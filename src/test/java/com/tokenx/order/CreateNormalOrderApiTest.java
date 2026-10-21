package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.catalog.model.TokenPackage;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.order.model.OrderType;
import com.tokenx.order.model.PurchaseOrder;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Buying a package directly: {@code POST /orders}.
 *
 * <p>Everything here runs against the real database. The subject of the tests is not "does the insert
 * happen" but the two properties that make this channel different from the seckill one:
 *
 * <ul>
 *   <li><b>The server owns every value that costs money.</b> The body carries a package id and
 *       nothing else, and the order stores the name, credits and list price read from the package
 *       row at that moment.
 *   <li><b>There is no stock and no per-person limit.</b> A buyer can own many normal orders for the
 *       same package, and creating one touches no activity inventory at all.
 * </ul>
 *
 * <p>Idempotency is tested here too, because the interesting cases are the ones shared with the other
 * channel: a retry must not create a second order, a key already spent on a seckill order must be
 * refused, and an order that already exists must stay reachable after its package is withdrawn.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CreateNormalOrderApiTest extends AbstractOrderApiTest {

  @Test
  void shouldCreateANormalOrderWithAServerSideSnapshot() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1500L, 1299L, PackageStatus.ON_SALE);

    MvcResult result =
        placeNormalOrder(buyer.token(), packageId, newKey())
            .andExpect(status().isCreated())
            .andReturn();
    Map<String, Object> body = payload(result);

    assertThat(body.get("orderNo")).asString().hasSize(26);
    assertThat(body).containsEntry("orderType", "NORMAL");
    assertThat(body).containsEntry("status", "UNPAID");
    assertThat(body).containsEntry("packageId", (int) packageId);
    assertThat(body).containsEntry("packageName", storedPackage(packageId).getName());
    assertThat(body).containsEntry("creditAmount", 1500);
    assertThat(body).containsEntry("payAmountCent", 1299);
    assertThat(body).containsKey("activityId");
    assertThat(body.get("activityId"))
        .as("a normal order belongs to no activity")
        .isNull();
    assertThat(body).doesNotContainKeys("id", "userId", "requestId");

    assertThat(Instant.parse((String) body.get("createdAt"))).isEqualTo(BASE);
    assertThat(Instant.parse((String) body.get("expiresAt")))
        .as("the deadline is created_at plus the configured timeout")
        .isEqualTo(BASE.plus(PAYMENT_TIMEOUT));
    assertThat(body.get("paidAt")).isNull();
    assertThat(body.get("closedAt")).isNull();

    List<PurchaseOrder> orders = storedOrdersOfUser(buyer.id());
    assertThat(orders).hasSize(1);
    PurchaseOrder stored = orders.getFirst();
    assertThat(stored.getOrderType()).isEqualTo(OrderType.NORMAL);
    assertThat(stored.getActivityId()).isNull();
    assertThat(stored.getStatus()).isEqualTo(OrderStatus.UNPAID);
    assertThat(stored.getUserId()).isEqualTo(buyer.id());
    assertThat(stored.getPackageId()).isEqualTo(packageId);
    assertThat(stored.getPackageNameSnapshot()).isEqualTo(storedPackage(packageId).getName());
    assertThat(stored.getCreditAmount()).isEqualTo(1500L);
    assertThat(stored.getPayAmountCent()).isEqualTo(1299L);
    assertThat(stored.getCreatedInstant()).isEqualTo(BASE);
    assertThat(stored.getExpiresInstant()).isEqualTo(BASE.plus(PAYMENT_TIMEOUT));
  }

  @Test
  void shouldReplayTheSameKeyWithoutCreatingASecondOrder() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);
    String key = newKey();

    MvcResult first =
        placeNormalOrder(buyer.token(), packageId, key).andExpect(status().isCreated()).andReturn();
    MvcResult retry =
        placeNormalOrder(buyer.token(), packageId, key).andExpect(status().isOk()).andReturn();

    assertThat(orderNoOf(retry))
        .as("a replayed request returns the order that already exists")
        .isEqualTo(orderNoOf(first));
    assertThat(payload(retry)).containsEntry("status", "UNPAID");
    assertThat(storedOrdersOfUser(buyer.id()))
        .as("the retry must not create a second order")
        .hasSize(1);
  }

  @Test
  void shouldRejectTheSameKeyForADifferentPackage() throws Exception {
    Buyer buyer = registerBuyer();
    long first = packageRow(1000L, 799L, PackageStatus.ON_SALE);
    long second = packageRow(2000L, 1599L, PackageStatus.ON_SALE);
    String key = newKey();

    placeNormalOrder(buyer.token(), first, key).andExpect(status().isCreated());

    MvcResult conflict =
        placeNormalOrder(buyer.token(), second, key).andExpect(status().isConflict()).andReturn();
    assertThat(errorCodeOf(conflict)).isEqualTo("IDEMPOTENCY_KEY_REUSED");

    assertThat(storedOrdersOfUser(buyer.id()))
        .as("the refused attempt must not create an order for the other package")
        .hasSize(1);
    assertThat(storedOrdersOfUser(buyer.id()).getFirst().getPackageId()).isEqualTo(first);
  }

  @Test
  void shouldRejectAKeyAlreadyUsedByASeckillOrder() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);
    long activityId = openActivity(admin, packageId, 599L, 3L);
    String key = newKey();

    placeOrder(buyer.token(), activityId, key).andExpect(status().isCreated());

    MvcResult conflict =
        placeNormalOrder(buyer.token(), packageId, key)
            .andExpect(status().isConflict())
            .andReturn();
    assertThat(errorCodeOf(conflict))
        .as("the key is spent on a seckill order, even for the same package")
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");

    assertThat(storedOrdersOfUser(buyer.id())).hasSize(1);
    assertThat(storedOrdersOfUser(buyer.id()).getFirst().getOrderType())
        .isEqualTo(OrderType.SECKILL);
    assertThat(storedInventory(activityId).getAvailableStock()).isEqualTo(2);
  }

  @Test
  void shouldRejectANormalKeyWhenBuyingAnActivity() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);
    String key = newKey();

    placeNormalOrder(buyer.token(), packageId, key).andExpect(status().isCreated());

    long activityId = openActivity(admin, packageId, 599L, 3L);
    MvcResult conflict =
        placeOrder(buyer.token(), activityId, key).andExpect(status().isConflict()).andReturn();
    assertThat(errorCodeOf(conflict))
        .as("the comparison includes the order type, in both directions")
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");

    assertThat(storedOrdersOfUser(buyer.id())).hasSize(1);
    ActivityInventory stock = storedInventory(activityId);
    assertThat(stock.getAvailableStock()).isEqualTo(3);
    assertThat(stock.getReservedStock()).isZero();
  }

  @Test
  void shouldAllowBuyingTheSamePackageAgainWithANewKey() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);

    MvcResult first =
        placeNormalOrder(buyer.token(), packageId, newKey())
            .andExpect(status().isCreated())
            .andReturn();
    MvcResult second =
        placeNormalOrder(buyer.token(), packageId, newKey())
            .andExpect(status().isCreated())
            .andReturn();

    assertThat(orderNoOf(second))
        .as("a different key is a different purchase")
        .isNotEqualTo(orderNoOf(first));
    assertThat(storedOrdersOfUser(buyer.id()))
        .as("normal purchases have no one-per-package limit")
        .hasSize(2);
    assertThat(storedOrdersOfUser(buyer.id()))
        .allSatisfy(
            order -> {
              assertThat(order.getOrderType()).isEqualTo(OrderType.NORMAL);
              assertThat(order.getActivityId()).isNull();
              assertThat(order.getStatus()).isEqualTo(OrderStatus.UNPAID);
            });
  }

  @Test
  void shouldRejectAnUnknownPackage() throws Exception {
    Buyer buyer = registerBuyer();

    MvcResult result =
        placeNormalOrder(buyer.token(), 424242L, newKey())
            .andExpect(status().isNotFound())
            .andReturn();
    assertThat(errorCodeOf(result)).isEqualTo("PRODUCT_NOT_FOUND");
    assertThat(storedOrdersOfUser(buyer.id())).isEmpty();
  }

  @Test
  void shouldRejectAnOffShelfPackage() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.OFF_SHELF);

    MvcResult result =
        placeNormalOrder(buyer.token(), packageId, newKey())
            .andExpect(status().isConflict())
            .andReturn();
    assertThat(errorCodeOf(result)).isEqualTo("PACKAGE_NOT_AVAILABLE");
    assertThat(storedOrdersOfUser(buyer.id())).isEmpty();
  }

  @Test
  void shouldRejectInvalidBodies() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);

    record Case(String description, String rawBody, Map<String, Object> body) {}

    List<Case> cases =
        List.of(
            new Case("empty object", null, Map.of()),
            new Case("zero id", null, Map.of("packageId", 0)),
            new Case("negative id", null, Map.of("packageId", -1)),
            new Case("non-numeric id", null, Map.of("packageId", "abc")),
            new Case("null id", null, nullMap()),
            new Case("not JSON", "not json", null),
            new Case("empty body", "", null),
            // Sent as raw JSON with the id of the package created above, so a mapper that silently
            // truncated the fraction would buy that real package instead of refusing the body.
            new Case(
                "fractional id that would truncate to a real package",
                "{\"packageId\":" + packageId + ".5}",
                null));

    for (Case testCase : cases) {
      MvcResult result =
          testCase.rawBody() != null
              ? placeNormalOrderRaw(buyer.token(), newKey(), testCase.rawBody()).andReturn()
              : placeNormalOrder(buyer.token(), packageId, newKey(), testCase.body()).andReturn();

      assertThat(result.getResponse().getStatus())
          .as("rejected before any business rule: %s", testCase.description())
          .isEqualTo(400);
      assertThat(errorCodeOf(result))
          .as("%s", testCase.description())
          .isEqualTo("VALIDATION_FAILED");
    }

    assertThat(storedOrdersOfUser(buyer.id()))
        .as("no rejected body may create an order — a fractional id must not become a real purchase")
        .isEmpty();
  }

  @Test
  void shouldRejectAMissingOrMalformedIdempotencyKey() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);

    for (String key : new String[] {null, "", "not-a-uuid", "1-1-1-1-1", "x".repeat(36)}) {
      MockHttpServletRequestBuilder request =
          post("/api/v1/orders")
              .header("Authorization", "Bearer " + buyer.token())
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(Map.of("packageId", packageId)));
      if (key != null) {
        request.header("Idempotency-Key", key);
      }

      MvcResult result = mockMvc.perform(request).andReturn();
      assertThat(result.getResponse().getStatus())
          .as("a key that cannot identify an attempt is a bad request: %s", key)
          .isEqualTo(400);
      assertThat(errorCodeOf(result)).isEqualTo("VALIDATION_FAILED");
    }

    assertThat(storedOrdersOfUser(buyer.id())).isEmpty();
  }

  @Test
  void shouldRequireAToken() throws Exception {
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .header("Idempotency-Key", newKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("packageId", packageId))))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(401);
    assertThat(errorCodeOf(result)).isEqualTo("UNAUTHORIZED");
  }

  @Test
  void shouldStillReplayAfterThePackageWasWithdrawn() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);
    String key = newKey();

    String orderNo =
        orderNoOf(
            placeNormalOrder(buyer.token(), packageId, key)
                .andExpect(status().isCreated())
                .andReturn());

    withdrawPackage(admin, packageId).andExpect(status().isOk());

    MvcResult replay =
        placeNormalOrder(buyer.token(), packageId, key).andExpect(status().isOk()).andReturn();
    assertThat(orderNoOf(replay))
        .as("the replay check runs before the package is validated")
        .isEqualTo(orderNo);

    MvcResult refused =
        placeNormalOrder(buyer.token(), packageId, newKey())
            .andExpect(status().isConflict())
            .andReturn();
    assertThat(errorCodeOf(refused))
        .as("a new attempt is a new sale, and the package is no longer on sale")
        .isEqualTo("PACKAGE_NOT_AVAILABLE");

    assertThat(storedOrdersOfUser(buyer.id())).hasSize(1);
  }

  @Test
  void shouldNotTouchActivityInventory() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);
    long activityId = openActivity(admin, packageId, 599L, 3L);

    ActivityInventory before = storedInventory(activityId);

    placeNormalOrder(buyer.token(), packageId, newKey()).andExpect(status().isCreated());

    ActivityInventory after = storedInventory(activityId);
    assertThat(after.getAvailableStock())
        .as("a normal purchase reserves nothing from an activity")
        .isEqualTo(before.getAvailableStock())
        .isEqualTo(3);
    assertThat(after.getReservedStock()).isZero();
    assertThat(after.getTotalStock()).isEqualTo(before.getTotalStock());
    assertThat(storedOrders(activityId))
        .as("the normal order did not become an activity order")
        .isEmpty();
  }

  @Test
  void shouldIgnoreClientSuppliedAmountsAndStatus() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L, 799L, PackageStatus.ON_SALE);

    MvcResult result =
        placeNormalOrder(buyer.token(), packageId, newKey(), tamperedBody(packageId))
            .andExpect(status().isCreated())
            .andReturn();

    Map<String, Object> body = payload(result);
    assertThat(body).containsEntry("packageName", storedPackage(packageId).getName());
    assertThat(body).containsEntry("creditAmount", 1000);
    assertThat(body).containsEntry("payAmountCent", 799);
    assertThat(body).containsEntry("status", "UNPAID");
    assertThat(Instant.parse((String) body.get("expiresAt")))
        .as("the deadline is the server's, not the client's")
        .isEqualTo(BASE.plus(PAYMENT_TIMEOUT));
  }

  // --- helpers --------------------------------------------------------------------------------

  private static Map<String, Object> nullMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("packageId", null);
    return body;
  }

  /** Every field a client must not be able to set, sent with the values it would prefer. */
  private static Map<String, Object> tamperedBody(long packageId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("packageId", packageId);
    body.put("packageName", "免费套餐");
    body.put("creditAmount", 999999);
    body.put("payAmountCent", 1);
    body.put("status", "PAID");
    body.put("userId", 1);
    body.put("orderType", "SECKILL");
    body.put("expiresAt", "2030-01-01T00:00:00Z");
    return body;
  }
}
