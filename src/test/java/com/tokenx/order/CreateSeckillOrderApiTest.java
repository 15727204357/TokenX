package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.support.TestClockConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Creating a seckill order: the happy path, and every reason it is refused.
 *
 * <p>Everything asserted here is read back from MySQL, so a passing test means the row, the stock
 * and the response agree — not that a mock returned the object it was handed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CreateSeckillOrderApiTest extends AbstractOrderApiTest {

  @Test
  void shouldCreateAnUnpaidOrderAndReserveExactlyOneUnit() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L);
    long activityId = openActivity(admin, packageId, 799L, 10L);

    MvcResult result = placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isCreated()).andReturn();
    Map<String, Object> body = payload(result);

    assertThat(body.get("orderNo")).asString().hasSize(26);
    assertThat(body.get("orderType")).isEqualTo("SECKILL");
    assertThat(body.get("status")).as("status is rendered by name, not as the stored code")
        .isEqualTo("UNPAID");
    assertThat(body.get("activityId")).isEqualTo((int) activityId);
    assertThat(body.get("packageId")).isEqualTo((int) packageId);
    assertThat(body.get("packageName")).isNotNull();
    assertThat(body.get("creditAmount")).as("credits come from the activity snapshot")
        .isEqualTo(1000);
    assertThat(body.get("payAmountCent")).as("the price comes from the activity, not the client")
        .isEqualTo(799);
    assertThat(body.get("paidAt")).isNull();
    assertThat(body.get("closedAt")).isNull();

    assertThat(Instant.parse((String) body.get("createdAt")))
        .as("created_at is the injected clock, at millisecond precision")
        .isEqualTo(BASE);
    assertThat(Instant.parse((String) body.get("expiresAt")))
        .as("the deadline is created_at + the configured timeout, fixed at creation")
        .isEqualTo(BASE.plus(PAYMENT_TIMEOUT));

    ActivityInventory stock = storedInventory(activityId);
    assertThat(stock.getTotalStock()).isEqualTo(10);
    assertThat(stock.getAvailableStock()).isEqualTo(9);
    assertThat(stock.getReservedStock()).isEqualTo(1);

    List<PurchaseOrder> orders = storedOrders(activityId);
    assertThat(orders).hasSize(1);
    PurchaseOrder stored = orders.getFirst();
    assertThat(stored.getStatus()).isEqualTo(OrderStatus.UNPAID);
    assertThat(stored.getUserId()).isEqualTo(buyer.id());
    assertThat(stored.getOrderNo()).isEqualTo(body.get("orderNo"));
    assertThat(stored.getRequestId()).as("the key is stored as compact binary").hasSize(16);
  }

  @Test
  void shouldNotExposeAnyInternalField() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 10L);

    MvcResult result = placeOrder(buyer.token(), activityId, newKey()).andReturn();

    assertThat(payload(result).keySet())
        .as("the self-increment id, the owner and the idempotency key stay inside the server")
        .containsExactlyInAnyOrder(
            "orderNo",
            "orderType",
            "activityId",
            "packageId",
            "packageName",
            "creditAmount",
            "payAmountCent",
            "status",
            "expiresAt",
            "paidAt",
            "closedAt",
            "createdAt");
  }

  @Test
  void shouldAllowOrderingExactlyAtTheStartBoundary() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId =
        createDraft(admin, packageRow(1000L), 799L, 5L, BASE, BASE.plus(Duration.ofHours(1)));
    changeStatus(admin, activityId, "PUBLISHED").andExpect(status().isOk());

    placeOrder(buyer.token(), activityId, newKey())
        .andExpect(status().isCreated())
        .andExpect(
            ignored -> assertThat(storedInventory(activityId).getAvailableStock()).isEqualTo(4));
  }

  @Test
  void shouldRejectOrderingExactlyAtTheEndBoundary() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);

    TestClockConfig.setTo(OPEN_END);

    MvcResult result = placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isConflict()).andReturn();
    assertThat(errorCodeOf(result)).isEqualTo("ACTIVITY_NOT_ACTIVE");
    assertThat(storedInventory(activityId).getAvailableStock())
        .as("a refused order must not consume stock")
        .isEqualTo(5);
  }

  @Test
  void shouldRejectAnEndedActivity() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);

    TestClockConfig.setTo(OPEN_END.plus(Duration.ofMinutes(1)));

    MvcResult result = placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isConflict()).andReturn();
    assertThat(errorCodeOf(result)).isEqualTo("ACTIVITY_NOT_ACTIVE");
    assertThat(storedOrders(activityId)).isEmpty();
  }

  @Test
  void shouldRejectAnUpcomingActivity() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId =
        createDraft(
            admin,
            packageRow(1000L),
            799L,
            5L,
            BASE.plus(Duration.ofMinutes(30)),
            BASE.plus(Duration.ofMinutes(90)));
    changeStatus(admin, activityId, "PUBLISHED").andExpect(status().isOk());

    MvcResult result = placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isConflict()).andReturn();
    assertThat(errorCodeOf(result)).isEqualTo("ACTIVITY_NOT_ACTIVE");
    assertThat(storedInventory(activityId).getAvailableStock()).isEqualTo(5);
    assertThat(storedOrders(activityId)).isEmpty();
  }

  @Test
  void shouldRejectADraftActivity() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = createDraft(admin, packageRow(1000L), 799L, 5L, OPEN_START, OPEN_END);

    MvcResult result = placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isConflict()).andReturn();
    assertThat(errorCodeOf(result)).isEqualTo("ACTIVITY_NOT_ACTIVE");
    assertThat(storedOrders(activityId)).isEmpty();
  }

  @Test
  void shouldRejectAWithdrawnActivity() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);
    changeStatus(admin, activityId, "OFFLINE").andExpect(status().isOk());

    MvcResult result = placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isConflict()).andReturn();
    assertThat(errorCodeOf(result)).isEqualTo("ACTIVITY_NOT_ACTIVE");
    assertThat(storedInventory(activityId))
        .as("withdrawing must not reset or release stock")
        .satisfies(
            stock -> {
              assertThat(stock.getTotalStock()).isEqualTo(5);
              assertThat(stock.getAvailableStock()).isEqualTo(5);
              assertThat(stock.getReservedStock()).isZero();
            });
  }

  @Test
  void shouldAnswerNotFoundForAnUnknownActivity() throws Exception {
    Buyer buyer = registerBuyer();

    MvcResult result = placeOrder(buyer.token(), 987_654_321L, newKey()).andExpect(status().isNotFound()).andReturn();
    assertThat(errorCodeOf(result)).isEqualTo("ACTIVITY_NOT_FOUND");
  }

  @Test
  void shouldRejectASoldOutActivityWithoutCreatingAnOrder() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 0L);

    MvcResult result = placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isConflict()).andReturn();
    assertThat(errorCodeOf(result)).isEqualTo("ACTIVITY_SOLD_OUT");
    assertThat(storedOrders(activityId)).isEmpty();
    assertThat(storedInventory(activityId).getAvailableStock()).isZero();
  }

  @Test
  void shouldIgnoreClientSuppliedIdentityAmountsAndStatus() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);

    MvcResult result =
        placeOrder(
                buyer.token(),
                activityId,
                newKey(),
                Map.of(
                    "userId", 999_999,
                    "payAmountCent", 1,
                    "creditAmount", 999_999,
                    "status", "PAID",
                    "expiresAt", "2099-01-01T00:00:00Z",
                    "activityId", 1))
            .andExpect(status().isCreated())
            .andReturn();

    Map<String, Object> body = payload(result);
    assertThat(body.get("activityId")).as("the path decides the activity, not the body")
        .isEqualTo((int) activityId);
    assertThat(body.get("payAmountCent")).isEqualTo(799);
    assertThat(body.get("creditAmount")).isEqualTo(1000);
    assertThat(body.get("status")).isEqualTo("UNPAID");
    assertThat(Instant.parse((String) body.get("expiresAt"))).isEqualTo(BASE.plus(PAYMENT_TIMEOUT));

    PurchaseOrder stored = storedOrders(activityId).getFirst();
    assertThat(stored.getUserId()).as("the token decides who buys").isEqualTo(buyer.id());
  }

  @Test
  void shouldSellAnActivityWhosePackageWasWithdrawnAfterPublishing() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1000L);
    long activityId = openActivity(admin, packageId, 799L, 5L);

    packageMapper.updateStatus(packageId, PackageStatus.OFF_SHELF);

    placeOrder(buyer.token(), activityId, newKey())
        .andExpect(status().isCreated())
        .andExpect(
            ignored -> assertThat(storedInventory(activityId).getAvailableStock()).isEqualTo(4));
  }
}
