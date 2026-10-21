package com.tokenx.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.tokenx.catalog.model.PackageStatus;
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
 * <p>The previous implementation read the row with a plain {@code SELECT}, so under
 * {@code REPEATABLE-READ} a second transaction could keep reading its snapshot after the first one
 * committed, and return a status that no longer existed. The fix reads the row with
 * {@code SELECT ... FOR UPDATE}; these tests are the evidence:
 *
 * <ul>
 *   <li>two publishes of the same package both succeed and both report the final status;
 *   <li>a request really blocks while another transaction holds the row lock, which is exactly what
 *       the old plain read failed to do;
 *   <li>opposite targets end in one of the two requested states, never in a third state or an error.
 * </ul>
 *
 * <p>Every wait is bounded, so a lock that is never released fails the test instead of hanging it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatusConcurrencyTest extends AbstractCatalogApiTest {

  private static final long TIMEOUT_SECONDS = 10;

  @Autowired private DataSource dataSource;

  @Test
  void shouldLetTwoConcurrentPublishesOfTheSamePackageSucceed() throws Exception {
    String token = adminToken();
    long id = createPackage(token, "并发上架包", 100L, 100L);

    List<MvcResult> results = runConcurrently(token, id, "ON_SALE", "ON_SALE");

    assertThat(results)
        .as("neither request may fail because the other one is running")
        .allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
    assertThat(statusOf(results.get(0))).isEqualTo("ON_SALE");
    assertThat(statusOf(results.get(1))).isEqualTo("ON_SALE");
    assertThat(packageMapper.selectById(id).getStatus()).isEqualTo(PackageStatus.ON_SALE);
  }

  @Test
  void shouldBlockAStatusChangeUntilTheRowLockIsReleased() throws Exception {
    String token = adminToken();
    long id = createPackage(token, "锁等待包", 100L, 100L);

    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Connection locker = dataSource.getConnection()) {
      locker.setAutoCommit(false);
      try (PreparedStatement lock =
          locker.prepareStatement("SELECT id FROM token_package WHERE id = ? FOR UPDATE")) {
        lock.setLong(1, id);
        lock.executeQuery().close();
      }

      CountDownLatch started = new CountDownLatch(1);
      Future<MvcResult> pending =
          worker.submit(
              () -> {
                started.countDown();
                return patchNow(token, id, "ON_SALE");
              });

      assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(300);
      assertThat(pending.isDone())
          .as("the status change must wait for the row lock instead of returning a stale snapshot")
          .isFalse();

      locker.commit();

      MvcResult result = pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      assertThat(statusOf(result)).isEqualTo("ON_SALE");
    } finally {
      worker.shutdownNow();
    }

    assertThat(packageMapper.selectById(id).getStatus())
        .as("the blocked request must apply its change once the lock is released")
        .isEqualTo(PackageStatus.ON_SALE);
  }

  @Test
  void shouldSerialiseOppositeStatusChangesIntoOneOfTheRequestedStates() throws Exception {
    String token = adminToken();
    long id = createPackage(token, "方向冲突包", 100L, 100L);

    List<MvcResult> results = runConcurrently(token, id, "ON_SALE", "OFF_SHELF");

    // A successful request always reports the status it acted on, so the pair is always
    // {ON_SALE, OFF_SHELF}. The row lock orders them; the last writer decides the stored value.
    assertThat(results)
        .allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
    assertThat(statusOf(results.get(0))).isEqualTo("ON_SALE");
    assertThat(statusOf(results.get(1))).isEqualTo("OFF_SHELF");

    assertThat(packageMapper.selectById(id).getStatus())
        .as("two opposite targets cannot both be final: exactly one of them is stored")
        .isIn(PackageStatus.ON_SALE, PackageStatus.OFF_SHELF);
  }

  private List<MvcResult> runConcurrently(String token, long id, String first, String second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Callable<MvcResult> firstCall =
          () -> {
            start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return patchNow(token, id, first);
          };
      Callable<MvcResult> secondCall =
          () -> {
            start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return patchNow(token, id, second);
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

  private MvcResult patchNow(String token, long id, String status) throws Exception {
    return patchStatus(token, id, status).andReturn();
  }

  private String statusOf(MvcResult result) throws Exception {
    JsonNode payload = objectMapper.readTree(body(result));
    return payload.get("status").asText();
  }
}
