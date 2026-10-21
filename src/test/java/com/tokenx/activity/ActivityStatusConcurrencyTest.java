package com.tokenx.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.tokenx.activity.model.ActivityStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Concurrent status changes against real MySQL.
 *
 * <p>Status changes are low frequency, so the goal is not throughput: it is that two simultaneous
 * admin actions produce an explainable serial result. The row lock is the whole mechanism, and these
 * tests are the evidence that it is actually taken:
 *
 * <ul>
 *   <li>two publishes of the same activity both succeed and both report the final state;
 *   <li>a publish really blocks while another transaction holds the row lock — the failure mode of a
 *       plain {@code SELECT}, which would return a stale snapshot instead of waiting;
 *   <li>opposite targets never leave an illegal state behind, and never answer {@code 500}.
 * </ul>
 *
 * <p>Every wait is bounded, so a lock that is never released fails the test instead of hanging it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityStatusConcurrencyTest extends AbstractActivityApiTest {

  private static final long TIMEOUT_SECONDS = 10;

  @Autowired private DataSource dataSource;

  @Test
  void shouldLetTwoConcurrentPublishesSucceed() throws Exception {
    String token = adminToken();
    long id = createDraft(token, onSalePackage(1000L), 799L, 10L);

    List<MvcResult> results = runConcurrently(token, id, "PUBLISHED", "PUBLISHED");

    assertThat(results)
        .as("neither request may fail because the other one is running")
        .allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
    assertThat(publishStatusOf(results.get(0))).isEqualTo("PUBLISHED");
    assertThat(publishStatusOf(results.get(1))).isEqualTo("PUBLISHED");
    assertThat(storedActivity(id).getStatus()).isEqualTo(ActivityStatus.PUBLISHED);
  }

  @Test
  void shouldBlockAStatusChangeUntilTheRowLockIsReleased() throws Exception {
    String token = adminToken();
    long id = createDraft(token, onSalePackage(1000L), 799L, 10L);

    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Connection locker = dataSource.getConnection()) {
      locker.setAutoCommit(false);
      try (PreparedStatement lock =
          locker.prepareStatement("SELECT id FROM seckill_activity WHERE id = ? FOR UPDATE")) {
        lock.setLong(1, id);
        lock.executeQuery().close();
      }

      CountDownLatch started = new CountDownLatch(1);
      Future<MvcResult> pending =
          worker.submit(
              () -> {
                started.countDown();
                return changeStatus(token, id, "PUBLISHED").andReturn();
              });

      assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(300);
      assertThat(pending.isDone())
          .as("the status change must wait for the row lock instead of returning a stale snapshot")
          .isFalse();

      locker.commit();

      MvcResult result = pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      assertThat(publishStatusOf(result)).isEqualTo("PUBLISHED");
    } finally {
      worker.shutdownNow();
    }

    assertThat(storedActivity(id).getStatus())
        .as("the blocked request must apply its change once the lock is released")
        .isEqualTo(ActivityStatus.PUBLISHED);
  }

  @Test
  void shouldSerialiseOppositeStatusChanges() throws Exception {
    String token = adminToken();
    long id = createDraft(token, onSalePackage(1000L), 799L, 10L);

    List<MvcResult> results = runConcurrently(token, id, "PUBLISHED", "OFFLINE");

    for (MvcResult result : results) {
      int status = result.getResponse().getStatus();
      assertThat(status)
          .as("the two requests race on one row; the loser must be a conflict, never a 500")
          .isIn(200, 409);
      if (status == 409) {
        assertThat(errorCodeOf(result)).isEqualTo("ACTIVITY_STATE_CONFLICT");
      }
    }

    // The winner either published (then offline) or cancelled the draft; both are legal end states.
    assertThat(storedActivity(id).getStatus())
        .isIn(ActivityStatus.PUBLISHED, ActivityStatus.OFFLINE);
  }

  private List<MvcResult> runConcurrently(String token, long id, String first, String second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Callable<MvcResult> firstCall =
          () -> {
            start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return changeStatus(token, id, first).andReturn();
          };
      Callable<MvcResult> secondCall =
          () -> {
            start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return changeStatus(token, id, second).andReturn();
          };

      Future<MvcResult> firstFuture = pool.submit(firstCall);
      Future<MvcResult> secondFuture = pool.submit(secondCall);
      start.countDown();

      return List.of(
          firstFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          secondFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  private String publishStatusOf(MvcResult result) throws Exception {
    return objectMapper.readTree(body(result)).get("publishStatus").asText();
  }

  private String errorCodeOf(MvcResult result) throws Exception {
    JsonNode payload = objectMapper.readTree(body(result));
    return payload.get("code").asText();
  }
}
