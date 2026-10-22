package com.tokenx.messaging.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.tokenx.messaging.mapper.OutboxEventMapper;
import com.tokenx.messaging.model.OrderPaidEvent;
import com.tokenx.order.model.PurchaseOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Creates the durable order-paid event inside the payment transaction. */
@Service
public class OrderPaidOutboxWriter {

  private final OutboxEventMapper outboxMapper;
  private final ObjectMapper objectMapper;

  public OrderPaidOutboxWriter(OutboxEventMapper outboxMapper, ObjectMapper objectMapper) {
    this.outboxMapper = outboxMapper;
    this.objectMapper = objectMapper;
  }

  public void record(PurchaseOrder order, Instant paidAt) {
    UUID eventId = UUID.randomUUID();
    OrderPaidEvent event =
        new OrderPaidEvent(
            1,
            eventId,
            "ORDER_PAID",
            order.getOrderNo(),
            order.getOrderType().name(),
            order.getPayAmountCent(),
            order.getCreditAmount(),
            paidAt);
    try {
      outboxMapper.insertOrderPaid(
          eventId,
          order.getOrderNo(),
          objectMapper.writeValueAsString(event),
          LocalDateTime.ofInstant(paidAt, ZoneOffset.UTC));
    } catch (JacksonException exception) {
      throw new IllegalStateException("could not serialize order-paid event", exception);
    }
  }
}
