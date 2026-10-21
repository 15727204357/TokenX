package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.support.TestClockConfig;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * What the timeout scan does to one order, against real MySQL.
 *
 * <p>Every case here drives the clock instead of waiting: the scan compares {@code expires_at} with
 * the instant the server read, so an order can be made expired by moving that instant past the
 * deadline it stored. Nothing sleeps for fifteen minutes and nothing trusts the test's wall clock.
 *
 * <p>The two channels differ in exactly one respect and these tests pin it down: a closed normal
 * order changes nothing but itself, while a closed seckill order must give its reserved unit back in
 * the same transaction. A closed order is never deleted and its {@code paid_at} is never filled in,
 * because closing is the opposite outcome of paying.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderTimeoutClosureTest extends AbstractOrderApiTest {

  @Test
  void shouldCloseAnExpiredNormalOrderWithoutTouchingStockOrWallet() throws Exception {
    Buyer buyer = registerBuyer();
    long bystanderActivity = openActivity(adminToken(), 100L, 5L);
    String orderNo = placeOneNormalOrder(buyer, packageRow(500L));

    ActivityInventory before = storedInventory(bystanderActivity);
    Instant expiresAt = expiresAtOf(orderNo, buyer.id());
    TestClockConfig.setTo(expiresAt);

    assertThat(timeoutService.closeExpiredOrders(10)).isEqualTo(1);

    PurchaseOrder closed = storedOrder(orderNo, buyer.id());
    assertThat(closed.getStatus()).isEqualTo(OrderStatus.CLOSED);
    assertThat(closed.getClosedAt())
        .as("closed_at is the scan's single clock reading, not a second one")
        .isEqualTo(PurchaseOrder.toColumn(expiresAt));
    assertThat(closed.getUpdatedAt()).isEqualTo(PurchaseOrder.toColumn(expiresAt));
    assertThat(closed.getPaidAt()).as("a closed order was never paid").isNull();

    assertThat(storedBalance(buyer.id())).as("closing pays nothing").isZero();
    assertThat(storedLedgerOf(buyer.id())).as("closing posts no ledger entry").isEmpty();

    ActivityInventory after = storedInventory(bystanderActivity);
    assertThat(after.getAvailableStock()).isEqualTo(before.getAvailableStock());
    assertThat(after.getReservedStock()).isEqualTo(before.getReservedStock());
  }

  @Test
  void shouldCloseAnExpiredSeckillOrderAndGiveTheReservedUnitBack() throws Exception {
    Buyer buyer = registerBuyer();
    long activityId = openActivity(adminToken(), 100L, 5L);
    String orderNo = placeOneSeckillOrder(buyer, activityId);

    ActivityInventory reserved = storedInventory(activityId);
    assertThat(reserved.getAvailableStock()).isEqualTo(4);
    assertThat(reserved.getReservedStock()).isEqualTo(1);

    TestClockConfig.setTo(expiresAtOf(orderNo, buyer.id()));
    assertThat(timeoutService.closeExpiredOrders(10)).isEqualTo(1);

    assertThat(storedOrder(orderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.CLOSED);

    ActivityInventory released = storedInventory(activityId);
    assertThat(released.getAvailableStock())
        .as("the unit becomes purchasable again")
        .isEqualTo(5);
    assertThat(released.getReservedStock()).isZero();
    assertThat(released.getAvailableStock() + released.getReservedStock())
        .as("total stock is still the sum of what is available and what is reserved")
        .isEqualTo(released.getTotalStock());
  }

  @Test
  void shouldNotCloseOneMillisecondBeforeTheDeadlineButCloseOnIt() throws Exception {
    Buyer buyer = registerBuyer();
    long activityId = openActivity(adminToken(), 100L, 5L);
    String orderNo = placeOneSeckillOrder(buyer, activityId);
    Instant expiresAt = expiresAtOf(orderNo, buyer.id());

    TestClockConfig.setTo(expiresAt.minusMillis(1));
    assertThat(timeoutService.closeExpiredOrders(10))
        .as("one millisecond before the deadline the order is still payable")
        .isZero();
    assertThat(storedOrder(orderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.UNPAID);
    assertThat(storedInventory(activityId).getReservedStock()).isEqualTo(1);

    TestClockConfig.setTo(expiresAt);
    assertThat(timeoutService.closeExpiredOrders(10))
        .as("expires_at itself is already too late to pay")
        .isEqualTo(1);
    assertThat(storedOrder(orderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.CLOSED);
    assertThat(storedInventory(activityId).getAvailableStock()).isEqualTo(5);
  }

  @Test
  void shouldLeaveAnUnexpiredOrderAndItsReservationAlone() throws Exception {
    Buyer buyer = registerBuyer();
    long activityId = openActivity(adminToken(), 100L, 5L);
    String orderNo = placeOneSeckillOrder(buyer, activityId);

    assertThat(timeoutService.closeExpiredOrders(10))
        .as("the clock is still inside the payment window")
        .isZero();

    PurchaseOrder untouched = storedOrder(orderNo, buyer.id());
    assertThat(untouched.getStatus()).isEqualTo(OrderStatus.UNPAID);
    assertThat(untouched.getClosedAt()).isNull();

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getAvailableStock()).isEqualTo(4);
    assertThat(inventory.getReservedStock()).isEqualTo(1);
  }

  @Test
  void shouldNeverCloseAnOrderThatWasAlreadyPaid() throws Exception {
    Buyer buyer = registerBuyer();
    long activityId = openActivity(adminToken(), 1000L, 5L);
    String orderNo = placeOneSeckillOrder(buyer, activityId);

    mockPay(buyer.token(), orderNo).andExpect(status().isOk());

    long balanceAfterPayment = storedBalance(buyer.id());
    int ledgerAfterPayment = storedLedgerOf(buyer.id()).size();
    assertThat(ledgerAfterPayment).isEqualTo(1);

    TestClockConfig.setTo(expiresAtOf(orderNo, buyer.id()).plusSeconds(60));
    assertThat(timeoutService.closeExpiredOrders(10))
        .as("a paid order is not a timeout candidate, even long after the deadline")
        .isZero();

    PurchaseOrder paid = storedOrder(orderNo, buyer.id());
    assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(paid.getPaidAt()).isNotNull();
    assertThat(paid.getClosedAt()).isNull();
    assertThat(storedBalance(buyer.id())).isEqualTo(balanceAfterPayment);
    assertThat(storedLedgerOf(buyer.id())).hasSize(ledgerAfterPayment);

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getAvailableStock()).isEqualTo(4);
    assertThat(inventory.getReservedStock())
        .as("payment settled the reservation, so there is none left to release")
        .isZero();
  }

  @Test
  void shouldReleaseTheReservationOnlyOnceWhenTheScanRunsRepeatedly() throws Exception {
    Buyer buyer = registerBuyer();
    long activityId = openActivity(adminToken(), 100L, 5L);
    String orderNo = placeOneSeckillOrder(buyer, activityId);

    TestClockConfig.setTo(expiresAtOf(orderNo, buyer.id()));

    assertThat(timeoutService.closeExpiredOrders(10)).isEqualTo(1);
    PurchaseOrder firstRun = storedOrder(orderNo, buyer.id());

    assertThat(timeoutService.closeExpiredOrders(10)).isZero();
    assertThat(timeoutService.closeExpiredOrders(10)).isZero();

    PurchaseOrder afterRepeats = storedOrder(orderNo, buyer.id());
    assertThat(afterRepeats.getStatus()).isEqualTo(OrderStatus.CLOSED);
    assertThat(afterRepeats.getClosedAt())
        .as("a repeated scan cannot restamp an order that is already closed")
        .isEqualTo(firstRun.getClosedAt());

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getAvailableStock())
        .as("the unit is returned exactly once, not once per scan")
        .isEqualTo(5);
    assertThat(inventory.getReservedStock()).isZero();
  }

  @Test
  void shouldCloseBothChannelsInOneRunAndTouchOnlySeckillStock() throws Exception {
    Buyer seckillBuyer = registerBuyer();
    Buyer normalBuyer = registerBuyer();
    long activityId = openActivity(adminToken(), 100L, 3L);

    String seckillOrderNo = placeOneSeckillOrder(seckillBuyer, activityId);
    String normalOrderNo = placeOneNormalOrder(normalBuyer, packageRow(400L));

    assertThat(storedInventory(activityId).getAvailableStock()).isEqualTo(2);
    assertThat(storedInventory(activityId).getReservedStock()).isEqualTo(1);

    TestClockConfig.setTo(expiresAtOf(seckillOrderNo, seckillBuyer.id()));
    assertThat(timeoutService.closeExpiredOrders(10)).isEqualTo(2);

    assertThat(storedOrder(seckillOrderNo, seckillBuyer.id()).getStatus())
        .isEqualTo(OrderStatus.CLOSED);
    assertThat(storedOrder(normalOrderNo, normalBuyer.id()).getStatus())
        .isEqualTo(OrderStatus.CLOSED);

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getAvailableStock()).isEqualTo(3);
    assertThat(inventory.getReservedStock()).isZero();
  }

  // --- fixtures -------------------------------------------------------------------------------

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
