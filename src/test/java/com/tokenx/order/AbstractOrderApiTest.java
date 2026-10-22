package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.tokenx.auth.model.TokenAccount;
import com.tokenx.catalog.mapper.TokenPackageMapper;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.catalog.model.TokenPackage;
import com.tokenx.order.mapper.PurchaseOrderMapper;
import com.tokenx.order.mapper.SeckillRequestMapper;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.order.timeout.OrderTimeoutService;
import com.tokenx.wallet.mapper.TokenLedgerMapper;
import com.tokenx.wallet.model.LedgerBizType;
import com.tokenx.wallet.model.LedgerEffectType;
import com.tokenx.wallet.model.TokenLedger;
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
 * Shared setup for the order, payment and wallet tests: real MySQL, a controllable clock, genuine
 * logins, and cleanup that removes only the rows a test created.
 *
 * <p>The default activity window is already <em>open</em> around {@link #BASE}, because most tests
 * are about what happens once an activity accepts orders. Window edges are tested by moving the
 * clock or by building an activity with an explicit window, never by sleeping.
 *
 * <p>No foreign keys exist by design, so cleanup deletes the child rows explicitly, in the reverse
 * order of creation. Ledger rows go first: they are the only rows that point at an order by value
 * rather than by a constraint, so leaving them behind would let a later test see someone else's
 * posting.
 *
 * <p>Public so the payment and wallet test packages can use the same fixtures instead of growing a
 * second, subtly different copy of "create a buyer, publish an activity, place an order".
 */
@Import(TestClockConfig.class)
public abstract class AbstractOrderApiTest extends AbstractIntegrationTest {

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
  @Autowired protected SeckillRequestMapper seckillRequestMapper;
  @Autowired protected TokenLedgerMapper ledgerMapper;
  @Autowired protected OrderTimeoutService timeoutService;
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
    // Wallet postings first: an order must not be deleted while a posting still names its id.
    for (Long buyerId : buyerIds) {
      ledgerMapper.delete(Wrappers.<TokenLedger>lambdaQuery().eq(TokenLedger::getUserId, buyerId));
    }

    for (Long activityId : activityIds) {
      seckillRequestMapper.delete(
          Wrappers.<com.tokenx.order.model.SeckillRequestRow>lambdaQuery()
              .eq(com.tokenx.order.model.SeckillRequestRow::getActivityId, activityId));
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
    return packageRow(creditAmount, creditAmount / 2 + 1, PackageStatus.ON_SALE);
  }

  /** A package with an explicit list price and status, so a test can control what is snapshotted. */
  protected long packageRow(long creditAmount, long listPriceCent, PackageStatus status) {
    TokenPackage tokenPackage = new TokenPackage();
    tokenPackage.setName("下单套餐-" + UUID.randomUUID().toString().substring(0, 8));
    tokenPackage.setCreditAmount(creditAmount);
    tokenPackage.setListPriceCent(listPriceCent);
    tokenPackage.setStatus(status);
    packageMapper.insert(tokenPackage);
    packageIds.add(tokenPackage.getId());
    return tokenPackage.getId();
  }

  /** Withdraws a package through the admin endpoint, the way an operator would. */
  protected ResultActions withdrawPackage(String adminToken, long packageId) throws Exception {
    return mockMvc.perform(
        patch("/api/v1/admin/products/" + packageId + "/status")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("status", "OFF_SHELF"))));
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

  /** Normal purchase of one package, with the documented body. */
  protected ResultActions placeNormalOrder(String token, long packageId, String idempotencyKey)
      throws Exception {
    return placeNormalOrder(token, packageId, idempotencyKey, Map.of("packageId", packageId));
  }

  /** Normal purchase with a body a client might try to tamper with; only {@code packageId} counts. */
  protected ResultActions placeNormalOrder(
      String token, long packageId, String idempotencyKey, Map<String, Object> body)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/orders")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
  }

  /** Normal purchase with a body written verbatim, for bodies that cannot be parsed. */
  protected ResultActions placeNormalOrderRaw(
      String token, String idempotencyKey, String rawBody) throws Exception {
    return mockMvc.perform(
        post("/api/v1/orders")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(rawBody));
  }

  protected static String newKey() {
    return UUID.randomUUID().toString();
  }

  // --- payment and wallet requests -------------------------------------------------------------

  /** Confirms a mock payment. No body: the server reads the price and the credits from the order. */
  protected ResultActions mockPay(String token, String orderNo) throws Exception {
    return mockMvc.perform(
        post("/api/v1/orders/" + orderNo + "/mock-pay").header("Authorization", "Bearer " + token));
  }

  /** The same call with a body a client might try to tamper with; none of it may be honoured. */
  protected ResultActions mockPay(String token, String orderNo, Map<String, Object> body)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/orders/" + orderNo + "/mock-pay")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
  }

  protected ResultActions getWallet(String token) throws Exception {
    return mockMvc.perform(get("/api/v1/wallet").header("Authorization", "Bearer " + token));
  }

  protected ResultActions getLedger(String token, String query) throws Exception {
    return mockMvc.perform(
        get("/api/v1/wallet/ledger" + query).header("Authorization", "Bearer " + token));
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

  /** Every order one buyer owns, whichever channel created it. */
  protected List<PurchaseOrder> storedOrdersOfUser(long userId) {
    return orderMapper.selectList(
        Wrappers.<PurchaseOrder>lambdaQuery().eq(PurchaseOrder::getUserId, userId));
  }

  /** The package as the database now holds it, for asserting that ordering changed nothing. */
  protected TokenPackage storedPackage(long packageId) {
    return packageMapper.selectById(packageId);
  }

  /** The account row as the database holds it; {@code null} only if the invariant is broken. */
  protected TokenAccount storedAccount(long userId) {
    return accountMapper.selectById(userId);
  }

  protected long storedBalance(long userId) {
    TokenAccount account = storedAccount(userId);
    return account == null ? -1L : account.getAvailableBalance();
  }

  /** Every posting one user owns, newest first, exactly as the ledger stores them. */
  protected List<TokenLedger> storedLedgerOf(long userId) {
    return ledgerMapper.selectList(
        Wrappers.<TokenLedger>lambdaQuery()
            .eq(TokenLedger::getUserId, userId)
            .orderByDesc(TokenLedger::getId));
  }

  /** The single posting an order produced, or {@code null} if payment posted nothing. */
  protected TokenLedger storedLedgerOfOrder(long orderId) {
    return ledgerMapper.selectOne(
        Wrappers.<TokenLedger>lambdaQuery()
            .eq(TokenLedger::getBizType, LedgerBizType.ORDER)
            .eq(TokenLedger::getBizId, orderId)
            .eq(TokenLedger::getEffectType, LedgerEffectType.CREDIT));
  }

  /** The order row behind a number owned by this user, for asserting the stored state. */
  protected PurchaseOrder storedOrder(String orderNo, long userId) {
    return orderMapper.selectByOrderNoAndUser(orderNo, userId);
  }

  /**
   * Breaks the invariant a closure depends on: an order claims a reservation the activity no longer
   * holds. No normal path can produce this, so tests that need a corrupt row write it here.
   */
  protected void drainReservations(long activityId) {
    ActivityInventory broken = storedInventory(activityId);
    broken.setReservedStock(0);
    inventoryMapper.updateById(broken);
    assertThat(storedInventory(activityId).getReservedStock()).isZero();
  }

  /**
   * The deadline an order froze at creation time.
   *
   * <p>Tests move the clock to this instant instead of waiting, so "expired" is a decision about the
   * clock rather than about how long the suite has been running.
   */
  protected Instant expiresAtOf(String orderNo, long userId) {
    return storedOrder(orderNo, userId).getExpiresInstant();
  }

  protected String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }
}
