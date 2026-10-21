package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.support.TestClockConfig;
import java.time.Instant;
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
 * Payment and closure racing for the same order.
 *
 * <p>The two paths are deliberately not symmetric in time: payment requires {@code expires_at > now}
 * and closure requires {@code expires_at <= now}. At any instant exactly one of them is eligible, so
 * the pair cannot produce two outcomes — that is the design, and these tests are the evidence.
 *
 * <p>The race is made repeatable by moving the clock instead of relying on luck: both a payment and a
 * scan are started together from a latch at a chosen instant, one millisecond before the deadline and
 * then exactly on it. Whichever side is eligible wins, the other side reports a business answer
 * rather than an error, and the wallet and stock are then checked against the winning state.
 *
 * <p>What must never appear is a mixed state: paid with released stock, closed with a credit, or a
 * reservation both settled and returned.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderTimeoutRaceTest extends AbstractOrderApiTest {

  private static final long TIMEOUT_SECONDS = 10;

  @Test
  void shouldLetPaymentWinOneMillisecondBeforeTheDeadline() throws Exception {
    Buyer buyer = registerBuyer();
    long activityId = openActivity(adminToken(), 1000L, 5L);
    String orderNo = placeOneSeckillOrder(buyer, activityId);
    Instant expiresAt = expiresAtOf(orderNo, buyer.id());

    TestClockConfig.setTo(expiresAt.minusMillis(1));
    RaceOutcome outcome = race(buyer, orderNo);

    assertThat(outcome.paymentStatus())
        .as("a payment inside the window must not be refused because a scan is running")
        .isEqualTo(200);
    assertThat(outcome.closedOrders())
        .as("the scan finds nothing: the order is not expired yet")
        .isZero();

    PurchaseOrder order = storedOrder(orderNo, buyer.id());
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(order.getPaidAt()).isNotNull();
    assertThat(order.getClosedAt()).as("a paid order is never closed").isNull();

    assertThat(storedBalance(buyer.id())).as("exactly one credit").isEqualTo(1000L);
    assertThat(storedLedgerOf(buyer.id())).hasSize(1);

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getAvailableStock())
        .as("a paid unit is sold, not returned to the shelf")
        .isEqualTo(4);
    assertThat(inventory.getReservedStock()).isZero();
  }

  @Test
  void shouldLetTheClosureWinOnTheDeadlineAndRefuseThePayment() throws Exception {
    Buyer buyer = registerBuyer();
    long activityId = openActivity(adminToken(), 1000L, 5L);
    String orderNo = placeOneSeckillOrder(buyer, activityId);
    Instant expiresAt = expiresAtOf(orderNo, buyer.id());

    TestClockConfig.setTo(expiresAt);
    RaceOutcome outcome = race(buyer, orderNo);

    assertThat(outcome.closedOrders()).isEqualTo(1);
    assertThat(outcome.paymentStatus())
        .as("the deadline has passed, so the payment is a conflict rather than a second success")
        .isEqualTo(409);

    PurchaseOrder order = storedOrder(orderNo, buyer.id());
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CLOSED);
    assertThat(order.getClosedAt()).isEqualTo(PurchaseOrder.toColumn(expiresAt));
    assertThat(order.getPaidAt()).isNull();

    assertThat(storedBalance(buyer.id())).as("a closed order credits nobody").isZero();
    assertThat(storedLedgerOf(buyer.id())).isEmpty();

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getAvailableStock()).isEqualTo(5);
    assertThat(inventory.getReservedStock())
        .as("the reservation was returned once, not settled and returned")
        .isZero();
  }

  // --- fixtures -------------------------------------------------------------------------------

  /** One payment and one scan, released together, each reporting what it actually did. */
  private RaceOutcome race(Buyer buyer, String orderNo) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch startLine = new CountDownLatch(1);
    try {
      Future<Integer> closing =
          pool.submit(
              () -> {
                startLine.await();
                return timeoutService.closeExpiredOrders(10);
              });
      Future<MvcResult> paying =
          pool.submit(
              () -> {
                startLine.await();
                return mockPay(buyer.token(), orderNo).andReturn();
              });

      startLine.countDown();
      int closedOrders = closing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      int paymentStatus = paying.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getResponse().getStatus();
      return new RaceOutcome(closedOrders, paymentStatus);
    } finally {
      pool.shutdownNow();
    }
  }

  private record RaceOutcome(int closedOrders, int paymentStatus) {}

  private String placeOneSeckillOrder(Buyer buyer, long activityId) throws Exception {
    MvcResult created =
        placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isCreated()).andReturn();
    return orderNoOf(created);
  }
}
