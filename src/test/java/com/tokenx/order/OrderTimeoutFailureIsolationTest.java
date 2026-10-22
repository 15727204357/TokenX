package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * What one broken order does to the rest of a run.
 *
 * <p>A row that cannot be closed stays {@code UNPAID}, so it keeps its place at the head of the scan
 * forever. Retrying it in the same loop would fail forever; stopping the run would starve every
 * healthy order behind it, and in a single-instance deployment that means the backlog never drains.
 *
 * <p>These tests pin the chosen middle ground: the run skips past the failed row <em>within this
 * run</em> and keeps going, while the next run starts from the beginning again — so the broken order
 * is retried every time rather than quietly forgotten. The budget counts claims, not successes, which
 * is what keeps "skip and continue" from becoming an unbounded loop.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderTimeoutFailureIsolationTest extends AbstractOrderApiTest {

  @Test
  void shouldSkipTheFailedOrderAndCloseTheHealthyOnesBehindIt() throws Exception {
    Buyer brokenBuyer = registerBuyer();
    Buyer normalBuyer = registerBuyer();
    Buyer healthyBuyer = registerBuyer();

    // Created in this order on purpose: same deadline, so (expires_at, id) puts the broken order
    // first and the two healthy ones behind it — exactly the shape that used to block everything.
    long brokenActivity = openActivity(adminToken(), 100L, 1L);
    String brokenOrderNo = placeOneSeckillOrder(brokenBuyer, brokenActivity);
    String normalOrderNo = placeOneNormalOrder(normalBuyer, packageRow(100L));
    long healthyActivity = openActivity(adminToken(), 100L, 2L);
    String healthyOrderNo = placeOneSeckillOrder(healthyBuyer, healthyActivity);

    drainReservations(brokenActivity);
    TestClockConfig.setTo(expiresAtOf(brokenOrderNo, brokenBuyer.id()));

    // A budget of two: the failed claim consumes one, the next healthy order consumes the other.
    assertThat(timeoutService.closeExpiredOrders(2)).isEqualTo(1);

    assertThat(storedOrder(brokenOrderNo, brokenBuyer.id()).getStatus())
        .as("the broken order keeps its own rollback; it is neither closed nor hidden")
        .isEqualTo(OrderStatus.UNPAID);
    assertThat(storedOrder(brokenOrderNo, brokenBuyer.id()).getClosedAt()).isNull();

    assertThat(storedOrder(normalOrderNo, normalBuyer.id()).getStatus())
        .as("the run continues past the failure")
        .isEqualTo(OrderStatus.CLOSED);
    assertThat(storedOrder(healthyOrderNo, healthyBuyer.id()).getStatus())
        .as("the budget was spent on the failure and one success")
        .isEqualTo(OrderStatus.UNPAID);

    ActivityInventory brokenInventory = storedInventory(brokenActivity);
    assertThat(brokenInventory.getAvailableStock()).isZero();
    assertThat(brokenInventory.getReservedStock()).isZero();
  }

  @Test
  void shouldRetryTheFailedOrderOnTheNextRunInsteadOfBlacklistingIt() throws Exception {
    Buyer brokenBuyer = registerBuyer();
    Buyer healthyBuyer = registerBuyer();

    long brokenActivity = openActivity(adminToken(), 100L, 1L);
    String brokenOrderNo = placeOneSeckillOrder(brokenBuyer, brokenActivity);
    long healthyActivity = openActivity(adminToken(), 100L, 2L);
    String healthyOrderNo = placeOneSeckillOrder(healthyBuyer, healthyActivity);

    drainReservations(brokenActivity);
    TestClockConfig.setTo(expiresAtOf(brokenOrderNo, brokenBuyer.id()));

    // A budget of one is the observable difference: if the broken order were blacklisted, this run
    // would close the healthy order; it returns zero, so the broken order was attempted first again.
    assertThat(timeoutService.closeExpiredOrders(1)).isZero();
    assertThat(storedOrder(healthyOrderNo, healthyBuyer.id()).getStatus())
        .isEqualTo(OrderStatus.UNPAID);

    // The next run with room for both still fails on the broken order and closes the healthy one.
    assertThat(timeoutService.closeExpiredOrders(5)).isEqualTo(1);
    assertThat(storedOrder(brokenOrderNo, brokenBuyer.id()).getStatus())
        .isEqualTo(OrderStatus.UNPAID);
    assertThat(storedOrder(healthyOrderNo, healthyBuyer.id()).getStatus())
        .isEqualTo(OrderStatus.CLOSED);

    ActivityInventory released = storedInventory(healthyActivity);
    assertThat(released.getAvailableStock())
        .as("the reservation is released exactly once, by the run that managed to close it")
        .isEqualTo(2);
    assertThat(released.getReservedStock()).isZero();
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
