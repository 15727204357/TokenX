package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.order.mapper.PurchaseOrderMapper;
import com.tokenx.order.model.PurchaseOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The two things only a real database can prove: that a failed insert really undoes the reservation,
 * and that the shared read really waits for a writer.
 *
 * <p>Nothing here is verified against a mock's expectations. The spy exists only to make a
 * statement fail on demand; every conclusion afterwards is a query.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderTransactionTest extends AbstractOrderApiTest {

  private static final long TIMEOUT_SECONDS = 10;

  @MockitoSpyBean private PurchaseOrderMapper orderMapperSpy;

  @Autowired private DataSource dataSource;

  @Test
  void shouldRollBackTheReservationWhenTheOrderInsertFails() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 3L);

    doThrow(new DataIntegrityViolationException("forced order insert failure"))
        .when(orderMapperSpy)
        .insert(any(PurchaseOrder.class));

    placeOrder(buyer.token(), activityId, newKey())
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

    ActivityInventory stock = storedInventory(activityId);
    assertThat(stock.getAvailableStock())
        .as("the unit reserved before the insert must be given back")
        .isEqualTo(3);
    assertThat(stock.getReservedStock()).isZero();
    assertThat(storedOrders(activityId)).isEmpty();
  }

  @Test
  void shouldDoNothingAtAllWhenTheActivityIsRefused() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = createDraft(admin, packageRow(1000L), 799L, 3L, OPEN_START, OPEN_END);

    placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isConflict());

    ActivityInventory stock = storedInventory(activityId);
    assertThat(stock.getAvailableStock()).isEqualTo(3);
    assertThat(stock.getReservedStock()).isZero();
    assertThat(storedOrders(activityId)).isEmpty();
  }

  @Test
  void shouldWaitForAnExclusiveActivityLockAndThenSeeTheCommittedState() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 3L);

    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Connection locker = dataSource.getConnection()) {
      locker.setAutoCommit(false);
      try (PreparedStatement lock =
          locker.prepareStatement("SELECT id FROM seckill_activity WHERE id = ? FOR UPDATE")) {
        lock.setLong(1, activityId);
        lock.executeQuery().close();
      }

      CountDownLatch started = new CountDownLatch(1);
      Future<MvcResult> pending =
          worker.submit(
              () -> {
                started.countDown();
                return placeOrder(buyer.token(), activityId, newKey()).andReturn();
              });

      assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(300);
      assertThat(pending.isDone())
          .as("FOR SHARE must wait for the exclusive lock instead of reading a stale row")
          .isFalse();

      // Withdraw the activity while still holding the lock, then release it.
      try (PreparedStatement update =
          locker.prepareStatement("UPDATE seckill_activity SET status = 2 WHERE id = ?")) {
        update.setLong(1, activityId);
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      locker.commit();

      MvcResult result = pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(result.getResponse().getStatus())
          .as("the waiting request must read the state committed by the lock holder, not its own "
              + "older snapshot")
          .isEqualTo(409);
      assertThat(errorCodeOf(result)).isEqualTo("ACTIVITY_NOT_ACTIVE");
    } finally {
      worker.shutdownNow();
    }

    assertThat(storedInventory(activityId).getAvailableStock())
        .as("a refused order consumes nothing")
        .isEqualTo(3);
  }
}
