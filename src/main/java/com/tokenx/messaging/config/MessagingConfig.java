package com.tokenx.messaging.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Kafka topic and bounded consumer retry configuration for the event path. */
@Configuration
@EnableKafka
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfig {

  private static final Logger log = LoggerFactory.getLogger(MessagingConfig.class);

  @Bean
  @ConditionalOnProperty(prefix = "tokenx.messaging", name = "kafka-enabled", havingValue = "true")
  NewTopic orderEventsTopic(MessagingProperties properties) {
    return new NewTopic(
        properties.getOrderEventsTopic(),
        properties.getTopicPartitions(),
        (short) properties.getTopicReplicationFactor());
  }

  @Bean
  @ConditionalOnProperty(prefix = "tokenx.messaging", name = "kafka-enabled", havingValue = "true")
  NewTopic orderEventsDeadLetterTopic(MessagingProperties properties) {
    return new NewTopic(
        properties.getOrderEventsDltTopic(),
        properties.getTopicPartitions(),
        (short) properties.getTopicReplicationFactor());
  }

  @Bean
  @ConditionalOnProperty(prefix = "tokenx.messaging", name = "kafka-enabled", havingValue = "true")
  CommonErrorHandler orderEventsErrorHandler(
      KafkaTemplate<Object, Object> kafkaTemplate, MessagingProperties properties) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) ->
                new TopicPartition(properties.getOrderEventsDltTopic(), record.partition()));
    recoverer.setFailIfSendResultIsError(true);
    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            recoverer,
            new FixedBackOff(
                properties.getListenerRetryDelay().toMillis(),
                properties.getListenerRetryCount()));
    handler.setRetryListeners(
        (record, exception, deliveryAttempt) ->
            log.warn(
                "order event consume retry topic={} partition={} offset={} attempt={} type={}",
                record.topic(),
                record.partition(),
                record.offset(),
                deliveryAttempt,
                exception.getClass().getSimpleName()));
    return handler;
  }
}
