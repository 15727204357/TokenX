package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.support.TestClockConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Idempotency and the permanent "one order per user per activity" rule.
 *
 * <p>The interesting cases are the ones a naive implementation gets wrong: a retry that must not
 * deduct twice, a retry of a request whose activity has since ended, and a second attempt that must
 * be refused even though the first order is no longer payable.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderIdempotencyApiTest extends AbstractOrderApiTest {

  @Autowired private DataSource dataSource;

  @Test
  void shouldReplayTheSameOrderAndDeductOnlyOnce() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);
    String key = newKey();

    MvcResult first =
        placeOrder(buyer.token(), activityId, key).andExpect(status().isCreated()).andReturn();
    MvcResult retry =
        placeOrder(buyer.token(), activityId, key).andExpect(status().isOk()).andReturn();

    assertThat(orderNoOf(retry))
        .as("a replayed request returns the order that already exists")
        .isEqualTo(orderNoOf(first));
    assertThat(payload(retry)).containsEntry("status", "UNPAID");

    ActivityInventory stock = storedInventory(activityId);
    assertThat(stock.getAvailableStock()).isEqualTo(4);
    assertThat(stock.getReservedStock()).isEqualTo(1);
    assertThat(storedOrders(activityId)).hasSize(1);
  }

  @Test
  void shouldStillReplayAfterTheActivityEndedOrWasWithdrawn() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);
    String key = newKey();

    String orderNo =
        orderNoOf(
            placeOrder(buyer.token(), activityId, key).andExpect(status().isCreated()).andReturn());

    changeStatus(admin, activityId, "OFFLINE").andExpect(status().isOk());
    TestClockConfig.setTo(OPEN_END.plus(Duration.ofHours(1)));

    MvcResult replay =
        placeOrder(buyer.token(), activityId, key).andExpect(status().isOk()).andReturn();
    assertThat(orderNoOf(replay))
        .as("an existing order must stay reachable even when the activity is no longer open")
        .isEqualTo(orderNo);
    assertThat(storedInventory(activityId).getAvailableStock()).isEqualTo(4);
  }

  @Test
  void shouldRejectTheSameKeyUsedForADifferentActivity() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long first = openActivity(admin, 799L, 5L);
    long second = openActivity(admin, 899L, 5L);
    String key = newKey();

    placeOrder(buyer.token(), first, key).andExpect(status().isCreated());

    MvcResult conflict =
        placeOrder(buyer.token(), second, key).andExpect(status().isConflict()).andReturn();
    assertThat(errorCodeOf(conflict)).isEqualTo("IDEMPOTENCY_KEY_REUSED");

    ActivityInventory untouched = storedInventory(second);
    assertThat(untouched.getAvailableStock())
        .as("the rejected attempt must leave the other activity's stock alone")
        .isEqualTo(5);
    assertThat(untouched.getReservedStock()).isZero();
    assertThat(storedOrders(second)).isEmpty();
  }

  @Test
  void shouldRejectASecondOrderForTheSameActivityWithADifferentKey() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);

    placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isCreated());

    MvcResult conflict =
        placeOrder(buyer.token(), activityId, newKey())
            .andExpect(status().isConflict())
            .andReturn();
    assertThat(errorCodeOf(conflict)).isEqualTo("ALREADY_PURCHASED");

    ActivityInventory stock = storedInventory(activityId);
    assertThat(stock.getAvailableStock())
        .as("the reservation taken before the unique key fired must be rolled back")
        .isEqualTo(4);
    assertThat(stock.getReservedStock()).isEqualTo(1);
    assertThat(storedOrders(activityId)).hasSize(1);
  }

  @Test
  void shouldRefuseARebuyEvenAfterTheOrderWasClosed() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);

    placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isCreated());
    PurchaseOrder order = storedOrders(activityId).getFirst();
    closeOrder(order.getOrderNo());

    MvcResult conflict =
        placeOrder(buyer.token(), activityId, newKey())
            .andExpect(status().isConflict())
            .andReturn();
    assertThat(errorCodeOf(conflict))
        .as("a closed order is still an order: the rule is permanent, not a state check")
        .isEqualTo("ALREADY_PURCHASED");
    assertThat(storedOrders(activityId)).hasSize(1);

    // The same conclusion at the level below the API: the unique key refuses the row outright.
    assertThatThrownBy(() -> insertRawOrder(activityId, order.getPackageId(), buyer.id()))
        .as("uk_activity_user is the permanent defence")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldRejectAMissingOrMalformedIdempotencyKey() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);

    for (String key : new String[] {null, "", "not-a-uuid", "1-1-1-1-1", "x".repeat(36)}) {
      MockHttpServletRequestBuilder request =
          post("/api/v1/activities/" + activityId + "/orders")
              .header("Authorization", "Bearer " + buyer.token());
      if (key != null) {
        request.header("Idempotency-Key", key);
      }

      MvcResult result = mockMvc.perform(request).andReturn();
      assertThat(result.getResponse().getStatus())
          .as("rejected before any business rule: %s", key)
          .isEqualTo(400);
      assertThat(errorCodeOf(result)).isEqualTo("VALIDATION_FAILED");
    }

    assertThat(storedOrders(activityId)).isEmpty();
    assertThat(storedInventory(activityId).getAvailableStock()).isEqualTo(5);
  }

  // --- helpers --------------------------------------------------------------------------------

  private void closeOrder(String orderNo) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE purchase_order SET status = 2, closed_at = ?, paid_at = NULL"
                    + " WHERE order_no = ?")) {
      statement.setString(1, "2026-10-21 12:05:00.000");
      statement.setString(2, orderNo);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  /** One more row with the same activity and user: only the unique key can stop it. */
  private long insertRawOrder(long activityId, long packageId, long userId) throws SQLException {
    String orderNo = UUID.randomUUID().toString().replace("-", "").substring(0, 26).toUpperCase();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO purchase_order"
                    + " (order_no, request_id, user_id, package_id, activity_id, order_type,"
                    + "  package_name_snapshot, credit_amount, pay_amount_cent, status, expires_at)"
                    + " VALUES (?, UNHEX(REPLACE(UUID(),'-','')), ?, ?, ?, 1, 'snapshot', 1000, 799,"
                    + "         0, '2026-10-21 12:15:00.000')")) {
      statement.setString(1, orderNo);
      statement.setLong(2, userId);
      statement.setLong(3, packageId);
      statement.setLong(4, activityId);
      return statement.executeUpdate();
    }
  }
}
