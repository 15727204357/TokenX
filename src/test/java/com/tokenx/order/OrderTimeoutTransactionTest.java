package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where each closure's transaction actually begins and ends.
 *
 * <p>"One order, one transaction" is only true if the boundary is {@code REQUIRES_NEW}. With the
 * default {@code REQUIRED} a batch called from inside somebody else's transaction would join it
 * silently: the caller's rollback would undo closures that had already been reported as done, and
 * the caller would hold every order lock for its own lifetime.
 *
 * <p>The first test is the direct proof. It opens a transaction of its own, runs the whole batch
 * inside it, and rolls that transaction back. Nothing about the batch is asserted afterwards through
 * the same transaction — the assertions run after it has ended, against the committed state.
 *
 * <p>No test here relies on Spring's method-level test transactions: there are none, and the outer
 * transaction is created explicitly so that its rollback is unambiguous.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderTimeoutTransactionTest extends AbstractOrderApiTest {

  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void shouldCommitEveryClosureEvenWhenTheCallersTransactionRollsBack() throws Exception {
    Buyer firstBuyer = registerBuyer();
    Buyer secondBuyer = registerBuyer();
    long activityId = openActivity(adminToken(), 100L, 3L);
    String firstOrderNo = placeOneSeckillOrder(firstBuyer, activityId);
    String secondOrderNo = placeOneSeckillOrder(secondBuyer, activityId);
    TestClockConfig.setTo(expiresAtOf(firstOrderNo, firstBuyer.id()));

    TransactionTemplate caller = new TransactionTemplate(transactionManager);
    caller.executeWithoutResult(
        status -> {
          assertThat(timeoutService.closeExpiredOrders(10))
              .as("both expired orders are closed inside the caller's transaction")
              .isEqualTo(2);
          status.setRollbackOnly();
        });

    // The caller rolled back. Under PROPAGATION_REQUIRED the closures would have gone with it and
    // both orders would be UNPAID again; each order's own transaction must have committed already.
    assertThat(storedOrder(firstOrderNo, firstBuyer.id()).getStatus())
        .as("a closure committed in its own transaction survives the caller's rollback")
        .isEqualTo(OrderStatus.CLOSED);
    assertThat(storedOrder(secondOrderNo, secondBuyer.id()).getStatus())
        .isEqualTo(OrderStatus.CLOSED);

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getAvailableStock())
        .as("the released stock survives the caller's rollback too")
        .isEqualTo(3);
    assertThat(inventory.getReservedStock()).isZero();
  }

  @Test
  void shouldKeepAnEarlierClosureCommittedWhenALaterOrderFails() throws Exception {
    Buyer healthyBuyer = registerBuyer();
    Buyer brokenBuyer = registerBuyer();

    // The healthy order is created first, so the scan reaches it first and the failure comes later.
    long healthyActivity = openActivity(adminToken(), 100L, 2L);
    String healthyOrderNo = placeOneSeckillOrder(healthyBuyer, healthyActivity);
    long brokenActivity = openActivity(adminToken(), 100L, 2L);
    String brokenOrderNo = placeOneSeckillOrder(brokenBuyer, brokenActivity);

    drainReservations(brokenActivity);
    TestClockConfig.setTo(expiresAtOf(healthyOrderNo, healthyBuyer.id()));

    assertThat(timeoutService.closeExpiredOrders(10)).isEqualTo(1);

    assertThat(storedOrder(healthyOrderNo, healthyBuyer.id()).getStatus())
        .as("a later failure cannot undo a closure that already committed")
        .isEqualTo(OrderStatus.CLOSED);
    assertThat(storedInventory(healthyActivity).getAvailableStock()).isEqualTo(2);

    assertThat(storedOrder(brokenOrderNo, brokenBuyer.id()).getStatus())
        .isEqualTo(OrderStatus.UNPAID);
    assertThat(storedInventory(brokenActivity).getAvailableStock())
        .as("the failed order released nothing")
        .isEqualTo(1);
  }

  // --- fixtures -------------------------------------------------------------------------------

  private String placeOneSeckillOrder(Buyer buyer, long activityId) throws Exception {
    MvcResult created =
        placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isCreated()).andReturn();
    return orderNoOf(created);
  }
}
