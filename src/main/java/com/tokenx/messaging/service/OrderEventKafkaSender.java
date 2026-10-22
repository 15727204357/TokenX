package com.tokenx.messaging.service;

import com.tokenx.messaging.config.MessagingProperties;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Sends one record and bounds the time spent waiting for broker acknowledgement. */
@Component
public class OrderEventKafkaSender {

  private final KafkaTemplate<Object, Object> kafkaTemplate;
  private final MessagingProperties properties;

  public OrderEventKafkaSender(
      KafkaTemplate<Object, Object> kafkaTemplate, MessagingProperties properties) {
    this.kafkaTemplate = kafkaTemplate;
    this.properties = properties;
  }

  public void send(String topic, String key, String payload) throws Exception {
    kafkaTemplate
        .send(topic, key, payload)
        .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
  }
}
