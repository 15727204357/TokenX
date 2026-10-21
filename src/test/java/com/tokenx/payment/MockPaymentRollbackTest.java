package com.tokenx.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.order.AbstractOrderApiTest;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.wallet.mapper.TokenLedgerMapper;
import com.tokenx.wallet.model.TokenLedger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Proof that a payment is all-or-nothing.
 *
 * <p>Each test breaks one later statement of the transaction and then queries all four affected
 * tables: the order, the inventory row, the account and the ledger. A test that only asserted the
 * exception would pass even if the order had already been marked {@code PAID} and the stock
 * released, which is exactly the failure mode worth guarding against.
 *
 * <p>The spies exist only to make a statement fail on demand; every conclusion afterwards is a
 * query, never a mock expectation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MockPaymentRollbackTest extends AbstractOrderApiTest {

  @MockitoSpyBean private TokenAccountMapper accountMapperSpy;

  @MockitoSpyBean private TokenLedgerMapper ledgerMapperSpy;

  @Autowired private DataSource dataSource;

  @Test
  void shouldRollBackEverythingWhenTheReservationCannotBeSettled() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 2L);
    String orderNo = orderNoOf(placeOrder(buyer.token(), activityId, newKey()).andReturn());

    // Break the invariant F4 established: an unpaid order with no matching reservation.
    zeroReservedStock(activityId);

    mockPay(buyer.token(), orderNo)
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

    assertThat(storedOrder(orderNo, buyer.id()).getStatus())
        .as("the status gate must be rolled back together with the failed step")
        .isEqualTo(OrderStatus.UNPAID);
    assertThat(storedOrder(orderNo, buyer.id()).getPaidInstant()).isNull();
    assertThat(storedBalance(buyer.id())).as("no credits without a settled reservation").isZero();
    assertThat(storedLedgerOf(buyer.id())).isEmpty();

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getReservedStock())
        .as("an inventory update that matched nothing must not be compensated twice")
        .isZero();
    assertThat(inventory.getAvailableStock()).isEqualTo(1);
  }

  @Test
  void shouldRollBackEverythingWhenTheAccountRowIsMissing() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(500L);
    String orderNo = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());

    deleteAccount(buyer.id());

    mockPay(buyer.token(), orderNo)
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

    assertThat(storedAccount(buyer.id()))
        .as("a missing account is an invariant break, never a reason to create one while paying")
        .isNull();
    assertThat(storedOrder(orderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.UNPAID);
    assertThat(storedLedgerOf(buyer.id())).isEmpty();
  }

  @Test
  void shouldRollBackEverythingWhenTheAccountUpdateFails() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(500L);
    String orderNo = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());

    doThrow(new DataIntegrityViolationException("forced account update failure"))
        .when(accountMapperSpy)
        .creditBalance(anyLong(), anyLong());

    mockPay(buyer.token(), orderNo)
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

    assertThat(storedBalance(buyer.id())).isZero();
    assertThat(storedOrder(orderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.UNPAID);
    assertThat(storedLedgerOf(buyer.id())).isEmpty();
  }

  @Test
  void shouldRollBackTheCreditAndTheOrderWhenTheLedgerInsertFails() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 2L);
    String orderNo = orderNoOf(placeOrder(buyer.token(), activityId, newKey()).andReturn());

    doThrow(new DataIntegrityViolationException("forced ledger insert failure"))
        .when(ledgerMapperSpy)
        .insert(any(TokenLedger.class));

    mockPay(buyer.token(), orderNo)
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

    assertThat(storedBalance(buyer.id()))
        .as("a balance that cannot be evidenced by a posting must not survive")
        .isZero();
    assertThat(storedLedgerOf(buyer.id())).isEmpty();
    assertThat(storedOrder(orderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.UNPAID);
    assertThat(storedOrder(orderNo, buyer.id()).getPaidInstant()).isNull();

    ActivityInventory inventory = storedInventory(activityId);
    assertThat(inventory.getReservedStock()).as("the released unit must come back").isEqualTo(1);
    assertThat(inventory.getAvailableStock()).isEqualTo(1);
  }

  // --- helpers --------------------------------------------------------------------------------

  private void zeroReservedStock(long activityId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE activity_inventory SET reserved_stock = 0 WHERE activity_id = ?")) {
      statement.setLong(1, activityId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private void deleteAccount(long userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("DELETE FROM token_account WHERE user_id = ?")) {
      statement.setLong(1, userId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }
}
