package com.tokenx.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.order.AbstractOrderApiTest;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.order.model.OrderType;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.support.TestClockConfig;
import com.tokenx.wallet.model.LedgerBizType;
import com.tokenx.wallet.model.LedgerEffectType;
import com.tokenx.wallet.model.TokenLedger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Mock payment, end to end, against real MySQL.
 *
 * <p>The two things that must never be true are asserted from the database, not from the response:
 * an order is credited exactly once, and a failed or repeated attempt changes no balance, no stock
 * and no ledger.
 *
 * <p>There is no payment gateway here: paying is a single local transaction, so nothing below waits
 * on a network, and no {@code payment_record} exists to check.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MockPaymentApiTest extends AbstractOrderApiTest {

  @Autowired private DataSource dataSource;

  @Test
  void shouldPayANormalOrderAndCreditTheWalletExactlyOnce() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);
    String admin = adminToken();
    long activityId = openActivity(admin, 699L, 3L);
    ActivityInventory beforePayment = storedInventory(activityId);

    String orderNo = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());

    MvcResult paid = mockPay(buyer.token(), orderNo).andExpect(status().isOk()).andReturn();
    JsonNode body = json(paid);
    assertThat(body.get("status").asText()).isEqualTo("PAID");
    assertThat(Instant.parse(body.get("paidAt").asText())).isEqualTo(BASE);

    PurchaseOrder stored = storedOrder(orderNo, buyer.id());
    assertThat(stored.getStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(stored.getPaidInstant()).isEqualTo(BASE);
    assertThat(stored.getOrderType()).isEqualTo(OrderType.NORMAL);

    assertThat(storedBalance(buyer.id())).isEqualTo(1000L);

    TokenLedger posting = storedLedgerOfOrder(stored.getId());
    assertThat(posting).isNotNull();
    assertThat(posting.getUserId()).isEqualTo(buyer.id());
    assertThat(posting.getBizType()).isEqualTo(LedgerBizType.ORDER);
    assertThat(posting.getEffectType()).isEqualTo(LedgerEffectType.CREDIT);
    assertThat(posting.getDelta()).isEqualTo(1000L);
    assertThat(posting.getBalanceAfter()).isEqualTo(1000L);
    assertThat(posting.getCreatedAt())
        .as("the posting is timestamped by the same clock reading as paid_at")
        .isEqualTo(PurchaseOrder.toColumn(BASE));

    assertThat(storedInventory(activityId).getAvailableStock())
        .as("a normal order has no activity, so paying it must not touch any stock row")
        .isEqualTo(beforePayment.getAvailableStock());
    assertThat(storedInventory(activityId).getReservedStock())
        .isEqualTo(beforePayment.getReservedStock());
  }

  @Test
  void shouldReleaseTheReservedUnitWhenASeckillOrderIsPaid() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 3L);

    String orderNo = orderNoOf(placeOrder(buyer.token(), activityId, newKey()).andReturn());

    ActivityInventory reserved = storedInventory(activityId);
    assertThat(reserved.getAvailableStock()).isEqualTo(2);
    assertThat(reserved.getReservedStock()).isEqualTo(1);

    mockPay(buyer.token(), orderNo).andExpect(status().isOk());

    ActivityInventory paid = storedInventory(activityId);
    assertThat(paid.getReservedStock()).as("the reservation is settled, not invented").isZero();
    assertThat(paid.getAvailableStock())
        .as("paying a reserved unit never puts it back on sale")
        .isEqualTo(2);
    assertThat(paid.getTotalStock()).isEqualTo(3);

    assertThat(storedBalance(buyer.id())).isEqualTo(1000L);
    PurchaseOrder stored = storedOrder(orderNo, buyer.id());
    assertThat(stored.getStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(storedLedgerOf(buyer.id())).hasSize(1);
  }

  @Test
  void shouldBeIdempotentWhenTheSameOrderIsPaidTwice() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 3L);
    String orderNo = orderNoOf(placeOrder(buyer.token(), activityId, newKey()).andReturn());

    MvcResult first = mockPay(buyer.token(), orderNo).andExpect(status().isOk()).andReturn();
    MvcResult second = mockPay(buyer.token(), orderNo).andExpect(status().isOk()).andReturn();

    assertThat(json(second).get("status").asText()).isEqualTo("PAID");
    assertThat(json(second).get("orderNo").asText()).isEqualTo(json(first).get("orderNo").asText());
    assertThat(Instant.parse(json(second).get("paidAt").asText()))
        .as("the replay must not restamp the payment")
        .isEqualTo(Instant.parse(json(first).get("paidAt").asText()));

    assertThat(storedBalance(buyer.id())).as("a replay is not a second credit").isEqualTo(1000L);
    assertThat(storedLedgerOf(buyer.id())).hasSize(1);

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getReservedStock()).isZero();
    assertThat(inventory.getAvailableStock()).isEqualTo(2);
  }

  @Test
  void shouldReturnTheSame404ForAnUnknownOrderAndForSomeoneElsesOrder() throws Exception {
    Buyer owner = registerBuyer();
    Buyer stranger = registerBuyer();
    long packageId = packageRow(400L);
    String orderNo = orderNoOf(placeNormalOrder(owner.token(), packageId, newKey()).andReturn());

    MvcResult otherPersons =
        mockPay(stranger.token(), orderNo).andExpect(status().isNotFound()).andReturn();
    assertThat(errorCodeOf(otherPersons)).isEqualTo("ORDER_NOT_FOUND");

    String unknown = "ZZZZZZZZZZ0000000000000001";
    MvcResult missing =
        mockPay(stranger.token(), unknown).andExpect(status().isNotFound()).andReturn();
    assertThat(errorCodeOf(missing)).isEqualTo("ORDER_NOT_FOUND");

    assertThat(storedOrder(orderNo, owner.id()).getStatus())
        .as("a stranger's attempt must not pay the owner's order")
        .isEqualTo(OrderStatus.UNPAID);
    assertThat(storedBalance(owner.id())).isZero();
    assertThat(storedBalance(stranger.id())).isZero();
    assertThat(storedLedgerOf(owner.id())).isEmpty();
  }

  @Test
  void shouldRefuseAClosedOrderWithoutAnySideEffect() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 3L);
    String orderNo = orderNoOf(placeOrder(buyer.token(), activityId, newKey()).andReturn());
    markClosed(orderNo);

    MvcResult refused =
        mockPay(buyer.token(), orderNo).andExpect(status().isConflict()).andReturn();
    assertThat(errorCodeOf(refused)).isEqualTo("ORDER_STATE_CONFLICT");

    assertThat(storedOrder(orderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.CLOSED);
    assertThat(storedBalance(buyer.id())).isZero();
    assertThat(storedLedgerOf(buyer.id())).isEmpty();

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getReservedStock())
        .as("closing an order is the timeout path's job; payment must not release stock either")
        .isEqualTo(1);
    assertThat(inventory.getAvailableStock()).isEqualTo(2);
  }

  @Test
  void shouldAcceptPaymentOneMillisecondBeforeTheDeadlineAndRefuseItExactlyAtTheDeadline()
      throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(600L);

    String payableOrder = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());
    Instant deadline = storedOrder(payableOrder, buyer.id()).getExpiresInstant();

    TestClockConfig.setTo(deadline.minusMillis(1));
    mockPay(buyer.token(), payableOrder).andExpect(status().isOk());
    assertThat(storedBalance(buyer.id())).isEqualTo(600L);

    TestClockConfig.setTo(BASE);
    String expiredOrder = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());

    TestClockConfig.setTo(deadline);
    MvcResult refused = mockPay(buyer.token(), expiredOrder).andExpect(status().isConflict()).andReturn();
    assertThat(errorCodeOf(refused)).isEqualTo("ORDER_STATE_CONFLICT");

    assertThat(storedOrder(expiredOrder, buyer.id()).getStatus())
        .as("F5 must not close the order; it only refuses to credit it")
        .isEqualTo(OrderStatus.UNPAID);
    assertThat(storedBalance(buyer.id())).as("an expired order credits nothing").isEqualTo(600L);
    assertThat(storedLedgerOf(buyer.id())).hasSize(1);
  }

  @Test
  void shouldIgnoreAForgeBodyWithAmountsAnotherUserAndAStatus() throws Exception {
    Buyer buyer = registerBuyer();
    Buyer victim = registerBuyer();
    long packageId = packageRow(800L, 500L, PackageStatus.ON_SALE);
    String orderNo = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());

    Map<String, Object> forged = new LinkedHashMap<>();
    forged.put("userId", victim.id());
    forged.put("creditAmount", 999_999);
    forged.put("payAmountCent", 1);
    forged.put("status", "PAID");
    forged.put("paidAt", "2020-01-01T00:00:00Z");

    mockPay(buyer.token(), orderNo, forged).andExpect(status().isOk());

    assertThat(storedBalance(buyer.id())).as("the credits come from the order snapshot").isEqualTo(800L);
    assertThat(storedBalance(victim.id())).as("a userId in the body is not a payer").isZero();
    assertThat(storedOrder(orderNo, buyer.id()).getPaidInstant())
        .as("a paidAt in the body is not a clock")
        .isEqualTo(BASE);

    TokenLedger posting = storedLedgerOfOrder(storedOrder(orderNo, buyer.id()).getId());
    assertThat(posting.getDelta()).isEqualTo(800L);
    assertThat(posting.getUserId()).isEqualTo(buyer.id());
  }

  @Test
  void shouldRejectAMalformedOrderNumber() throws Exception {
    Buyer buyer = registerBuyer();

    for (String candidate : List.of("not-an-order", "ABC", "ZZZZZZZZZZ000000000000000!")) {
      MvcResult result =
          mockPay(buyer.token(), candidate).andExpect(status().isBadRequest()).andReturn();
      assertThat(errorCodeOf(result)).as("candidate '%s'", candidate).isEqualTo("VALIDATION_FAILED");
    }
  }

  @Test
  void shouldRequireAuthentication() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(100L);
    String orderNo = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());

    mockMvc
        .perform(
            post("/api/v1/orders/" + orderNo + "/mock-pay"))
        .andExpect(status().isUnauthorized());

    assertThat(storedOrder(orderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.UNPAID);
  }

  @Test
  void shouldReconcileBalanceAgainstTheLedgerAndStockAgainstTheOrders() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long packageId = packageRow(300L);

    long activityId = openActivity(admin, 799L, 4L);
    String seckillOrder = orderNoOf(placeOrder(buyer.token(), activityId, newKey()).andReturn());
    String firstNormal = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());
    String secondNormal = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());

    mockPay(buyer.token(), seckillOrder).andExpect(status().isOk());
    mockPay(buyer.token(), firstNormal).andExpect(status().isOk());
    mockPay(buyer.token(), secondNormal).andExpect(status().isOk());
    // A replay after three successful payments must change no total.
    mockPay(buyer.token(), firstNormal).andExpect(status().isOk());

    assertThat(balanceMinusLedgerSum(buyer.id()))
        .as("token_account.available_balance must equal SUM(token_ledger.delta)")
        .isZero();

    List<PurchaseOrder> orders = storedOrdersOfUser(buyer.id());
    assertThat(orders).hasSize(3);
    assertThat(orders).allSatisfy(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID));
    assertThat(storedLedgerOf(buyer.id())).hasSize(3);
    assertThat(orders)
        .allSatisfy(order -> assertThat(storedLedgerOfOrder(order.getId())).isNotNull());

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(
            inventory.getAvailableStock()
                + inventory.getReservedStock()
                + paidSeckillCount(activityId))
        .as("available + reserved + paid orders must still equal total_stock")
        .isEqualTo(inventory.getTotalStock().longValue());
  }

  // --- helpers --------------------------------------------------------------------------------

  /** The timeout path belongs to F6, so a closed order is produced with one direct statement. */
  private void markClosed(String orderNo) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE purchase_order SET status = 2, closed_at = ?, updated_at = ?"
                    + " WHERE order_no = ?")) {
      statement.setString(1, "2026-10-21 12:05:00.000");
      statement.setString(2, "2026-10-21 12:05:00.000");
      statement.setString(3, orderNo);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private long balanceMinusLedgerSum(long userId) throws SQLException {
    return queryLong(
        "SELECT a.available_balance - COALESCE(SUM(l.delta), 0)"
            + " FROM token_account a LEFT JOIN token_ledger l ON l.user_id = a.user_id"
            + " WHERE a.user_id = "
            + userId
            + " GROUP BY a.available_balance");
  }

  private long paidSeckillCount(long activityId) throws SQLException {
    return queryLong(
        "SELECT COUNT(*) FROM purchase_order WHERE activity_id = "
            + activityId
            + " AND order_type = 1 AND status = 1");
  }

  private long queryLong(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rs = statement.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
