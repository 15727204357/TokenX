package com.tokenx.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.order.AbstractOrderApiTest;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.wallet.model.TokenLedger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * What a ledger page may say about the order behind a posting.
 *
 * <p>A posting stores {@code biz_id}, not the order number, and there is no foreign key to tie the two
 * together. The order number a client sees therefore comes from a join — and a join is only safe if it
 * also asks whether that order belongs to the same person as the posting. Without that predicate, one
 * corrupted {@code biz_id} turns the wallet of user A into a way to read the order numbers of user B.
 *
 * <p>The corruption is inserted directly on purpose: the payment path cannot produce it (it takes
 * {@code orderId} from the row it just paid), so the only way to test the defence is to write the bad
 * row the way an old bug or a manual repair would have left it. Every assertion here is about that
 * row staying <em>visible</em> — hiding it would hide a balance discrepancy — while telling the caller
 * as little as possible about the order it points at.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerOwnershipTest extends AbstractOrderApiTest {

  @Test
  void shouldKeepAForeignPostingVisibleWithoutNamingTheOrderItPointsAt() throws Exception {
    Buyer owner = registerBuyer();
    Buyer stranger = registerBuyer();
    PurchaseOrder strangersOrder = unpaidOrderOf(stranger);

    TokenLedger misattributed = misattributedPosting(owner.id(), strangersOrder.getId(), 400L);

    MvcResult result = getLedger(owner.token(), "").andExpect(status().isOk()).andReturn();
    JsonNode body = json(result);

    assertThat(body.get("items"))
        .as("the anomalous posting belongs to this caller, so it must still be listed")
        .hasSize(1);

    JsonNode entry = body.get("items").get(0);
    assertThat(entry.get("entryId").asLong()).isEqualTo(misattributed.getId());
    assertThat(entry.get("orderNo").isNull())
        .as("the join must refuse to name an order owned by somebody else")
        .isTrue();
    assertThat(entry.get("delta").asLong()).isEqualTo(400L);
    assertThat(entry.get("balanceAfter").asLong()).isEqualTo(400L);
    assertThat(entry.get("createdAt").asText()).isNotBlank();

    assertThat(body(result))
        .as("no part of the response may carry the other user's order number")
        .doesNotContain(strangersOrder.getOrderNo());
  }

  @Test
  void shouldStillNameTheCallersOwnOrderWhenAForeignPostingIsPresent() throws Exception {
    Buyer owner = registerBuyer();
    Buyer stranger = registerBuyer();
    PaidOrder own = payOneOwnOrder(owner, packageRow(500L));

    PurchaseOrder strangersOrder = unpaidOrderOf(stranger);
    TokenLedger misattributed = misattributedPosting(owner.id(), strangersOrder.getId(), 700L);

    MvcResult result = getLedger(owner.token(), "?limit=10").andExpect(status().isOk()).andReturn();
    JsonNode body = json(result);

    assertThat(body.get("items")).hasSize(2);

    JsonNode newest = body.get("items").get(0);
    JsonNode older = body.get("items").get(1);

    assertThat(newest.get("entryId").asLong()).isEqualTo(misattributed.getId());
    assertThat(newest.get("orderNo").isNull())
        .as("tightening the join must not invent a number for the bad row")
        .isTrue();

    assertThat(older.get("orderNo").asText())
        .as("a healthy posting still resolves to the caller's own order")
        .isEqualTo(own.orderNo());

    assertThat(body(result)).doesNotContain(strangersOrder.getOrderNo());
  }

  @Test
  void shouldWalkOnlyTheCallersOwnPostingsAcrossPagesWhenOneIsMisattributed() throws Exception {
    Buyer owner = registerBuyer();
    Buyer stranger = registerBuyer();
    long packageId = packageRow(300L);

    List<Long> ownerEntryIds = new ArrayList<>();
    ownerEntryIds.add(payOneOwnOrder(owner, packageId).posting().getId());
    ownerEntryIds.add(payOneOwnOrder(owner, packageId).posting().getId());

    PurchaseOrder strangersOrder = unpaidOrderOf(stranger);
    TokenLedger misattributed = misattributedPosting(owner.id(), strangersOrder.getId(), 300L);
    ownerEntryIds.add(misattributed.getId());

    // A genuine posting of the other user, which must never be reachable from this walk.
    payOneOwnOrder(stranger, packageId);
    long strangerEntryId = storedLedgerOf(stranger.id()).get(0).getId();

    Map<Long, JsonNode> walked = new LinkedHashMap<>();
    List<String> bodies = new ArrayList<>();
    String query = "?limit=2";
    int pages = 0;

    while (true) {
      MvcResult page = getLedger(owner.token(), query).andExpect(status().isOk()).andReturn();
      JsonNode body = json(page);
      bodies.add(body(page));
      pages++;

      for (JsonNode item : body.get("items")) {
        walked.put(item.get("entryId").asLong(), item);
      }

      if (!body.get("hasMore").asBoolean()) {
        assertThat(body.get("nextCursor").isNull())
            .as("no cursor is offered when nothing is left")
            .isTrue();
        break;
      }
      query = "?limit=2&cursor=" + body.get("nextCursor").asLong();
    }

    List<Long> expected = new ArrayList<>(ownerEntryIds);
    expected.sort(Comparator.reverseOrder());

    assertThat(pages).as("three postings of size two need two pages").isEqualTo(2);
    assertThat(walked.keySet())
        .as("the walk returns every posting of the caller exactly once, newest first")
        .containsExactlyElementsOf(expected);
    assertThat(walked.keySet()).doesNotContain(strangerEntryId);

    assertThat(walked.get(misattributed.getId()).get("orderNo").isNull())
        .as("the misattributed row stays, but without an order number")
        .isTrue();
    assertThat(
            walked.entrySet().stream()
                .filter(entry -> entry.getKey().longValue() != misattributed.getId().longValue())
                .allMatch(entry -> !entry.getValue().get("orderNo").isNull()))
        .as("every healthy posting in the same walk keeps its order number")
        .isTrue();

    assertThat(bodies)
        .allSatisfy(text -> assertThat(text).doesNotContain(strangersOrder.getOrderNo()));
  }

  // --- fixtures -------------------------------------------------------------------------------

  /** An order and the posting its payment wrote, both needed by different assertions. */
  private record PaidOrder(String orderNo, TokenLedger posting) {}

  /**
   * Writes a posting that claims {@code foreignOrderId} while belonging to {@code userId}.
   *
   * <p>This is the corrupted shape the join has to survive: the row is a perfectly valid posting of
   * the caller's wallet, and only the order it points at belongs to somebody else.
   */
  private TokenLedger misattributedPosting(long userId, long foreignOrderId, long credits) {
    TokenLedger posting = TokenLedger.orderCredit(userId, foreignOrderId, credits, credits, BASE);
    ledgerMapper.insert(posting);
    assertThat(posting.getId()).isNotNull();
    return posting;
  }

  /** An order the stranger created and never paid, so its {@code CREDIT} slot is still free. */
  private PurchaseOrder unpaidOrderOf(Buyer buyer) throws Exception {
    long packageId = packageRow(900L);
    MvcResult created =
        placeNormalOrder(buyer.token(), packageId, newKey())
            .andExpect(status().isCreated())
            .andReturn();
    return storedOrder(orderNoOf(created), buyer.id());
  }

  /** Buys and pays one package, returning the order number and the posting it produced. */
  private PaidOrder payOneOwnOrder(Buyer buyer, long packageId) throws Exception {
    MvcResult created =
        placeNormalOrder(buyer.token(), packageId, newKey())
            .andExpect(status().isCreated())
            .andReturn();
    String orderNo = orderNoOf(created);
    mockPay(buyer.token(), orderNo).andExpect(status().isOk());

    PurchaseOrder order = storedOrder(orderNo, buyer.id());
    return new PaidOrder(orderNo, storedLedgerOfOrder(order.getId()));
  }
}
