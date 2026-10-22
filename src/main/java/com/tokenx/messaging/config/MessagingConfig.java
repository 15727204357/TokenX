package com.tokenx.messaging.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
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
  @ConditionalOnExpression("'${tokenx.messaging.kafka-enabled:true}' == 'true' && '${tokenx.seckill.mode:SYNC}' == 'ASYNC'")
  NewTopic seckillOrderCommandsTopic(MessagingProperties properties) {
    return new NewTopic(properties.getSeckillOrdersTopic(), properties.getSeckillTopicPartitions(), (short) properties.getTopicReplicationFactor());
  }

  @Bean
  @ConditionalOnExpression("'${tokenx.messaging.kafka-enabled:true}' == 'true' && '${tokenx.seckill.mode:SYNC}' == 'ASYNC'")
  NewTopic seckillOrderCommandsDltTopic(MessagingProperties properties) {
    return new NewTopic(properties.getSeckillOrdersDltTopic(), properties.getSeckillTopicPartitions(), (short) properties.getTopicReplicationFactor());
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

  @Bean(name="seckillKafkaListenerContainerFactory")
  ConcurrentKafkaListenerContainerFactory<Object,Object> seckillKafkaListenerContainerFactory(
      ConsumerFactory<Object,Object> consumerFactory, KafkaTemplate<Object,Object> kafkaTemplate,
      MessagingProperties properties) {
    var factory=new ConcurrentKafkaListenerContainerFactory<Object,Object>();
    factory.setConsumerFactory(consumerFactory);
    factory.setConcurrency(properties.getSeckillListenerConcurrency());
    var recoverer=new DeadLetterPublishingRecoverer(kafkaTemplate,
        (record,exception)->new TopicPartition(properties.getSeckillOrdersDltTopic(),record.partition()));
    recoverer.setFailIfSendResultIsError(true);
    var handler=new DefaultErrorHandler(recoverer,new FixedBackOff(properties.getListenerRetryDelay().toMillis(),properties.getListenerRetryCount()));
    handler.setBackOffFunction((record,exception)->hasCause(exception,org.springframework.dao.DataAccessException.class)
        ? new FixedBackOff(1000L,Long.MAX_VALUE)
        : new FixedBackOff(properties.getListenerRetryDelay().toMillis(),properties.getListenerRetryCount()));
    handler.setRetryListeners((record,exception,deliveryAttempt)->log.warn(
        "seckill command retry topic={} partition={} offset={} attempt={} type={} causeType={}",
        record.topic(),record.partition(),record.offset(),deliveryAttempt,exception.getClass().getSimpleName(),rootCause(exception).getClass().getSimpleName()));
    factory.setCommonErrorHandler(handler);
    return factory;
  }

  @Bean(name="seckillKafkaDltListenerContainerFactory")
  ConcurrentKafkaListenerContainerFactory<Object,Object> seckillKafkaDltListenerContainerFactory(
      ConsumerFactory<Object,Object> consumerFactory, MessagingProperties properties) {
    var factory=new ConcurrentKafkaListenerContainerFactory<Object,Object>();
    factory.setConsumerFactory(consumerFactory);
    factory.setConcurrency(1);
    factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L,Long.MAX_VALUE)));
    return factory;
  }

  private static boolean hasCause(Throwable error,Class<? extends Throwable> type){
    for(Throwable current=error;current!=null;current=current.getCause())if(type.isInstance(current))return true;
    return false;
  }

  private static Throwable rootCause(Throwable error){
    Throwable root=error;
    while(root.getCause()!=null&&root.getCause()!=root)root=root.getCause();
    return root;
  }
}
