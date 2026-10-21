package com.tokenx.activity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.activity.mapper.ActivityInventoryMapper;
import com.tokenx.activity.mapper.SeckillActivityMapper;
import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.activity.model.SeckillActivity;
import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.catalog.mapper.TokenPackageMapper;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.catalog.model.TokenPackage;
import com.tokenx.support.AbstractIntegrationTest;
import com.tokenx.support.TestAdmins;
import com.tokenx.support.TestClockConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared setup for the activity tests: real MySQL, a controllable clock, genuine admin logins and
 * cleanup that only removes rows these tests created.
 *
 * <p>The clock is fixed at {@link TestClockConfig#BASE}, so "in one hour" always means the same
 * instant and a boundary assertion can be exact instead of approximate.
 */
@Import(TestClockConfig.class)
abstract class AbstractActivityApiTest extends AbstractIntegrationTest {

  protected static final Instant BASE = TestClockConfig.BASE;
  protected static final Instant START = BASE.plus(Duration.ofHours(1));
  protected static final Instant END = BASE.plus(Duration.ofHours(2));

  @Autowired protected MockMvc mockMvc;
  @Autowired protected ObjectMapper objectMapper;
  @Autowired protected AppUserMapper userMapper;
  @Autowired protected TokenAccountMapper accountMapper;
  @Autowired protected TokenPackageMapper packageMapper;
  @Autowired protected SeckillActivityMapper activityMapper;
  @Autowired protected ActivityInventoryMapper inventoryMapper;
  @Autowired protected PasswordEncoder passwordEncoder;

  private final List<Long> activityIds = new ArrayList<>();
  private final List<Long> packageIds = new ArrayList<>();
  private final List<Long> adminIds = new ArrayList<>();
  private final List<String> registeredUsernames = new ArrayList<>();

  @BeforeEach
  void resetClock() {
    TestClockConfig.reset();
  }

  @AfterEach
  void deleteRowsCreatedByThisTest() {
    // No foreign keys by design, so the order is a courtesy rather than a requirement.
    for (Long activityId : activityIds) {
      inventoryMapper.deleteById(activityId);
      activityMapper.deleteById(activityId);
    }
    activityIds.clear();

    for (Long packageId : packageIds) {
      packageMapper.deleteById(packageId);
    }
    packageIds.clear();

    // Administrators are inserted directly and own no token_account row.
    for (Long adminId : adminIds) {
      userMapper.deleteById(adminId);
    }
    adminIds.clear();

    // Users registered through the API own a zero-balance account created in the same transaction.
    for (String username : registeredUsernames) {
      AppUser user =
          userMapper.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username));
      if (user != null) {
        accountMapper.deleteById(user.getId());
        userMapper.deleteById(user.getId());
      }
    }
    registeredUsernames.clear();
  }

  /** Real admin login, so the role claim travels the production code path. */
  protected String adminToken() throws Exception {
    TestAdmins.Admin admin = TestAdmins.create(userMapper, passwordEncoder);
    adminIds.add(admin.id());
    return login(admin.username(), admin.password());
  }

  /** Registers an ordinary user through the public API and logs it in. */
  protected String userToken() throws Exception {
    String username = "buyer" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String password = "Passw0rd-" + username.substring(5);
    registeredUsernames.add(username);

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("username", username, "password", password))))
        .andExpect(status().isCreated());

    return login(username, password);
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

  /** Inserts a package row directly: these tests are about activities, not about the catalogue API. */
  protected long packageRow(long creditAmount, PackageStatus status) {
    TokenPackage tokenPackage = new TokenPackage();
    tokenPackage.setName("活动套餐-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    tokenPackage.setCreditAmount(creditAmount);
    tokenPackage.setListPriceCent(creditAmount / 2 + 1);
    tokenPackage.setStatus(status);
    packageMapper.insert(tokenPackage);
    packageIds.add(tokenPackage.getId());
    return tokenPackage.getId();
  }

  protected long onSalePackage(long creditAmount) {
    return packageRow(creditAmount, PackageStatus.ON_SALE);
  }

  protected Map<String, Object> activityBody(
      long packageId, long activityPriceCent, long totalStock, Instant startAt, Instant endAt) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("packageId", packageId);
    body.put("activityPriceCent", activityPriceCent);
    body.put("totalStock", totalStock);
    body.put("startAt", startAt.toString());
    body.put("endAt", endAt.toString());
    return body;
  }

  protected ResultActions postActivity(String token, Map<String, Object> body) throws Exception {
    return mockMvc.perform(
        post("/api/v1/admin/activities")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
  }

  /** Creates a draft activity with the default window and remembers it for cleanup. */
  protected long createActivity(String token, Map<String, Object> body) throws Exception {
    return track(postActivity(token, body).andExpect(status().isCreated()).andReturn());
  }

  protected long createDraft(String token, long packageId, long priceCent, long totalStock)
      throws Exception {
    return createActivity(token, activityBody(packageId, priceCent, totalStock, START, END));
  }

  /** Creates and publishes, which is what a visitor can actually see. */
  protected long publishedActivity(String token, long packageId, long priceCent, long totalStock)
      throws Exception {
    long id = createDraft(token, packageId, priceCent, totalStock);
    changeStatus(token, id, "PUBLISHED").andExpect(status().isOk());
    return id;
  }

  protected ResultActions changeStatus(String token, long activityId, String status)
      throws Exception {
    return mockMvc.perform(
        patch("/api/v1/admin/activities/" + activityId + "/status")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("status", status))));
  }

  protected long track(MvcResult result) throws Exception {
    long id = objectMapper.readTree(body(result)).get("id").asLong();
    activityIds.add(id);
    return id;
  }

  protected void trackActivity(long id) {
    activityIds.add(id);
  }

  protected SeckillActivity storedActivity(long id) {
    return activityMapper.selectById(id);
  }

  protected ActivityInventory storedInventory(long id) {
    return inventoryMapper.selectById(id);
  }

  /** Reads a response as UTF-8. */
  protected String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }
}
