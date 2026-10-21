package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Oversell protection against real MySQL, with real concurrent requests.
 *
 * <p>These are correctness tests, not benchmarks: nothing here measures throughput, and no number
 * produced here may be quoted as a QPS/TPS figure. What they establish is the only thing that
 * matters for a strong-consistency baseline — the database cannot be made to sell one unit twice.
 *
 * <p>The mechanism under test is the conditional {@code UPDATE ... WHERE available_stock > 0}
 * followed by the affected-row check. Each buyer takes the stock row's exclusive lock for the short
 * window between the update and the commit, so buyers of the same activity serialise on exactly one
 * row, while buyers of different activities never touch the same row.
 *
 * <p>Every wait is bounded, so a lock that is never released fails the test instead of hanging it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SeckillOversellConcurrencyTest extends AbstractOrderApiTest {

  private static final long TIMEOUT_SECONDS = 20;

  @Test
  void shouldNeverSellMoreUnitsThanTheStock() throws Exception {
    String admin = adminToken();
    long activityId = openActivity(admin, 799L, 3L);

    List<Buyer> buyers = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      buyers.add(registerBuyer());
    }

    List<Callable<MvcResult>> calls = new ArrayList<>();
    for (Buyer buyer : buyers) {
      String key = newKey();
      calls.add(() -> placeOrder(buyer.token(), activityId, key).andReturn());
    }

    List<MvcResult> results = runConcurrently(calls);

    long created = results.stream().filter(result -> statusOf(result) == 201).count();
    long refused = results.stream().filter(result -> statusOf(result) == 409).count();

    assertThat(created).as("exactly the available stock may be sold").isEqualTo(3);
    assertThat(refused).as("everyone else is refused").isEqualTo(5);

    for (MvcResult result : results) {
      assertThat(statusOf(result))
          .as("only 201 and a stable business conflict may appear — never a 500")
          .isIn(201, 409);
      if (statusOf(result) == 409) {
        assertThat(errorCodeOf(result)).isEqualTo("ACTIVITY_SOLD_OUT");
      }
    }

    ActivityInventory stock = storedInventory(activityId);
    assertThat(stock.getAvailableStock()).isZero();
    assertThat(stock.getReservedStock()).isEqualTo(3);
    assertThat(stock.getAvailableStock() + stock.getReservedStock())
        .as("nothing may appear or vanish")
        .isEqualTo(stock.getTotalStock());
    assertThat(storedOrders(activityId)).as("no order beyond the stock").hasSize(3);
  }

  @Test
  void shouldConsumeTwoActivitiesIndependently() throws Exception {
    String admin = adminToken();
    long first = openActivity(admin, 799L, 2L);
    long second = openActivity(admin, 899L, 2L);

    List<Buyer> buyers = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      buyers.add(registerBuyer());
    }

    // Every buyer tries both activities at the same time: the two stock rows must not share a lock.
    List<Callable<MvcResult>> calls = new ArrayList<>();
    for (Buyer buyer : buyers) {
      String firstKey = newKey();
      String secondKey = newKey();
      calls.add(() -> placeOrder(buyer.token(), first, firstKey).andReturn());
      calls.add(() -> placeOrder(buyer.token(), second, secondKey).andReturn());
    }

    List<MvcResult> results = runConcurrently(calls);

    assertThat(results.stream().filter(result -> statusOf(result) == 201).count())
        .as("two activities with two units each sell exactly four")
        .isEqualTo(4);
    assertThat(results.stream().anyMatch(result -> statusOf(result) >= 500))
        .as("parallel activities must not produce a server error")
        .isFalse();

    for (long activityId : List.of(first, second)) {
      ActivityInventory stock = storedInventory(activityId);
      assertThat(stock.getAvailableStock()).isZero();
      assertThat(stock.getReservedStock()).isEqualTo(2);
      assertThat(storedOrders(activityId)).hasSize(2);
    }
  }

  @Test
  void shouldLetOneWinnerThroughWhenTwoIdenticalRequestsRace() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);
    String key = newKey();

    List<Callable<MvcResult>> calls =
        List.of(
            () -> placeOrder(buyer.token(), activityId, key).andReturn(),
            () -> placeOrder(buyer.token(), activityId, key).andReturn());

    List<MvcResult> results = runConcurrently(calls);

    assertThat(results.stream().filter(result -> statusOf(result) == 201).count())
        .as("a duplicate key must roll back, so exactly one request creates the order")
        .isEqualTo(1);
    assertThat(results.stream().filter(result -> statusOf(result) == 200).count())
        .as("the other request replays the same order")
        .isEqualTo(1);
    assertThat(orderNoOf(results.get(0))).isEqualTo(orderNoOf(results.get(1)));

    ActivityInventory stock = storedInventory(activityId);
    assertThat(stock.getAvailableStock()).isEqualTo(4);
    assertThat(stock.getReservedStock()).isEqualTo(1);
    assertThat(storedOrders(activityId)).hasSize(1);
  }

  @Test
  void shouldAllowAtMostOneOrderWhenTwoDifferentKeysRace() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);

    List<Callable<MvcResult>> calls =
        List.of(
            () -> placeOrder(buyer.token(), activityId, newKey()).andReturn(),
            () -> placeOrder(buyer.token(), activityId, newKey()).andReturn());

    List<MvcResult> results = runConcurrently(calls);

    assertThat(results.stream().filter(result -> statusOf(result) == 201).count()).isEqualTo(1);
    assertThat(results.stream().filter(result -> statusOf(result) == 409).count()).isEqualTo(1);
    assertThat(results)
        .filteredOn(result -> statusOf(result) == 409)
        .allSatisfy(
            result ->
                assertThat(errorCodeOf(result)).isEqualTo("ALREADY_PURCHASED"));

    ActivityInventory stock = storedInventory(activityId);
    assertThat(stock.getAvailableStock())
        .as("the loser's reservation must have been rolled back")
        .isEqualTo(4);
    assertThat(stock.getReservedStock()).isEqualTo(1);
    assertThat(storedOrders(activityId)).hasSize(1);
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
