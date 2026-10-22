package com.tokenx.messaging.service;

import tools.jackson.databind.ObjectMapper;
import com.tokenx.messaging.config.MessagingProperties;
import com.tokenx.messaging.model.SeckillOrderCommand;
import com.tokenx.order.mapper.SeckillRequestMapper;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.order.service.SeckillAdmissionGate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-publishes durable unconfirmed intents after a process crash; duplicate commands are DB-idempotent. */
@Component
public class SeckillCommandRecoveryTask {
  private static final Logger log = LoggerFactory.getLogger(SeckillCommandRecoveryTask.class);
  private static final Duration STALE_AFTER = Duration.ofSeconds(30);
  private static final int BATCH_SIZE = 50;

  private final SeckillRequestMapper requests;
  private final KafkaTemplate<Object, Object> kafka;
  private final MessagingProperties messaging;
  private final ObjectMapper json;
  private final SeckillAdmissionGate gate;
  private final Clock clock;
  private final com.tokenx.order.config.SeckillProperties seckill;

  public SeckillCommandRecoveryTask(
      SeckillRequestMapper requests,
      KafkaTemplate<Object, Object> kafka,
      MessagingProperties messaging,
      ObjectMapper json,
      SeckillAdmissionGate gate,
      Clock clock,
      com.tokenx.order.config.SeckillProperties seckill) {
    this.requests = requests;
    this.kafka = kafka;
    this.messaging = messaging;
    this.json = json;
    this.gate = gate;
    this.clock = clock;
    this.seckill = seckill;
  }

  @Scheduled(fixedDelayString = "${tokenx.seckill.recovery-interval:5s}")
  public void republishStaleReservations() {
    if (seckill.getMode() != com.tokenx.order.config.SeckillProperties.Mode.ASYNC
        || !messaging.isKafkaEnabled()) {
      return;
    }
    Instant now = clock.instant();
    var cutoff = PurchaseOrder.toColumn(now.minus(STALE_AFTER));
    for (var row : requests.findStaleReservations(cutoff, BATCH_SIZE)) {
      if (requests.claimStaleReservation(row.getRequestId(), cutoff, PurchaseOrder.toColumn(now)) == 0) {
        continue;
      }
      var command =
          new SeckillOrderCommand(
              1,
              com.tokenx.order.support.IdempotencyKeys.fromBytes(row.getRequestId()),
              row.getActivityId(),
              row.getUserId(),
              PurchaseOrder.toInstant(row.getCreatedAt()));
      try {
        String payload = json.writeValueAsString(command);
        kafka
            .send(messaging.getSeckillOrdersTopic(), Long.toString(row.getActivityId()), payload)
            .get(messaging.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        requests.markAcknowledged(row.getRequestId(), row.getUserId(), PurchaseOrder.toColumn(clock.instant()));
        try {
          if (!gate.queued(row.getActivityId(), row.getUserId(), command.requestId())) {
            gate.markRebuilding(row.getActivityId());
          }
        } catch (RuntimeException ignored) {
          // MySQL remains authoritative; a missing gate is repaired through the admin recovery path.
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        log.warn("stale seckill request republish interrupted requestId={}", command.requestId());
        return;
      } catch (ExecutionException | TimeoutException | RuntimeException ex) {
        log.warn(
            "stale seckill request republish deferred requestId={} type={}",
            command.requestId(),
            ex.getClass().getSimpleName());
      }
    }
  }
}
