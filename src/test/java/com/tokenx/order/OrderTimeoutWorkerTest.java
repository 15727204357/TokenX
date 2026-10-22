package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.order.timeout.OrderTimeoutScheduler;
import com.tokenx.support.TestClockConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MvcResult;

/**
 * How the scan behaves as a job: bounded batches, real concurrency, and the two properties that make
 * several workers safe on one table.
 *
 * <p>The first is {@code FOR UPDATE SKIP LOCKED}: a worker must step over a row another transaction
 * already holds rather than queue behind it, which these tests prove by keeping a row locked from a
 * separate connection and requiring the scan to return promptly with the <em>other</em> order closed.
 * Checking the SQL text would prove nothing; only the waiting behaviour does.
 *
 * <p>The second is that a run is bounded. One trigger may attempt at most the configured number
 * of orders — a closure and a failed attempt each spend one of those slots — so a backlog is
 * worked down over several triggers instead of one transaction that locks a whole range.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderTimeoutWorkerTest extends AbstractOrderApiTest {

  private static final long TIMEOUT_SECONDS = 10;

  @Autowired private DataSource dataSource;

  @Autowired private ApplicationContext applicationContext;

  @Test
  void shouldCloseAtMostTheBatchSizeAndContinueOnTheNextRun() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(100L);
    List<String> orderNos = new ArrayList<>();
    for (int index = 0; index < 3; index++) {
      orderNos.add(placeOneNormalOrder(buyer, packageId));
    }
    TestClockConfig.setTo(expiresAtOf(orderNos.get(0), buyer.id()));

    assertThat(timeoutService.closeExpiredOrders(2))
        .as("one trigger stops at the batch size even though three orders are due")
        .isEqualTo(2);
    assertThat(closedCount(orderNos, buyer)).isEqualTo(2);

    assertThat(timeoutService.closeExpiredOrders(2))
        .as("the next trigger picks up the rest instead of losing it")
        .isEqualTo(1);
    assertThat(closedCount(orderNos, buyer)).isEqualTo(3);

    assertThat(timeoutService.closeExpiredOrders(2)).isZero();
    assertThat(closedCount(orderNos, buyer)).isEqualTo(3);
  }

  @Test
  void shouldSkipARowLockedByAnotherTransactionInsteadOfWaitingForIt() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(100L);
    String lockedOrderNo = placeOneNormalOrder(buyer, packageId);
    String freeOrderNo = placeOneNormalOrder(buyer, packageId);
    TestClockConfig.setTo(expiresAtOf(lockedOrderNo, buyer.id()));

    long lockedOrderId = storedOrder(lockedOrderNo, buyer.id()).getId();

    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Connection locker = dataSource.getConnection()) {
      locker.setAutoCommit(false);
      try (PreparedStatement lock =
          locker.prepareStatement("SELECT id FROM purchase_order WHERE id = ? FOR UPDATE")) {
        lock.setLong(1, lockedOrderId);
        lock.executeQuery().close();
      }

      Future<Integer> scan = worker.submit(() -> timeoutService.closeExpiredOrders(10));
      int closed = scan.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      assertThat(closed)
          .as("the locked order is skipped, and the free one is closed in the same run")
          .isEqualTo(1);
      assertThat(storedOrder(lockedOrderNo, buyer.id()).getStatus())
          .as("a row another transaction holds is left untouched")
          .isEqualTo(OrderStatus.UNPAID);
      assertThat(storedOrder(freeOrderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.CLOSED);

      locker.commit();
    } finally {
      worker.shutdownNow();
    }

    assertThat(timeoutService.closeExpiredOrders(10))
        .as("once the lock is gone the skipped order is closed by the next run")
        .isEqualTo(1);
    assertThat(storedOrder(lockedOrderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.CLOSED);
  }

  @Test
  void shouldCloseEveryExpiredOrderExactlyOnceWithSeveralWorkers() throws Exception {
    int buyers = 4;
    String adminToken = adminToken();
    long activityId = openActivity(adminToken, 100L, buyers);

    List<Buyer> buyersWithOrders = new ArrayList<>();
    for (int index = 0; index < buyers; index++) {
      Buyer buyer = registerBuyer();
      placeOneSeckillOrder(buyer, activityId);
      buyersWithOrders.add(buyer);
    }

    ActivityInventory reserved = storedInventory(activityId);
    assertThat(reserved.getAvailableStock()).isZero();
    assertThat(reserved.getReservedStock()).isEqualTo(buyers);

    Instant expiresAt = storedOrders(activityId).get(0).getExpiresInstant();
    TestClockConfig.setTo(expiresAt);

    int workers = 3;
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    CountDownLatch startLine = new CountDownLatch(1);
    List<Future<Integer>> running = new ArrayList<>();
    try {
      for (int index = 0; index < workers; index++) {
        running.add(
            pool.submit(
                () -> {
                  startLine.await();
                  return timeoutService.closeExpiredOrders(10);
                }));
      }
      startLine.countDown();

      int closedInTotal = 0;
      for (Future<Integer> scan : running) {
        closedInTotal += scan.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }

      assertThat(closedInTotal)
          .as("each order is closed once, so the workers' totals add up to the backlog")
          .isEqualTo(buyers);
    } finally {
      pool.shutdownNow();
    }

    assertThat(
            storedOrders(activityId).stream()
                .filter(order -> order.getStatus() == OrderStatus.CLOSED)
                .count())
        .isEqualTo(buyers);

    ActivityInventory released = storedInventory(activityId);
    assertThat(released.getAvailableStock())
        .as("stock is released once per order — a double release would show up here")
        .isEqualTo(buyers);
    assertThat(released.getReservedStock()).isZero();
  }

  @Test
  void shouldKeepAutomaticSchedulingOffInTests() {
    assertThat(applicationContext.getBeanNamesForType(OrderTimeoutScheduler.class))
        .as("a background thread would race the tests that drive the scan themselves")
        .isEmpty();
  }

  @Test
  void shouldFinishImmediatelyWhenNothingHasExpired() throws Exception {
    Buyer buyer = registerBuyer();
    String orderNo = placeOneNormalOrder(buyer, packageRow(100L));

    long startedAt = System.nanoTime();
    assertThat(timeoutService.closeExpiredOrders(10)).isZero();
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    assertThat(elapsed)
        .as("an empty scan returns instead of waiting for something to expire")
        .isLessThan(Duration.ofSeconds(5));
    assertThat(storedOrder(orderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.UNPAID);
  }

  // --- fixtures -------------------------------------------------------------------------------

  private long closedCount(List<String> orderNos, Buyer buyer) {
    return orderNos.stream()
        .filter(orderNo -> storedOrder(orderNo, buyer.id()).getStatus() == OrderStatus.CLOSED)
        .count();
  }

  private String placeOneSeckillOrder(Buyer buyer, long activityId) throws Exception {
    MvcResult created =
        placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isCreated()).andReturn();
    return orderNoOf(created);
  }

  private String placeOneNormalOrder(Buyer buyer, long packageId) throws Exception {
    MvcResult created =
        placeNormalOrder(buyer.token(), packageId, newKey())
            .andExpect(status().isCreated())
            .andReturn();
    return orderNoOf(created);
  }
}
