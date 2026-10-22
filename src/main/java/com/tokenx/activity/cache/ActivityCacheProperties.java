package com.tokenx.activity.cache;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounds and lifetime settings for the single public activity detail cache. */
@Validated
@ConfigurationProperties("tokenx.activity-cache")
public class ActivityCacheProperties {

  private String namespace = "tokenx:activity:public:v1";

  @NotNull private Duration positiveTtlMin = Duration.ofSeconds(30);
  @NotNull private Duration positiveTtlMax = Duration.ofSeconds(45);
  @NotNull private Duration negativeTtlMin = Duration.ofSeconds(5);
  @NotNull private Duration negativeTtlMax = Duration.ofSeconds(10);
  @Min(1) @Max(10000) private int maxConcurrentRequests = 128;
  @Min(1) @Max(10000) private int maxQueuedRequests = 64;
  @NotNull private Duration ingressWait = Duration.ofMillis(10);
  @Min(1) @Max(1000) private int fallbackConcurrency = 8;
  @Min(1) @Max(1000) private int fallbackQueueCapacity = 8;
  @NotNull private Duration fallbackWait = Duration.ofMillis(20);
  @NotNull private Duration redisCommandTimeout = Duration.ofMillis(500);
  @NotNull private Duration redisConnectTimeout = Duration.ofMillis(500);

  public String getNamespace() {
    return namespace;
  }

  public void setNamespace(String namespace) {
    this.namespace = namespace;
  }

  public Duration getPositiveTtlMin() {
    return positiveTtlMin;
  }

  public void setPositiveTtlMin(Duration value) {
    positiveTtlMin = value;
  }

  public Duration getPositiveTtlMax() {
    return positiveTtlMax;
  }

  public void setPositiveTtlMax(Duration value) {
    positiveTtlMax = value;
  }

  public Duration getNegativeTtlMin() {
    return negativeTtlMin;
  }

  public void setNegativeTtlMin(Duration value) {
    negativeTtlMin = value;
  }

  public Duration getNegativeTtlMax() {
    return negativeTtlMax;
  }

  public void setNegativeTtlMax(Duration value) {
    negativeTtlMax = value;
  }

  public int getMaxConcurrentRequests() {
    return maxConcurrentRequests;
  }

  public void setMaxConcurrentRequests(int value) {
    maxConcurrentRequests = value;
  }

  public int getMaxQueuedRequests() {
    return maxQueuedRequests;
  }

  public void setMaxQueuedRequests(int value) {
    maxQueuedRequests = value;
  }

  public Duration getIngressWait() {
    return ingressWait;
  }

  public void setIngressWait(Duration value) {
    ingressWait = value;
  }

  public int getFallbackConcurrency() {
    return fallbackConcurrency;
  }

  public void setFallbackConcurrency(int value) {
    fallbackConcurrency = value;
  }

  public int getFallbackQueueCapacity() {
    return fallbackQueueCapacity;
  }

  public void setFallbackQueueCapacity(int value) {
    fallbackQueueCapacity = value;
  }

  public Duration getFallbackWait() {
    return fallbackWait;
  }

  public void setFallbackWait(Duration value) {
    fallbackWait = value;
  }

  public Duration getRedisCommandTimeout() {
    return redisCommandTimeout;
  }

  public void setRedisCommandTimeout(Duration value) {
    redisCommandTimeout = value;
  }

  public Duration getRedisConnectTimeout() {
    return redisConnectTimeout;
  }

  public void setRedisConnectTimeout(Duration value) {
    redisConnectTimeout = value;
  }

  public void validate() {
    if (namespace == null || !namespace.matches("[a-zA-Z0-9:_-]{1,100}")) {
      throw new IllegalStateException(
          "tokenx.activity-cache.namespace must be a short Redis-safe prefix");
    }
    requireLimit(maxConcurrentRequests, 1, 10000, "max concurrent requests");
    requireLimit(maxQueuedRequests, 1, 10000, "max queued requests");
    requireLimit(fallbackConcurrency, 1, 1000, "fallback concurrency");
    requireLimit(fallbackQueueCapacity, 1, 1000, "fallback queue capacity");
    requireRange(positiveTtlMin, positiveTtlMax, "positive TTL");
    requireRange(negativeTtlMin, negativeTtlMax, "negative TTL");
    requireNonNegative(ingressWait, "ingress wait");
    requireNonNegative(fallbackWait, "fallback wait");
    requireTimeout(redisCommandTimeout, "Redis command timeout");
    requireTimeout(redisConnectTimeout, "Redis connect timeout");
  }

  private static void requireLimit(int value, int min, int max, String name) {
    if (value < min || value > max) {
      throw new IllegalStateException(
          "activity cache " + name + " must be between " + min + " and " + max);
    }
  }

  private static void requireRange(Duration min, Duration max, String name) {
    if (min == null
        || max == null
        || min.isNegative()
        || min.isZero()
        || max.compareTo(min) < 0
        || max.compareTo(Duration.ofDays(1)) > 0) {
      throw new IllegalStateException("activity cache " + name + " range is invalid");
    }
  }

  private static void requireTimeout(Duration value, String name) {
    if (value == null
        || value.isNegative()
        || value.isZero()
        || value.compareTo(Duration.ofSeconds(5)) > 0) {
      throw new IllegalStateException("activity cache " + name + " must be between 1ms and 5s");
    }
  }

  private static void requireNonNegative(Duration value, String name) {
    if (value == null || value.isNegative() || value.compareTo(Duration.ofSeconds(5)) > 0) {
      throw new IllegalStateException("activity cache " + name + " must be between 0 and 5 seconds");
    }
  }
}
