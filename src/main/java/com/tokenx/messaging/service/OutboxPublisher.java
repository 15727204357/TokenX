package com.tokenx.messaging.service;

import com.tokenx.messaging.config.MessagingProperties;
import com.tokenx.messaging.mapper.OutboxEventMapper;
import com.tokenx.messaging.mapper.OutboxEventMapper.OutboxEventRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Publishes a bounded batch outside the MySQL claim transaction and retries durable failures. */
@Component
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final OutboxEventMapper outboxMapper;
  private final OrderEventKafkaSender kafkaSender;
  private final MessagingProperties properties;
  private final Clock clock;
  private final TransactionTemplate claimTemplate;

  public OutboxPublisher(
      OutboxEventMapper outboxMapper,
      OrderEventKafkaSender kafkaSender,
      MessagingProperties properties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.outboxMapper = outboxMapper;
    this.kafkaSender = kafkaSender;
    this.properties = properties;
    this.clock = clock;
    this.claimTemplate = new TransactionTemplate(transactionManager);
    this.claimTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Exposed for deterministic integration tests and an operator-triggered repair run. */
  public int publishBatch() {
    LocalDateTime now = nowColumn();
    List<OutboxEventRecord> claimed =
        claimTemplate.execute(
            status ->
                outboxMapper.claimDue(
                    now,
                    now.plus(properties.getOutboxLease()),
                    properties.getOutboxBatchSize()));
    if (claimed == null || claimed.isEmpty()) {
      return 0;
    }
    for (OutboxEventRecord event : claimed) {
      publish(event);
    }
    return claimed.size();
  }

  private void publish(OutboxEventRecord event) {
    try {
      kafkaSender.send(properties.getOrderEventsTopic(), event.messageKey(), event.payload());
      outboxMapper.markPublished(event.id(), nowColumn());
    } catch (Exception exception) {
      String errorCode = exception.getClass().getSimpleName();
      if (event.attemptCount() >= properties.getOutboxMaxAttempts()) {
        try {
          kafkaSender.send(
              properties.getOrderEventsDltTopic(), event.messageKey(), event.payload());
          outboxMapper.markDeadLettered(event.id(), errorCode);
          log.error(
              "order event moved to DLT eventId={} attempts={} cause={}",
              event.eventId(),
              event.attemptCount(),
              errorCode);
        } catch (Exception deadLetterFailure) {
          scheduleRetry(event, "DLT_SEND_" + deadLetterFailure.getClass().getSimpleName());
        }
      } else {
        scheduleRetry(event, errorCode);
      }
    }
  }

  private void scheduleRetry(OutboxEventRecord event, String errorCode) {
    Duration delay = retryDelay(event.attemptCount());
    outboxMapper.scheduleRetry(event.id(), nowColumn().plus(delay), shorten(errorCode));
    log.warn(
        "order event publish retry scheduled eventId={} attempt={} delayMs={} cause={}",
        event.eventId(),
        event.attemptCount(),
        delay.toMillis(),
        errorCode);
  }

  private Duration retryDelay(int attempt) {
    long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
    Duration base = properties.getRetryBaseDelay();
    long delayMillis;
    try {
      delayMillis = Math.multiplyExact(base.toMillis(), multiplier);
    } catch (ArithmeticException overflow) {
      delayMillis = Long.MAX_VALUE;
    }
    return Duration.ofMillis(Math.min(delayMillis, properties.getRetryMaxDelay().toMillis()));
  }

  private LocalDateTime nowColumn() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private static String shorten(String value) {
    return value.length() <= 64 ? value : value.substring(0, 64);
  }
}
