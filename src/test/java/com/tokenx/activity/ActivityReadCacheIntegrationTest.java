package com.tokenx.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.cache.ActivityReadCache;
import com.tokenx.activity.model.PublishedActivityRow;
import com.tokenx.support.TestClockConfig;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Real MySQL and Redis coverage for the one public activity cache. */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityReadCacheIntegrationTest extends AbstractActivityApiTest {

  private static final String CACHE_PREFIX = "tokenx:activity:public:v1:";

  @Autowired private StringRedisTemplate redis;
  @Autowired private ActivityReadCache activityReadCache;

  @Test
  void shouldCachePublishedSnapshotAndUseItUntilInvalidated() throws Exception {
    String admin = adminToken();
    long packageId = onSalePackage(100L);
    long activityId = publishedActivity(admin, packageId, 79L, 5L);
    String generation = redis.opsForValue().get(CACHE_PREFIX + activityId + ":generation");
    String entryKey = CACHE_PREFIX + activityId + ":" + generation;

    MvcResult first = publicRead(activityId).andExpect(status().isOk()).andReturn();
    assertThat(body(first)).contains("\"activityPriceCent\":79");
    assertThat(redis.hasKey(entryKey)).isTrue();
    assertThat(redis.getExpire(entryKey)).isBetween(29L, 45L);

    var changed = storedActivity(activityId);
    changed.setActivityPriceCent(55L);
    activityMapper.updateById(changed);

    MvcResult second = publicRead(activityId).andExpect(status().isOk()).andReturn();
    assertThat(body(second)).contains("\"activityPriceCent\":79");
  }

  @Test
  void shouldNegativeCacheMissingAndUnpublishedActivitiesAsTheSameNotFound() throws Exception {
    String admin = adminToken();
    long packageId = onSalePackage(100L);
    long draftId = createDraft(admin, packageId, 79L, 5L);
    assertThat(redis.opsForValue().get(CACHE_PREFIX + draftId + ":generation")).isEqualTo("1");
    long absentId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    String generation = redis.opsForValue().get(CACHE_PREFIX + draftId + ":generation");
    String draftKey = CACHE_PREFIX + draftId + ":" + generation;
    String absentKey = CACHE_PREFIX + absentId + ":0";

    publicRead(draftId)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
    publicRead(absentId)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));

    assertThat(redis.hasKey(draftKey)).isTrue();
    assertThat(redis.hasKey(absentKey)).isTrue();
    assertThat(redis.getExpire(draftKey)).isBetween(4L, 10L);
    assertThat(redis.getExpire(absentKey)).isBetween(4L, 10L);
  }

  @Test
  void shouldRecalculateRuntimeStatusOnEveryCacheHit() throws Exception {
    String admin = adminToken();
    long activityId = publishedActivity(admin, onSalePackage(100L), 79L, 5L);
    publicRead(activityId).andExpect(status().isOk());

    TestClockConfig.advance(Duration.ofHours(1));
    MvcResult atStart = publicRead(activityId).andExpect(status().isOk()).andReturn();
    assertThat(body(atStart)).contains("\"runtimeStatus\":\"ACTIVE\"");

    TestClockConfig.advance(Duration.ofHours(1));
    MvcResult atEnd = publicRead(activityId).andExpect(status().isOk()).andReturn();
    assertThat(body(atEnd)).contains("\"runtimeStatus\":\"ENDED\"");
  }

  @Test
  void shouldKeepRuntimeStatusOutOfCacheAndJitterTtlsWithinConfiguredRanges() {
    for (int i = 0; i < 100; i++) {
      assertThat(activityReadCache.nextPositiveTtl())
          .isBetween(Duration.ofSeconds(30), Duration.ofSeconds(45));
      assertThat(activityReadCache.nextNegativeTtl())
          .isBetween(Duration.ofSeconds(5), Duration.ofSeconds(10));
    }
  }

  @Test
  void shouldNotAllowAnOldConcurrentFillToBecomeReachableAfterInvalidation() throws Exception {
    long id = publishedActivity(adminToken(), onSalePackage(100L), 79L, 5L);
    PublishedActivityRow oldSnapshot = row(id, 79L);
    PublishedActivityRow newSnapshot = row(id, 55L);
    CountDownLatch databaseReadStarted = new CountDownLatch(1);
    CountDownLatch allowOldReadToFinish = new CountDownLatch(1);
    CompletableFuture<?> pendingOldFill =
        CompletableFuture.runAsync(
            () ->
                activityReadCache.find(
                    id,
                    () -> {
                      databaseReadStarted.countDown();
                      try {
                        if (!allowOldReadToFinish.await(5, TimeUnit.SECONDS)) {
                          throw new IllegalStateException("timed out");
                        }
                      } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                      }
                      return oldSnapshot;
                    }));

    assertThat(databaseReadStarted.await(5, TimeUnit.SECONDS)).isTrue();
    activityReadCache.invalidateCommitted(id);
    allowOldReadToFinish.countDown();
    pendingOldFill.join();

    var current = activityReadCache.find(id, () -> newSnapshot).orElseThrow();
    assertThat(current.getActivityPriceCent()).isEqualTo(55L);
  }

  @Test
  void shouldInvalidateNegativeAndPositiveEntriesAfterPublishAndOffline() throws Exception {
    String admin = adminToken();
    long packageId = onSalePackage(100L);
    long activityId = createDraft(admin, packageId, 79L, 5L);
    publicRead(activityId)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));

    changeStatus(admin, activityId, "PUBLISHED").andExpect(status().isOk());
    publicRead(activityId).andExpect(status().isOk());

    changeStatus(admin, activityId, "OFFLINE").andExpect(status().isOk());
    publicRead(activityId)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
    String generation = redis.opsForValue().get(CACHE_PREFIX + activityId + ":generation");
    assertThat(redis.hasKey(CACHE_PREFIX + activityId + ":" + generation)).isTrue();
  }

  private static PublishedActivityRow row(long id, long price) {
    PublishedActivityRow row = new PublishedActivityRow();
    row.setId(id);
    row.setPackageId(17L);
    row.setActivityPriceCent(price);
    row.setCreditAmountSnapshot(100L);
    row.setStartAt(LocalDateTime.ofInstant(START, ZoneOffset.UTC));
    row.setEndAt(LocalDateTime.ofInstant(END, ZoneOffset.UTC));
    row.setTotalStock(5);
    row.setAvailableStock(5);
    return row;
  }

  private org.springframework.test.web.servlet.ResultActions publicRead(long id) throws Exception {
    return mockMvc.perform(get("/api/v1/activities/" + id).accept(MediaType.APPLICATION_JSON));
  }
}
