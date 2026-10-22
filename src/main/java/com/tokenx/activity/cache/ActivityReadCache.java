package com.tokenx.activity.cache;

import com.tokenx.activity.model.PublishedActivityRow;
import com.tokenx.infrastructure.error.BusinessException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Cache-Aside for published activity snapshots. MySQL remains the only source of trading truth. */
@Component
public class ActivityReadCache {

  private static final Logger log = LoggerFactory.getLogger(ActivityReadCache.class);
  private static final String NOT_FOUND = "N";
  private static final String CACHE_FULL = "ACTIVITY_READ_CAPACITY_EXCEEDED";
  private static final String CACHE_UNAVAILABLE = "ACTIVITY_READ_UNAVAILABLE";

  private final StringRedisTemplate redis;
  private final ActivityCacheProperties properties;
  private final Semaphore ingress;
  private final Semaphore ingressQueue;
  private final Semaphore fallback;
  private final Semaphore fallbackQueue;
  private final Semaphore invalidations;

  public ActivityReadCache(StringRedisTemplate redis, ActivityCacheProperties properties) {
    this.redis = redis;
    this.properties = properties;
    this.ingress = new Semaphore(properties.getMaxConcurrentRequests());
    this.ingressQueue = new Semaphore(properties.getMaxQueuedRequests());
    this.fallback = new Semaphore(properties.getFallbackConcurrency());
    this.fallbackQueue = new Semaphore(properties.getFallbackQueueCapacity());
    this.invalidations = new Semaphore(properties.getMaxConcurrentRequests());
  }

  /**
   * Returns a cached or database snapshot, enforcing both total and failure-path concurrency bounds.
   */
  public Optional<PublishedActivityRow> find(long id, Supplier<PublishedActivityRow> databaseRead) {
    acquireIngress();
    try {
      return findWithinPermit(id, databaseRead);
    } finally {
      ingress.release();
    }
  }

  /** Schedules invalidation only after the caller's DB transaction has committed. */
  public void invalidateAfterCommit(long id) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              invalidate(id);
            }
          });
    } else {
      invalidate(id);
    }
  }

  /** Invalidates immediately when the caller has already observed its transaction commit. */
  public void invalidateCommitted(long id) {
    invalidate(id);
  }

  private void invalidate(long id) {
    // Maintenance is best-effort and never queues behind a busy Redis connection after DB commit.
    if (!invalidations.tryAcquire()) {
      log.debug("activity cache invalidation deferred to TTL activityId={}", id);
      return;
    }
    try {
      redis.opsForValue().increment(generationKey(id));
    } catch (RuntimeException ex) {
      // Cache maintenance never changes the result of an already committed database transaction.
      log.warn(
          "activity cache invalidation failed activityId={} type={}",
          id,
          ex.getClass().getSimpleName());
    } finally {
      invalidations.release();
    }
  }

  public Duration nextPositiveTtl() {
    return randomDuration(properties.getPositiveTtlMin(), properties.getPositiveTtlMax());
  }

  public Duration nextNegativeTtl() {
    return randomDuration(properties.getNegativeTtlMin(), properties.getNegativeTtlMax());
  }

  private Optional<PublishedActivityRow> findWithinPermit(
      long id, Supplier<PublishedActivityRow> databaseRead) {
    final String generation;
    try {
      generation = currentGeneration(id);
      String encoded = redis.opsForValue().get(entryKey(id, generation));
      if (encoded != null) {
        return decode(encoded);
      }
    } catch (RuntimeException redisFailure) {
      return safeFallback(id, databaseRead, redisFailure);
    }

    PublishedActivityRow row = databaseRead.get();
    try {
      // Versioned keys make a stale concurrent fill unreachable after post-commit generation bump.
      redis
          .opsForValue()
          .set(
              entryKey(id, generation),
              encode(row),
              row == null ? nextNegativeTtl() : nextPositiveTtl());
    } catch (RuntimeException redisFailure) {
      log.debug(
          "activity cache write skipped activityId={} type={}",
          id,
          redisFailure.getClass().getSimpleName());
    }
    return Optional.ofNullable(row);
  }

  private Optional<PublishedActivityRow> safeFallback(
      long id, Supplier<PublishedActivityRow> databaseRead, RuntimeException redisFailure) {
    log.debug(
        "activity cache unavailable activityId={} type={}",
        id,
        redisFailure.getClass().getSimpleName());
    if (!fallback.tryAcquire()) {
      if (!fallbackQueue.tryAcquire()) {
        throw unavailable();
      }
      try {
        acquire(
            fallback,
            properties.getFallbackWait(),
            HttpStatus.SERVICE_UNAVAILABLE,
            CACHE_UNAVAILABLE,
            "activity cache unavailable and database fallback is full");
      } finally {
        fallbackQueue.release();
      }
    }
    try {
      return Optional.ofNullable(databaseRead.get());
    } finally {
      fallback.release();
    }
  }


  private void acquireIngress() {
    if (ingress.tryAcquire()) {
      return;
    }
    if (!ingressQueue.tryAcquire()) {
      throw new BusinessException(
          HttpStatus.TOO_MANY_REQUESTS, CACHE_FULL, "activity detail capacity is full");
    }
    try {
      acquire(
          ingress,
          properties.getIngressWait(),
          HttpStatus.TOO_MANY_REQUESTS,
          CACHE_FULL,
          "activity detail capacity is full");
    } finally {
      ingressQueue.release();
    }
  }

  private static BusinessException unavailable() {
    return new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        CACHE_UNAVAILABLE,
        "activity cache unavailable and database fallback is full");
  }

  private void acquire(
      Semaphore semaphore, Duration wait, HttpStatus status, String code, String message) {
    try {
      if (!semaphore.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS)) {
        throw new BusinessException(status, code, message);
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new BusinessException(status, code, message);
    }
  }

  private String currentGeneration(long id) {
    String value = redis.opsForValue().get(generationKey(id));
    return value == null ? "0" : value;
  }

  private String generationKey(long id) {
    return properties.getNamespace() + ":" + id + ":generation";
  }

  private String entryKey(long id, String generation) {
    return properties.getNamespace() + ":" + id + ":" + generation;
  }

  private static String encode(PublishedActivityRow row) {
    if (row == null) {
      return NOT_FOUND;
    }
    return String.join(
        "|",
        "P",
        row.getId().toString(),
        row.getPackageId().toString(),
        row.getActivityPriceCent().toString(),
        row.getCreditAmountSnapshot().toString(),
        toEpochMilli(row.getStartAt()).toString(),
        toEpochMilli(row.getEndAt()).toString(),
        row.getTotalStock().toString(),
        row.getAvailableStock().toString());
  }

  private static Optional<PublishedActivityRow> decode(String value) {
    if (NOT_FOUND.equals(value)) {
      return Optional.empty();
    }
    String[] fields = value.split("\\|", -1);
    if (fields.length != 9 || !"P".equals(fields[0])) {
      throw new IllegalArgumentException("invalid cache entry");
    }
    PublishedActivityRow row = new PublishedActivityRow();
    row.setId(Long.valueOf(fields[1]));
    row.setPackageId(Long.valueOf(fields[2]));
    row.setActivityPriceCent(Long.valueOf(fields[3]));
    row.setCreditAmountSnapshot(Long.valueOf(fields[4]));
    row.setStartAt(
        LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(Long.parseLong(fields[5])), ZoneOffset.UTC));
    row.setEndAt(
        LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(Long.parseLong(fields[6])), ZoneOffset.UTC));
    row.setTotalStock(Integer.valueOf(fields[7]));
    row.setAvailableStock(Integer.valueOf(fields[8]));
    return Optional.of(row);
  }

  private static Long toEpochMilli(LocalDateTime value) {
    return value.toInstant(ZoneOffset.UTC).toEpochMilli();
  }

  private static Duration randomDuration(Duration min, Duration max) {
    long low = min.toMillis();
    long high = max.toMillis();
    return Duration.ofMillis(ThreadLocalRandom.current().nextLong(low, high + 1));
  }
}
