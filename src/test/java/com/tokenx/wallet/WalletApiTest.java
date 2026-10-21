package com.tokenx.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.order.AbstractOrderApiTest;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.wallet.model.TokenLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The two read endpoints of the wallet, against real MySQL.
 *
 * <p>Three properties are checked here and nowhere else: a caller can only ever see their own
 * balance and their own postings, the walk over the ledger is a stable descending cursor that
 * neither repeats nor skips an entry, and the response contains no internal identifier.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WalletApiTest extends AbstractOrderApiTest {

  @Test
  void shouldReturnTheCallersOwnZeroBalanceBeforeAnyPayment() throws Exception {
    Buyer buyer = registerBuyer();

    MvcResult result = getWallet(buyer.token()).andExpect(status().isOk()).andReturn();

    JsonNode body = json(result);
    assertThat(body.get("availableBalance").asLong()).isZero();
    assertThat(body.get("updatedAt").asText()).isNotBlank();

    assertThat(payload(result))
        .as("a wallet answer is a balance and a timestamp, nothing about who owns it")
        .containsOnlyKeys("availableBalance", "updatedAt");
  }

  @Test
  void shouldReturnTheBalanceCreditedByAPayment() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L);
    String orderNo = payOneNormalOrder(buyer, packageId);

    MvcResult result = getWallet(buyer.token()).andExpect(status().isOk()).andReturn();

    assertThat(json(result).get("availableBalance").asLong())
        .as("the balance is the order's credit snapshot, not a value from the request")
        .isEqualTo(1000L);
    assertThat(storedOrder(orderNo, buyer.id()).getCreditAmount()).isEqualTo(1000L);
  }

  @Test
  void shouldNotMixBalancesBetweenUsers() throws Exception {
    Buyer payer = registerBuyer();
    Buyer bystander = registerBuyer();
    long packageId = packageRow(700L);

    payOneNormalOrder(payer, packageId);

    assertThat(json(getWallet(payer.token()).andReturn()).get("availableBalance").asLong())
        .isEqualTo(700L);
    assertThat(json(getWallet(bystander.token()).andReturn()).get("availableBalance").asLong())
        .as("a second user's account row is a different row")
        .isZero();
    assertThat(storedLedgerOf(bystander.id())).isEmpty();
  }

  @Test
  void shouldDescribeAPostingWithThePublicOrderNumberOnly() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1200L);
    String orderNo = payOneNormalOrder(buyer, packageId);

    MvcResult result = getLedger(buyer.token(), "").andExpect(status().isOk()).andReturn();
    JsonNode entry = json(result).get("items").get(0);

    assertThat(entry.get("bizType").asText()).isEqualTo("ORDER");
    assertThat(entry.get("effectType").asText()).isEqualTo("CREDIT");
    assertThat(entry.get("orderNo").asText()).isEqualTo(orderNo);
    assertThat(entry.get("delta").asLong()).isEqualTo(1200L);
    assertThat(entry.get("balanceAfter").asLong()).isEqualTo(1200L);
    assertThat(entry.get("createdAt").asText()).isNotBlank();

    assertThat(payloadOfEntry(entry))
        .as("the internal order id, the user id and the stored enum codes never leave the server")
        .containsOnlyKeys(
            "entryId", "bizType", "orderNo", "effectType", "delta", "balanceAfter", "createdAt");
  }

  @Test
  void shouldWalkTheLedgerNewestFirstWithoutRepeatingOrSkippingAnEntry() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(500L);

    List<String> paidOrderNumbers = new ArrayList<>();
    for (int index = 0; index < 3; index++) {
      paidOrderNumbers.add(payOneNormalOrder(buyer, packageId));
    }

    JsonNode firstPage = json(getLedger(buyer.token(), "?limit=2").andReturn());
    assertThat(firstPage.get("items")).hasSize(2);
    assertThat(firstPage.get("hasMore").asBoolean()).isTrue();
    long cursor = firstPage.get("nextCursor").asLong();

    JsonNode secondPage = json(getLedger(buyer.token(), "?limit=2&cursor=" + cursor).andReturn());
    assertThat(secondPage.get("items")).hasSize(1);
    assertThat(secondPage.get("hasMore").asBoolean()).isFalse();
    assertThat(secondPage.get("nextCursor").isNull())
        .as("no cursor is offered when there is nothing left to fetch")
        .isTrue();

    List<Long> walked = new ArrayList<>();
    firstPage.get("items").forEach(item -> walked.add(item.get("entryId").asLong()));
    secondPage.get("items").forEach(item -> walked.add(item.get("entryId").asLong()));

    List<Long> storedIds = storedLedgerOf(buyer.id()).stream().map(TokenLedger::getId).toList();
    assertThat(walked)
        .as("the walk covers every posting exactly once, newest first")
        .containsExactlyElementsOf(storedIds);
    assertThat(walked).isSortedAccordingTo((left, right) -> Long.compare(right, left));

    assertThat(
            storedLedgerOf(buyer.id()).stream()
                .map(ledger -> storedOrderById(ledger.getBizId(), buyer.id()).getOrderNo())
                .toList())
        .as("every posting names one of the orders this test really paid")
        .containsExactlyInAnyOrderElementsOf(paidOrderNumbers);
  }

  @Test
  void shouldNotShowAnotherUsersPostings() throws Exception {
    Buyer payer = registerBuyer();
    Buyer bystander = registerBuyer();
    long packageId = packageRow(300L);

    payOneNormalOrder(payer, packageId);

    JsonNode page = json(getLedger(bystander.token(), "").andReturn());
    assertThat(page.get("items")).isEmpty();
    assertThat(page.get("hasMore").asBoolean()).isFalse();
    assertThat(page.get("nextCursor").isNull()).isTrue();
  }

  @Test
  void shouldRejectAnInvalidCursorOrLimit() throws Exception {
    Buyer buyer = registerBuyer();

    for (String query :
        List.of("?limit=0", "?limit=101", "?limit=abc", "?cursor=0", "?cursor=-1", "?cursor=abc")) {
      MvcResult result =
          getLedger(buyer.token(), query).andExpect(status().isBadRequest()).andReturn();
      assertThat(errorCodeOf(result)).as("query %s", query).isEqualTo("VALIDATION_FAILED");
    }
  }

  @Test
  void shouldRequireAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/wallet")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/wallet/ledger")).andExpect(status().isUnauthorized());
  }

  // --- helpers --------------------------------------------------------------------------------

  /** Buys one package and pays for it immediately, returning the public order number. */
  private String payOneNormalOrder(Buyer buyer, long packageId) throws Exception {
    MvcResult created =
        placeNormalOrder(buyer.token(), packageId, newKey())
            .andExpect(status().isCreated())
            .andReturn();
    String orderNo = orderNoOf(created);

    mockPay(buyer.token(), orderNo).andExpect(status().isOk());
    return orderNo;
  }

  private PurchaseOrder storedOrderById(long orderId, long userId) {
    return storedOrdersOfUser(userId).stream()
        .filter(order -> order.getId() == orderId)
        .findFirst()
        .orElseThrow(() -> new AssertionError("order " + orderId + " belongs to nobody here"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> payloadOfEntry(JsonNode entry) throws Exception {
    return objectMapper.readValue(objectMapper.writeValueAsString(entry), Map.class);
  }
}
