package com.tokenx.messaging.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.tokenx.messaging.config.MessagingProperties;
import com.tokenx.messaging.mapper.ProcessedMessageMapper;
import com.tokenx.messaging.model.OrderPaidEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Validates and records a downstream payment event exactly once per consumer and event id. */
@Service
public class OrderPaidEventProcessor {

  private static final String CONSUMER_NAME = "order-paid-receipt-v1";

  private final ObjectMapper objectMapper;
  private final ProcessedMessageMapper processedMapper;
  private final MessagingProperties properties;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public OrderPaidEventProcessor(
      ObjectMapper objectMapper,
      ProcessedMessageMapper processedMapper,
      MessagingProperties properties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.objectMapper = objectMapper;
    this.processedMapper = processedMapper;
    this.properties = properties;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public boolean process(String payload) {
    OrderPaidEvent event = parse(payload);
    validate(event);
    LocalDateTime processedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    try {
      Boolean inserted =
          transactionTemplate.execute(
              status -> {
                if (processedMapper.exists(CONSUMER_NAME, event.eventId())) {
                  return false;
                }
                processedMapper.insert(
                    CONSUMER_NAME,
                    event.eventId(),
                    event.eventType(),
                    event.schemaVersion(),
                    processedAt);
                return true;
              });
      return Boolean.TRUE.equals(inserted);
    } catch (DuplicateKeyException duplicate) {
      // Concurrent delivery may race between the existence check and the unique insert.
      if (processedMapper.exists(CONSUMER_NAME, event.eventId())) {
        return false;
      }
      throw duplicate;
    }
  }

  private OrderPaidEvent parse(String payload) {
    try {
      return objectMapper.readValue(payload, OrderPaidEvent.class);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("invalid order event payload", exception);
    }
  }

  private void validate(OrderPaidEvent event) {
    if (event.schemaVersion() != 1
        || !"ORDER_PAID".equals(event.eventType())
        || event.eventId() == null
        || event.orderNo() == null
        || event.orderNo().isBlank()
        || event.orderType() == null
        || event.payAmountCent() <= 0
        || event.creditAmount() <= 0
        || event.paidAt() == null) {
      throw new IllegalArgumentException("unsupported or invalid order-paid event");
    }
  }

  public UUID parseEventId(String payload) {
    return parse(payload).eventId();
  }

  public String consumerName() {
    return CONSUMER_NAME;
  }
}
