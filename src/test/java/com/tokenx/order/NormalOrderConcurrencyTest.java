package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.order.model.OrderType;
import com.tokenx.order.model.PurchaseOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
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

/**
 * Concurrency on the normal purchase path, against real MySQL.
 *
 * <p>These are correctness tests, not benchmarks: nothing here measures throughput, and no number
 * produced here may be quoted as a QPS/TPS figure.
 *
 * <p>The path has no stock row to serialise on, so the only shared state between two buyers of the
 * same package is the package row they both read with {@code FOR SHARE}, and the only shared state
 * between two retries of one request is {@code uk_user_request}. Both are exercised below, together
 * with the one race that only a shared row can create: a buyer checking "is it on sale" while an
 * admin withdraws it.
 *
 * <p>Every wait is bounded, so a lock that is never released fails the test instead of hanging it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NormalOrderConcurrencyTest extends AbstractOrderApiTest {

  private static final long TIMEOUT_SECONDS = 20;

  @Autowired private DataSource dataSource;

  @Test
  void shouldCreateOneOrderWhenTheSameKeyRaces() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);
    String key = newKey();

    List<Callable<MvcResult>> calls =
        List.of(
            () -> placeNormalOrder(buyer.token(), packageId, key).andReturn(),
            () -> placeNormalOrder(buyer.token(), packageId, key).andReturn());

    List<MvcResult> results = runConcurrently(calls);

    assertThat(results.stream().filter(result -> statusOf(result) == 201).count())
        .as("a duplicate key must roll back, so exactly one request creates the order")
        .isEqualTo(1);
    assertThat(results.stream().filter(result -> statusOf(result) == 200).count())
        .as("the other request replays the same order instead of failing")
        .isEqualTo(1);
    assertThat(results)
        .allSatisfy(
            result ->
                assertThat(statusOf(result))
                    .as("a race may only produce 201 or a replay — never a 500")
                    .isIn(200, 201));
    assertThat(orderNoOf(results.get(0))).isEqualTo(orderNoOf(results.get(1)));

    assertThat(storedOrdersOfUser(buyer.id())).hasSize(1);
  }

  @Test
  void shouldCreateIndependentOrdersWhenDifferentKeysRace() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);

    List<Callable<MvcResult>> calls = new ArrayList<>();
    for (int index = 0; index < 6; index++) {
      String key = newKey();
      calls.add(() -> placeNormalOrder(buyer.token(), packageId, key).andReturn());
    }

    List<MvcResult> results = runConcurrently(calls);

    assertThat(results.stream().filter(result -> statusOf(result) == 201).count())
        .as("six different attempts are six purchases; uk_activity_user does not apply")
        .isEqualTo(6);
    assertThat(results).allSatisfy(result -> assertThat(statusOf(result)).isEqualTo(201));

    List<PurchaseOrder> orders = storedOrdersOfUser(buyer.id());
    assertThat(orders).hasSize(6);
    assertThat(orders).allSatisfy(order -> assertThat(order.getOrderType()).isEqualTo(OrderType.NORMAL));
    assertThat(orders.stream().map(PurchaseOrder::getOrderNo).distinct().count())
        .as("every order has its own number")
        .isEqualTo(6);
  }

  @Test
  void shouldWaitForAnExclusivePackageLockAndThenSeeTheWithdrawnState() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);

    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Connection locker = dataSource.getConnection()) {
      locker.setAutoCommit(false);
      try (PreparedStatement lock =
          locker.prepareStatement("SELECT id FROM token_package WHERE id = ? FOR UPDATE")) {
        lock.setLong(1, packageId);
        lock.executeQuery().close();
      }

      CountDownLatch started = new CountDownLatch(1);
      Future<MvcResult> pending =
          worker.submit(
              () -> {
                started.countDown();
                return placeNormalOrder(buyer.token(), packageId, newKey()).andReturn();
              });

      assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(300);
      assertThat(pending.isDone())
          .as("FOR SHARE must wait for the exclusive lock instead of reading a stale row")
          .isFalse();

      // Withdraw the package the way the admin endpoint does — lock held, status written (0 =
      // OFF_SHELF) — then release the lock.
      try (PreparedStatement update =
          locker.prepareStatement("UPDATE token_package SET status = 0 WHERE id = ?")) {
        update.setLong(1, packageId);
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      locker.commit();

      MvcResult result = pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(result.getResponse().getStatus())
          .as("the waiting request must decide on the state committed by the lock holder, not on its "
              + "own older snapshot")
          .isEqualTo(409);
      assertThat(errorCodeOf(result)).isEqualTo("PACKAGE_NOT_AVAILABLE");
    } finally {
      worker.shutdownNow();
    }

    assertThat(storedOrdersOfUser(buyer.id()))
        .as("an order must never be created from a stale on-sale decision")
        .isEmpty();
  }

  // --- helpers --------------------------------------------------------------------------------

  private List<MvcResult> runConcurrently(List<Callable<MvcResult>> calls) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<MvcResult>> futures = new ArrayList<>();
      for (Callable<MvcResult> call : calls) {
        futures.add(
            pool.submit(
                () -> {
                  start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                  return call.call();
                }));
      }
      start.countDown();

      List<MvcResult> results = new ArrayList<>();
      for (Future<MvcResult> future : futures) {
        results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private int statusOf(MvcResult result) {
    return result.getResponse().getStatus();
  }
}
