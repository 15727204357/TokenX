package com.tokenx.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.activity.mapper.ActivityInventoryMapper;
import com.tokenx.activity.mapper.SeckillActivityMapper;
import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.catalog.mapper.TokenPackageMapper;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.catalog.model.TokenPackage;
import com.tokenx.order.mapper.PurchaseOrderMapper;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.support.AbstractIntegrationTest;
import com.tokenx.support.TestAccounts;
import com.tokenx.support.TestAdmins;
import com.tokenx.support.TestClockConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared setup for the order tests: real MySQL, a controllable clock, genuine logins, and cleanup
 * that removes only the rows a test created.
 *
 * <p>The default activity window is already <em>open</em> around {@link #BASE}, because most tests
 * are about what happens once an activity accepts orders. Window edges are tested by moving the
 * clock or by building an activity with an explicit window, never by sleeping.
 *
 * <p>No foreign keys exist by design, so cleanup deletes the child rows explicitly, in the reverse
 * order of creation.
 */
@Import(TestClockConfig.class)
abstract class AbstractOrderApiTest extends AbstractIntegrationTest {

  protected static final Instant BASE = TestClockConfig.BASE;

  /** Open window: started an hour ago, ends an hour from now. */
  protected static final Instant OPEN_START = BASE.minus(Duration.ofHours(1));

  protected static final Instant OPEN_END = BASE.plus(Duration.ofHours(1));

  /** Default payment deadline, matching {@code tokenx.order.payment-timeout}. */
  protected static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(15);

  @Autowired protected MockMvc mockMvc;
  @Autowired protected ObjectMapper objectMapper;
  @Autowired protected AppUserMapper userMapper;
  @Autowired protected TokenAccountMapper accountMapper;
  @Autowired protected TokenPackageMapper packageMapper;
  @Autowired protected SeckillActivityMapper activityMapper;
  @Autowired protected ActivityInventoryMapper inventoryMapper;
  @Autowired protected PurchaseOrderMapper orderMapper;
  @Autowired protected PasswordEncoder passwordEncoder;

  private final List<Long> activityIds = new ArrayList<>();
  private final List<Long> packageIds = new ArrayList<>();
  private final List<Long> adminIds = new ArrayList<>();
  private final List<Long> buyerIds = new ArrayList<>();

  /** A logged-in buyer: the id is needed for assertions and cleanup, the token for the request. */
  protected record Buyer(long id, String token) {}

  @BeforeEach
  void resetClock() {
    TestClockConfig.reset();
  }

  @AfterEach
  void deleteRowsCreatedByThisTest() {
    for (Long activityId : activityIds) {
      orderMapper.delete(
          Wrappers.<PurchaseOrder>lambdaQuery().eq(PurchaseOrder::getActivityId, activityId));
      inventoryMapper.deleteById(activityId);
      activityMapper.deleteById(activityId);
    }
    activityIds.clear();

    for (Long buyerId : buyerIds) {
      orderMapper.delete(
          Wrappers.<PurchaseOrder>lambdaQuery().eq(PurchaseOrder::getUserId, buyerId));
      accountMapper.deleteById(buyerId);
      userMapper.deleteById(buyerId);
    }
    buyerIds.clear();

    for (Long packageId : packageIds) {
      packageMapper.deleteById(packageId);
    }
    packageIds.clear();

    for (Long adminId : adminIds) {
      userMapper.deleteById(adminId);
    }
    adminIds.clear();
  }

  // --- identities -----------------------------------------------------------------------------

  protected String adminToken() throws Exception {
    TestAdmins.Admin admin = TestAdmins.create(userMapper, passwordEncoder);
    adminIds.add(admin.id());
    return login(admin.username(), admin.password());
  }

  /** Registers an ordinary user through the public API, then logs it in. */
  protected Buyer registerBuyer() throws Exception {
    String username = TestAccounts.uniqueUsername("buyer");
    String password = TestAccounts.password();

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("username", username, "password", password))))
        .andExpect(status().isCreated());

    AppUser user =
        userMapper.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username));
    buyerIds.add(user.getId());
    return new Buyer(user.getId(), login(username, password));
  }

  protected String login(String username, String password) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of("username", username, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(body(result)).get("accessToken").asText();
  }

  // --- fixtures -------------------------------------------------------------------------------

  /** Inserts a package row directly: these tests are about ordering, not about the catalogue API. */
  protected long packageRow(long creditAmount) {
    TokenPackage tokenPackage = new TokenPackage();
    tokenPackage.setName("下单套餐-" + UUID.randomUUID().toString().substring(0, 8));
    tokenPackage.setCreditAmount(creditAmount);
    tokenPackage.setListPriceCent(creditAmount / 2 + 1);
    tokenPackage.setStatus(PackageStatus.ON_SALE);
    packageMapper.insert(tokenPackage);
    packageIds.add(tokenPackage.getId());
    return tokenPackage.getId();
  }

  protected long createDraft(
      String adminToken, long packageId, long priceCent, long stock, Instant startAt, Instant endAt)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("packageId", packageId);
    body.put("activityPriceCent", priceCent);
    body.put("totalStock", stock);
    body.put("startAt", startAt.toString());
    body.put("endAt", endAt.toString());

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/admin/activities")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();

    long id = objectMapper.readTree(body(result)).get("id").asLong();
    activityIds.add(id);
    return id;
  }

  /** A published activity with an already-open window: the normal subject of these tests. */
  protected long openActivity(String adminToken, long priceCent, long stock) throws Exception {
    return openActivity(adminToken, packageRow(1000L), priceCent, stock);
  }

  protected long openActivity(String adminToken, long packageId, long priceCent, long stock)
      throws Exception {
    long id = createDraft(adminToken, packageId, priceCent, stock, OPEN_START, OPEN_END);
    changeStatus(adminToken, id, "PUBLISHED").andExpect(status().isOk());
    return id;
  }

  protected ResultActions changeStatus(String adminToken, long activityId, String target)
      throws Exception {
    return mockMvc.perform(
        patch("/api/v1/admin/activities/" + activityId + "/status")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("status", target))));
  }

  // --- requests under test --------------------------------------------------------------------

  /** Orders without a body, which is the documented contract. */
  protected ResultActions placeOrder(String token, long activityId, String idempotencyKey)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/activities/" + activityId + "/orders")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", idempotencyKey));
  }

  /** Orders with a body a client might try to tamper with; it must be ignored, not honoured. */
  protected ResultActions placeOrder(
      String token, long activityId, String idempotencyKey, Map<String, Object> body)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/activities/" + activityId + "/orders")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
  }

  protected ResultActions getOrder(String token, String orderNo) throws Exception {
    return mockMvc.perform(
        get("/api/v1/orders/" + orderNo).header("Authorization", "Bearer " + token));
  }

  protected static String newKey() {
    return UUID.randomUUID().toString();
  }

  // --- assertions helpers ---------------------------------------------------------------------

  protected JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(body(result));
  }

  /**
   * The response as a plain map, so a test can assert on the exact set of field names.
   *
   * <p>Reading it as a map instead of walking {@link JsonNode} keeps the "no internal field may
   * leak" assertion independent of the JSON library's iteration API.
   */
  @SuppressWarnings("unchecked")
  protected Map<String, Object> payload(MvcResult result) throws Exception {
    return objectMapper.readValue(body(result), Map.class);
  }

  protected String orderNoOf(MvcResult result) throws Exception {
    return json(result).get("orderNo").asText();
  }

  protected String errorCodeOf(MvcResult result) throws Exception {
    return json(result).get("code").asText();
  }

  protected ActivityInventory storedInventory(long activityId) {
    return inventoryMapper.selectById(activityId);
  }

  protected List<PurchaseOrder> storedOrders(long activityId) {
    return orderMapper.selectList(
        Wrappers.<PurchaseOrder>lambdaQuery().eq(PurchaseOrder::getActivityId, activityId));
  }

  protected String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }
}
