package com.tokenx.messaging.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded Kafka and Outbox settings for the order event path. */
@Validated
@ConfigurationProperties("tokenx.messaging")
public class MessagingProperties {

  private String orderEventsTopic = "tokenx.order-events.v1";
  private String orderEventsDltTopic = "tokenx.order-events.dlt.v1";
  private String consumerGroupId = "tokenx-order-events-v1";
  private boolean kafkaEnabled = true;
  private boolean outboxEnabled = true;
  @Min(1) @Max(1000) private int topicPartitions = 1;
  @Min(1) @Max(10) private int topicReplicationFactor = 1;
  @Min(1) @Max(10) private int listenerConcurrency = 1;
  @Min(1) @Max(500) private int outboxBatchSize = 25;
  @Min(1) @Max(10) private int outboxMaxAttempts = 5;
  @NotNull private Duration outboxPollInterval = Duration.ofSeconds(1);
  @NotNull private Duration outboxLease = Duration.ofSeconds(90);
  @NotNull private Duration retryBaseDelay = Duration.ofSeconds(1);
  @NotNull private Duration retryMaxDelay = Duration.ofSeconds(30);
  @NotNull private Duration sendTimeout = Duration.ofSeconds(3);
  @NotNull private Duration listenerRetryDelay = Duration.ofMillis(250);
  @Min(0) @Max(10) private long listenerRetryCount = 3;

  public String getOrderEventsTopic() {
    return orderEventsTopic;
  }

  public void setOrderEventsTopic(String value) {
    orderEventsTopic = value;
  }

  public String getOrderEventsDltTopic() {
    return orderEventsDltTopic;
  }

  public void setOrderEventsDltTopic(String value) {
    orderEventsDltTopic = value;
  }

  public String getConsumerGroupId() {
    return consumerGroupId;
  }

  public void setConsumerGroupId(String value) {
    consumerGroupId = value;
  }

  public boolean isKafkaEnabled() {
    return kafkaEnabled;
  }

  public void setKafkaEnabled(boolean value) {
    kafkaEnabled = value;
  }

  public boolean isOutboxEnabled() {
    return outboxEnabled;
  }

  public void setOutboxEnabled(boolean value) {
    outboxEnabled = value;
  }

  public int getTopicPartitions() {
    return topicPartitions;
  }

  public void setTopicPartitions(int value) {
    topicPartitions = value;
  }

  public int getTopicReplicationFactor() {
    return topicReplicationFactor;
  }

  public void setTopicReplicationFactor(int value) {
    topicReplicationFactor = value;
  }

  public int getListenerConcurrency() {
    return listenerConcurrency;
  }

  public void setListenerConcurrency(int value) {
    listenerConcurrency = value;
  }

  public int getOutboxBatchSize() {
    return outboxBatchSize;
  }

  public void setOutboxBatchSize(int value) {
    outboxBatchSize = value;
  }

  public int getOutboxMaxAttempts() {
    return outboxMaxAttempts;
  }

  public void setOutboxMaxAttempts(int value) {
    outboxMaxAttempts = value;
  }

  public Duration getOutboxPollInterval() {
    return outboxPollInterval;
  }

  public void setOutboxPollInterval(Duration value) {
    outboxPollInterval = value;
  }

  public Duration getOutboxLease() {
    return outboxLease;
  }

  public void setOutboxLease(Duration value) {
    outboxLease = value;
  }

  public Duration getRetryBaseDelay() {
    return retryBaseDelay;
  }

  public void setRetryBaseDelay(Duration value) {
    retryBaseDelay = value;
  }

  public Duration getRetryMaxDelay() {
    return retryMaxDelay;
  }

  public void setRetryMaxDelay(Duration value) {
    retryMaxDelay = value;
  }

  public Duration getSendTimeout() {
    return sendTimeout;
  }

  public void setSendTimeout(Duration value) {
    sendTimeout = value;
  }

  public Duration getListenerRetryDelay() {
    return listenerRetryDelay;
  }

  public void setListenerRetryDelay(Duration value) {
    listenerRetryDelay = value;
  }

  public long getListenerRetryCount() {
    return listenerRetryCount;
  }

  public void setListenerRetryCount(long value) {
    listenerRetryCount = value;
  }

  @PostConstruct
  public void validate() {
    if (orderEventsTopic == null
        || orderEventsTopic.isBlank()
        || orderEventsDltTopic == null
        || orderEventsDltTopic.isBlank()
        || orderEventsTopic.equals(orderEventsDltTopic)
        || consumerGroupId == null
        || consumerGroupId.isBlank()) {
      throw new IllegalStateException("tokenx.messaging topic and consumer group names are required");
    }
    requireDuration(outboxPollInterval, Duration.ofMillis(10), Duration.ofMinutes(1), "poll interval");
    requireDuration(outboxLease, Duration.ofSeconds(1), Duration.ofMinutes(5), "outbox lease");
    requireDuration(retryBaseDelay, Duration.ofMillis(1), Duration.ofMinutes(5), "retry base delay");
    requireDuration(retryMaxDelay, retryBaseDelay, Duration.ofHours(1), "retry max delay");
    requireDuration(sendTimeout, Duration.ofMillis(1), Duration.ofSeconds(10), "send timeout");
    requireDuration(listenerRetryDelay, Duration.ofMillis(1), Duration.ofMinutes(1), "listener retry delay");
    if (outboxLease.compareTo(sendTimeout.multipliedBy(outboxBatchSize)) < 0) {
      throw new IllegalStateException(
          "tokenx.messaging.outbox-lease must cover one bounded batch of Kafka send timeouts");
    }
  }

  private static void requireDuration(Duration value, Duration min, Duration max, String name) {
    if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
      throw new IllegalStateException("tokenx.messaging " + name + " is outside the supported range");
    }
  }
}
