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
 * <p>The harder case is when <em>both</em> are eligible: the payment reads one millisecond inside the
 * window while the scan reads the deadline itself, so neither predicate refuses the other by
 * arithmetic and only the row lock decides. That is covered by one genuine race plus two
 * deterministic orderings, one for each possible winner.
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

    assertPaidOrder(buyer, activityId, storedOrder(orderNo, buyer.id()));
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

    assertClosedOrder(buyer, activityId, storedOrder(orderNo, buyer.id()), expiresAt, 1);
  }

  /**
   * The harder race: the two sides read <em>different</em> instants, and both are eligible.
   *
   * <p>The payment thread is pinned one millisecond inside the window, so its gate
   * ({@code expires_at > now}) accepts the order. The scan thread is pinned on the deadline, so its
   * gate ({@code expires_at <= now}) accepts it too. Neither predicate can refuse the other side by
   * arithmetic, which leaves exactly one arbiter: the order row lock both statements take first.
   *
   * <p>Either winner is legal, and the test accepts either — but it insists the loser left no trace
   * behind. That is the point of the pair of predicates: a mixed state (paid and returned, or closed
   * and credited) is not reachable, no matter which thread is scheduled first.
   */
  @Test
  void shouldKeepOneLegalOutcomeWhenTheSidesReadOppositeSidesOfTheDeadline() throws Exception {
    Buyer buyer = registerBuyer();
    long activityId = openActivity(adminToken(), 1000L, 5L);
    String orderNo = placeOneSeckillOrder(buyer, activityId);
    Instant expiresAt = expiresAtOf(orderNo, buyer.id());

    RaceOutcome outcome = raceAcrossTheDeadline(buyer, orderNo, expiresAt);

    assertThat(outcome.paymentStatus())
        .as("the payment either pays or is refused — never an error")
        .isIn(200, 409);
    assertThat(outcome.closedOrders())
        .as("one order, so at most one closure")
        .isBetween(0, 1);

    PurchaseOrder order = storedOrder(orderNo, buyer.id());
    if (outcome.paymentStatus() == 200) {
      assertPaidOrder(buyer, activityId, order);
      assertThat(outcome.closedOrders())
          .as("the scan cannot close what the payment already settled")
          .isZero();
    } else {
      assertClosedOrder(buyer, activityId, order, expiresAt, 1);
    }
  }

  /**
   * The crossing in the order that needs no luck: the closure commits first, then a payment whose own
   * clock still says "inside the window" arrives.
   *
   * <p>Its arithmetic would allow the payment, so a {@code 409} here can only come from the row's
   * status. This is the deterministic half of the crossing — the concurrent test above proves the
   * race cannot go wrong, this one proves the refusal is a decision about the row and not a decision
   * about the clock.
   */
  @Test
  void shouldRefuseAPaymentWhoseClockIsStillInsideTheWindowOnceTheOrderIsClosed() throws Exception {
    Buyer buyer = registerBuyer();
    long activityId = openActivity(adminToken(), 1000L, 5L);
    String orderNo = placeOneSeckillOrder(buyer, activityId);
    Instant expiresAt = expiresAtOf(orderNo, buyer.id());

    TestClockConfig.setTo(expiresAt);
    assertThat(timeoutService.closeExpiredOrders(10)).isEqualTo(1);

    TestClockConfig.setTo(expiresAt.minusMillis(1));
    MvcResult refused = mockPay(buyer.token(), orderNo).andReturn();

    assertThat(refused.getResponse().getStatus()).isEqualTo(409);
    assertThat(errorCodeOf(refused)).isEqualTo("ORDER_STATE_CONFLICT");
    assertClosedOrder(buyer, activityId, storedOrder(orderNo, buyer.id()), expiresAt, 1);
  }

  /**
   * The same crossing the other way round: the payment commits while its clock says "inside the
   * window", then a scan arrives whose clock says "the deadline has passed".
   *
   * <p>The scan's {@code expires_at <= now} matches the order, so only its {@code status = 0}
   * predicate keeps it away. The order stays paid, the credit stays posted once, and the stock stays
   * sold — a scan is not allowed to un-sell what a payment already bought.
   */
  @Test
  void shouldLeaveAPaidOrderAloneWhenAScanReadsTheDeadlineAfterwards() throws Exception {
    Buyer buyer = registerBuyer();
    long activityId = openActivity(adminToken(), 1000L, 5L);
    String orderNo = placeOneSeckillOrder(buyer, activityId);
    Instant expiresAt = expiresAtOf(orderNo, buyer.id());

    TestClockConfig.setTo(expiresAt.minusMillis(1));
    MvcResult paid = mockPay(buyer.token(), orderNo).andReturn();
    assertThat(paid.getResponse().getStatus()).isEqualTo(200);

    TestClockConfig.setTo(expiresAt);
    assertThat(timeoutService.closeExpiredOrders(10))
        .as("a paid order is not part of the backlog, whatever the clock says")
        .isZero();

    assertPaidOrder(buyer, activityId, storedOrder(orderNo, buyer.id()));
  }

  // --- invariants -----------------------------------------------------------------------------

  /** Everything the winning payment must have produced, and nothing else. */
  private void assertPaidOrder(Buyer buyer, long activityId, PurchaseOrder order) {
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(order.getPaidAt()).isNotNull();
    assertThat(order.getClosedAt()).as("a paid order is never closed").isNull();

    assertThat(storedBalance(buyer.id())).as("exactly one credit").isEqualTo(1000L);
    assertThat(storedLedgerOf(buyer.id())).hasSize(1);

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getAvailableStock())
        .as("a paid unit is sold, not returned to the shelf")
        .isEqualTo(4);
    assertThat(inventory.getReservedStock()).as("the reservation was settled once").isZero();
  }

  /** Everything the winning closure must have produced, and nothing else. */
  private void assertClosedOrder(
      Buyer buyer, long activityId, PurchaseOrder order, Instant closedAt, int closedOrders) {
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CLOSED);
    assertThat(order.getClosedAt()).isEqualTo(PurchaseOrder.toColumn(closedAt));
    assertThat(order.getPaidAt()).as("a closed order credits nobody").isNull();
    assertThat(closedOrders).as("exactly one closure").isEqualTo(1);

    assertThat(storedBalance(buyer.id())).as("a closed order credits nobody").isZero();
    assertThat(storedLedgerOf(buyer.id())).isEmpty();

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getAvailableStock()).as("the unit went back to the shelf").isEqualTo(5);
    assertThat(inventory.getReservedStock())
        .as("the reservation was returned once, not settled and returned")
        .isZero();
  }

  // --- fixtures -------------------------------------------------------------------------------

  /** One payment and one scan, released together, each reporting what it actually did. */
  private RaceOutcome race(Buyer buyer, String orderNo) throws Exception {
    return runTogether(buyer, orderNo, null, null);
  }

  /**
   * The same pair, each thread pinned to its own "now".
   *
   * <p>The pin is set before the start line, so it covers the whole business path on that thread —
   * the request, the transaction, the statements — exactly as a single reading of the clock would.
   */
  private RaceOutcome raceAcrossTheDeadline(Buyer buyer, String orderNo, Instant expiresAt)
      throws Exception {
    return runTogether(buyer, orderNo, expiresAt, expiresAt.minusMillis(1));
  }

  /**
   * @param closingNow what the scanning thread reads, {@code null} to use the shared instant
   * @param payingNow what the paying thread reads, {@code null} to use the shared instant
   */
  private RaceOutcome runTogether(Buyer buyer, String orderNo, Instant closingNow, Instant payingNow)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch startLine = new CountDownLatch(1);
    try {
      Future<Integer> closing =
          pool.submit(
              () -> {
                if (closingNow != null) {
                  TestClockConfig.pinCurrentThread(closingNow);
                }
                try {
                  startLine.await();
                  return timeoutService.closeExpiredOrders(10);
                } finally {
                  TestClockConfig.unpinCurrentThread();
                }
              });
      Future<MvcResult> paying =
          pool.submit(
              () -> {
                if (payingNow != null) {
                  TestClockConfig.pinCurrentThread(payingNow);
                }
                try {
                  startLine.await();
                  return mockPay(buyer.token(), orderNo).andReturn();
                } finally {
                  TestClockConfig.unpinCurrentThread();
                }
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
