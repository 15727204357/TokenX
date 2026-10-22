package com.tokenx.activity.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tokenx.activity.model.PublishedActivityRow;
import com.tokenx.infrastructure.error.BusinessException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ActivityReadCacheProtectionTest {

  @Test
  void startupConfigurationRejectsUnboundedQueueAndRedisTimeoutValues() {
    ActivityCacheProperties invalidQueue = new ActivityCacheProperties();
    invalidQueue.setMaxQueuedRequests(10001);
    assertThatThrownBy(invalidQueue::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("max queued requests");

    ActivityCacheProperties invalidTimeout = new ActivityCacheProperties();
    invalidTimeout.setRedisCommandTimeout(Duration.ofSeconds(6));
    assertThatThrownBy(invalidTimeout::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Redis command timeout");
  }

  @Test
  void redisFailureAllowsOnlyConfiguredFallbackConcurrencyAndThenRecoversToCache() throws Exception {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    AtomicBoolean redisAvailable = new AtomicBoolean(false);
    Map<String, String> stored = new ConcurrentHashMap<>();
    when(values.get(anyString())).thenAnswer(invocation -> {
      if (!redisAvailable.get()) throw new RedisConnectionFailureException("test outage");
      return stored.get(invocation.getArgument(0));
    });
    doAnswer(invocation -> {
      stored.put(invocation.getArgument(0), invocation.getArgument(1));
      return null;
    }).when(values).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    ActivityCacheProperties properties = properties(2, 1, Duration.ZERO, Duration.ZERO);
    ActivityReadCache cache = new ActivityReadCache(redis, properties);
    CountDownLatch fallbackStarted = new CountDownLatch(1);
    CountDownLatch releaseFallback = new CountDownLatch(1);

    CompletableFuture<?> first = CompletableFuture.runAsync(() -> cache.find(81L, () -> {
      fallbackStarted.countDown();
      await(releaseFallback);
      return row(81L, 79L);
    }));
    assertThat(fallbackStarted.await(2, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> cache.find(82L, () -> row(82L, 79L)))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex -> {
              BusinessException error = (BusinessException) ex;
              assertThat(error.getStatus().value()).isEqualTo(503);
              assertThat(error.getCode()).isEqualTo("ACTIVITY_READ_UNAVAILABLE");
            });

    releaseFallback.countDown();
    first.join();
    redisAvailable.set(true);
    AtomicInteger databaseReads = new AtomicInteger();
    assertThat(cache.find(81L, () -> {
      databaseReads.incrementAndGet();
      return row(81L, 55L);
    }).orElseThrow().getActivityPriceCent()).isEqualTo(55L);
    assertThat(cache.find(81L, () -> {
      databaseReads.incrementAndGet();
      throw new AssertionError("a recovered Redis hit must avoid MySQL");
    }).orElseThrow().getActivityPriceCent()).isEqualTo(55L);
    assertThat(databaseReads).hasValue(1);
  }

  @Test
  void totalIngressSaturationReturns429WithoutOpeningAnotherFallback() throws Exception {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    CountDownLatch redisReadStarted = new CountDownLatch(1);
    CountDownLatch releaseRedisRead = new CountDownLatch(1);
    when(values.get(anyString())).thenAnswer(invocation -> {
      redisReadStarted.countDown();
      await(releaseRedisRead);
      return null;
    });
    ActivityReadCache cache = new ActivityReadCache(
        redis, properties(1, 1, Duration.ZERO, Duration.ZERO));
    CompletableFuture<?> first = CompletableFuture.runAsync(() -> cache.find(91L, () -> row(91L, 79L)));
    assertThat(redisReadStarted.await(2, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> cache.find(92L, () -> row(92L, 79L)))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex -> {
              BusinessException error = (BusinessException) ex;
              assertThat(error.getStatus().value()).isEqualTo(429);
              assertThat(error.getCode()).isEqualTo("ACTIVITY_READ_CAPACITY_EXCEEDED");
            });

    releaseRedisRead.countDown();
    first.join();
  }

  private static ActivityCacheProperties properties(
      int maxRequests, int fallbackConcurrency, Duration ingressWait, Duration fallbackWait) {
    ActivityCacheProperties p = new ActivityCacheProperties();
    p.setMaxConcurrentRequests(maxRequests);
    p.setFallbackConcurrency(fallbackConcurrency);
    p.setIngressWait(ingressWait);
    p.setFallbackWait(fallbackWait);
    return p;
  }

  private static PublishedActivityRow row(long id, long price) {
    PublishedActivityRow row = new PublishedActivityRow();
    row.setId(id);
    row.setPackageId(3L);
    row.setActivityPriceCent(price);
    row.setCreditAmountSnapshot(100L);
    row.setStartAt(java.time.LocalDateTime.of(2026, 10, 22, 10, 0));
    row.setEndAt(java.time.LocalDateTime.of(2026, 10, 22, 12, 0));
    row.setTotalStock(5);
    row.setAvailableStock(5);
    return row;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    }
  }
}
