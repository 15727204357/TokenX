package com.tokenx.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.order.AbstractOrderApiTest;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.wallet.mapper.TokenLedgerMapper;
import com.tokenx.wallet.model.TokenLedger;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** The order payment and its durable event must commit or roll back as one MySQL transaction. */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentOutboxIntegrationTest extends AbstractOrderApiTest {

  @Autowired private JdbcTemplate jdbc;

  @MockitoSpyBean private TokenLedgerMapper ledgerMapperSpy;

  private final Set<String> orderNumbers = new HashSet<>();

  @AfterEach
  void deleteOutboxRows() {
    for (String orderNo : orderNumbers) {
      jdbc.update(
          "DELETE FROM processed_message WHERE event_id IN "
              + "(SELECT event_id FROM outbox_event WHERE aggregate_id = ?)",
          orderNo);
      jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", orderNo);
    }
    orderNumbers.clear();
  }

  @Test
  void shouldWriteOneOutboxEventWithPaymentAndNotDuplicateItOnReplay() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(700L);
    String orderNo = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());
    orderNumbers.add(orderNo);

    mockPay(buyer.token(), orderNo).andExpect(status().isOk());

    assertThat(outboxCount(orderNo)).isEqualTo(1);
    assertThat(storedOrder(orderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.PAID);

    mockPay(buyer.token(), orderNo).andExpect(status().isOk());

    assertThat(outboxCount(orderNo)).as("payment replay must not create another event").isEqualTo(1);
  }

  @Test
  void shouldRollBackTheOutboxEventWhenThePaymentTransactionRollsBack() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(900L);
    String orderNo = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());
    orderNumbers.add(orderNo);
    doThrow(new org.springframework.dao.DataIntegrityViolationException("forced ledger failure"))
        .when(ledgerMapperSpy)
        .insert(any(TokenLedger.class));

    mockPay(buyer.token(), orderNo).andExpect(status().isInternalServerError());

    assertThat(storedOrder(orderNo, buyer.id()).getStatus()).isEqualTo(OrderStatus.UNPAID);
    assertThat(outboxCount(orderNo)).as("an uncommitted payment must publish no durable event").isZero();
  }

  private int outboxCount(String orderNo) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'ORDER_PAID'",
        Integer.class,
        orderNo);
  }
}
