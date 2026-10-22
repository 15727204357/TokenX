package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * What happens when the data a closure depends on is already broken.
 *
 * <p>Closing a seckill order claims to release a reservation that the order, by existing, must hold.
 * If {@code reserved_stock} is zero there is no such reservation, and the affected-row count says so.
 * The only honest answer is to fail: the order must stay {@code UNPAID} and keep its reservation
 * semantics intact, rather than become {@code CLOSED} while the unit is neither reserved nor sold.
 *
 * <p>These tests damage the row on purpose, because the normal path cannot produce this state. They
 * then assert all four things that could be left half-done — status, {@code closed_at},
 * {@code updated_at} and both stock columns — are exactly as they were before the attempt.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderTimeoutRollbackTest extends AbstractOrderApiTest {

  @Test
  void shouldRollBackTheWholeClosureWhenNoReservationIsLeftToRelease() throws Exception {
    Buyer buyer = registerBuyer();
    long activityId = openActivity(adminToken(), 100L, 5L);
    String orderNo = placeOneSeckillOrder(buyer, activityId);

    PurchaseOrder beforeAttempt = storedOrder(orderNo, buyer.id());
    drainReservations(activityId);

    TestClockConfig.setTo(expiresAtOf(orderNo, buyer.id()));

    assertThatThrownBy(() -> timeoutService.closeNextExpiredOrder())
        .as("a closure that cannot release its reservation must fail, not pretend to succeed")
        .isInstanceOf(IllegalStateException.class);

    PurchaseOrder afterAttempt = storedOrder(orderNo, buyer.id());
    assertThat(afterAttempt.getStatus())
        .as("the status change and the stock release are one transaction")
        .isEqualTo(OrderStatus.UNPAID);
    assertThat(afterAttempt.getClosedAt()).isNull();
    assertThat(afterAttempt.getUpdatedAt()).isEqualTo(beforeAttempt.getUpdatedAt());
    assertThat(afterAttempt.getPaidAt()).isNull();

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getAvailableStock())
        .as("the unit was never added back, because the closure rolled back")
        .isEqualTo(4);
    assertThat(inventory.getReservedStock()).isZero();
  }

  @Test
  void shouldSkipTheBrokenOrderWithoutSpinningOnIt() throws Exception {
    Buyer buyer = registerBuyer();
    long activityId = openActivity(adminToken(), 100L, 5L);
    String orderNo = placeOneSeckillOrder(buyer, activityId);
    drainReservations(activityId);

    TestClockConfig.setTo(expiresAtOf(orderNo, buyer.id()));

    assertThat(timeoutService.closeExpiredOrders(50))
        .as("the failed order is skipped by the cursor, so the run ends instead of retrying it")
        .isZero();
    assertThat(storedOrder(orderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.UNPAID);
    assertThat(storedInventory(activityId).getAvailableStock()).isEqualTo(4);
  }

  // --- fixtures -------------------------------------------------------------------------------

  private String placeOneSeckillOrder(Buyer buyer, long activityId) throws Exception {
    MvcResult created =
        placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isCreated()).andReturn();
    return orderNoOf(created);
  }
}
