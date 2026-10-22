package com.tokenx.messaging.service;

import com.tokenx.messaging.config.MessagingProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka adapter; record offsets are acknowledged only after the processor's DB transaction. */
@Component
@ConditionalOnProperty(prefix = "tokenx.messaging", name = "kafka-enabled", havingValue = "true")
public class OrderEventsListener {

  private final OrderPaidEventProcessor processor;

  public OrderEventsListener(OrderPaidEventProcessor processor) {
    this.processor = processor;
  }

  @KafkaListener(
      topics = "${tokenx.messaging.order-events-topic}",
      groupId = "${tokenx.messaging.consumer-group-id}",
      concurrency = "${tokenx.messaging.listener-concurrency:1}",
      autoStartup = "${tokenx.messaging.listener-enabled:true}")
  public void onOrderEvent(ConsumerRecord<String, String> record) {
    processor.process(record.value());
  }
}
