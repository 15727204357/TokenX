package com.tokenx.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.order.AbstractOrderApiTest;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.wallet.model.TokenLedger;
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
 * Concurrent payment, against real MySQL.
 *
 * <p>These are correctness tests, not benchmarks: nothing here measures throughput, and no number
 * produced here may be quoted as a QPS/TPS figure. What they prove is narrower and more useful —
 * that the conditional update on {@code status = 0} lets exactly one caller through, that the
 * others turn into replays instead of errors, and that two payments by one user cannot lose an
 * update on the account row.
 *
 * <p>Every wait is bounded, so a lock that is never released fails the test instead of hanging it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MockPaymentConcurrencyTest extends AbstractOrderApiTest {

  private static final long TIMEOUT_SECONDS = 20;

  private static final int SAME_ORDER_THREADS = 4;

  @Test
  void shouldCreditOnceWhenTheSameOrderIsPaidConcurrently() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 2L);
    String orderNo = orderNoOf(placeOrder(buyer.token(), activityId, newKey()).andReturn());

    List<Callable<MvcResult>> calls = new ArrayList<>();
    for (int index = 0; index < SAME_ORDER_THREADS; index++) {
      calls.add(() -> mockPay(buyer.token(), orderNo).andReturn());
    }

    List<MvcResult> results = runConcurrently(calls);

    assertThat(results)
        .allSatisfy(
            result ->
                assertThat(statusOf(result))
                    .as("a lost race is a replay, never a 500")
                    .isEqualTo(200));

    assertThat(storedBalance(buyer.id())).as("four attempts, one credit").isEqualTo(1000L);
    assertThat(storedLedgerOf(buyer.id())).hasSize(1);

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getReservedStock()).as("the reservation is settled exactly once").isZero();
    assertThat(inventory.getAvailableStock()).isEqualTo(1);

    PurchaseOrder stored = storedOrder(orderNo, buyer.id());
    assertThat(stored.getStatus()).isEqualTo(OrderStatus.PAID);
    // Every response must describe the same payment, not four different ones.
    assertThat(results)
        .allSatisfy(
            result ->
                assertThat(json(result).get("paidAt").asText())
                    .isEqualTo(stored.getPaidInstant().toString()));
  }

  @Test
  void shouldKeepBothCreditsWhenTwoOrdersOfOneUserArePaidConcurrently() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(600L, 400L, PackageStatus.ON_SALE);

    String firstOrder = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());
    String secondOrder = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());

    List<MvcResult> results =
        runConcurrently(
            List.of(
                () -> mockPay(buyer.token(), firstOrder).andReturn(),
                () -> mockPay(buyer.token(), secondOrder).andReturn()));

    assertThat(results).allSatisfy(result -> assertThat(statusOf(result)).isEqualTo(200));
    assertThat(storedBalance(buyer.id()))
        .as("two credits of 600 on one account row must both survive")
        .isEqualTo(1200L);

    List<TokenLedger> ledger = storedLedgerOf(buyer.id());
    assertThat(ledger).hasSize(2);
    assertThat(ledger.stream().map(TokenLedger::getBalanceAfter))
        .as("the two postings describe 600 then 1200, so the account can be replayed from them")
        .containsExactlyInAnyOrder(600L, 1200L);
    assertThat(ledger.stream().mapToLong(TokenLedger::getDelta).sum()).isEqualTo(1200L);
  }

  @Test
  void shouldSettleEveryReservationWhenTwoBuyersPayConcurrently() throws Exception {
    String admin = adminToken();
    Buyer first = registerBuyer();
    Buyer second = registerBuyer();
    long activityId = openActivity(admin, 799L, 2L);

    String firstOrder = orderNoOf(placeOrder(first.token(), activityId, newKey()).andReturn());
    String secondOrder = orderNoOf(placeOrder(second.token(), activityId, newKey()).andReturn());

    List<MvcResult> results =
        runConcurrently(
            List.of(
                () -> mockPay(first.token(), firstOrder).andReturn(),
                () -> mockPay(second.token(), secondOrder).andReturn()));

    assertThat(results).allSatisfy(result -> assertThat(statusOf(result)).isEqualTo(200));

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getReservedStock()).isZero();
    assertThat(inventory.getAvailableStock()).isZero();
    assertThat(inventory.getTotalStock()).isEqualTo(2);

    assertThat(storedBalance(first.id())).isEqualTo(1000L);
    assertThat(storedBalance(second.id())).isEqualTo(1000L);
    assertThat(storedLedgerOf(first.id())).hasSize(1);
    assertThat(storedLedgerOf(second.id())).hasSize(1);
    assertThat(storedLedgerOf(first.id()).get(0).getUserId()).isEqualTo(first.id());
    assertThat(storedLedgerOf(second.id()).get(0).getUserId()).isEqualTo(second.id());
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
