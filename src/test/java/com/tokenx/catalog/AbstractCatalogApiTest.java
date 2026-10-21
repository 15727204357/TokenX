package com.tokenx.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.catalog.mapper.TokenPackageMapper;
import com.tokenx.support.AbstractIntegrationTest;
import com.tokenx.support.TestAccounts;
import com.tokenx.support.TestAdmins;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared setup for the catalogue API tests: real MySQL, genuine logins, and cleanup that only
 * removes rows these tests created.
 */
abstract class AbstractCatalogApiTest extends AbstractIntegrationTest {

  @Autowired protected MockMvc mockMvc;
  @Autowired protected ObjectMapper objectMapper;
  @Autowired protected AppUserMapper userMapper;
  @Autowired protected TokenAccountMapper accountMapper;
  @Autowired protected TokenPackageMapper packageMapper;
  @Autowired protected PasswordEncoder passwordEncoder;

  private final List<Long> createdPackageIds = new ArrayList<>();
  private final List<Long> createdAdminIds = new ArrayList<>();
  private final List<String> createdUsernames = new ArrayList<>();

  @AfterEach
  void deleteRowsCreatedByThisTest() {
    for (Long packageId : createdPackageIds) {
      packageMapper.deleteById(packageId);
    }
    createdPackageIds.clear();

    // Administrators are inserted directly, so they own no token_account row.
    for (Long adminId : createdAdminIds) {
      userMapper.deleteById(adminId);
    }
    createdAdminIds.clear();

    for (String username : createdUsernames) {
      AppUser user =
          userMapper.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username));
      if (user != null) {
        accountMapper.deleteById(user.getId());
        userMapper.deleteById(user.getId());
      }
    }
    createdUsernames.clear();
  }

  /** Real admin login, so the role claim travels the production code path. */
  protected String adminToken() throws Exception {
    TestAdmins.Admin admin = TestAdmins.create(userMapper, passwordEncoder);
    createdAdminIds.add(admin.id());
    return login(admin.username(), admin.password());
  }

  /** Registers an ordinary user through the public API and logs it in. */
  protected String userToken() throws Exception {
    String username = TestAccounts.uniqueUsername("buyer");
    String password = TestAccounts.password();
    createdUsernames.add(username);

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

  /** Creates an off-shelf package and remembers its id for cleanup. */
  protected long createPackage(String adminToken, String name, long creditAmount, long listPriceCent)
      throws Exception {
    return createPackage(adminToken, createBody(name, creditAmount, listPriceCent));
  }

  protected long createPackage(String adminToken, Map<String, Object> body) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/admin/products")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    return trackPackage(result);
  }

  protected long createPublishedPackage(String adminToken, String name, long creditAmount,
      long listPriceCent) throws Exception {
    long id = createPackage(adminToken, name, creditAmount, listPriceCent);
    patchStatus(adminToken, id, "ON_SALE").andExpect(status().isOk());
    return id;
  }

  protected ResultActions patchStatus(String token, long packageId, String status)
      throws Exception {
    return mockMvc.perform(
        patch("/api/v1/admin/products/" + packageId + "/status")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("status", status))));
  }

  protected Map<String, Object> createBody(String name, Long creditAmount, Long listPriceCent) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("creditAmount", creditAmount);
    body.put("listPriceCent", listPriceCent);
    return body;
  }

  protected long trackPackage(MvcResult result) throws Exception {
    long id = objectMapper.readTree(body(result)).get("id").asLong();
    createdPackageIds.add(id);
    return id;
  }

  /** Reads a response as UTF-8: package names may be non-ASCII. */
  protected String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }
}
