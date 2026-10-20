package com.tokenx.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.databind.ObjectMapper;
import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.auth.model.TokenAccount;
import com.tokenx.auth.model.UserRole;
import com.tokenx.auth.model.UserStatus;
import com.tokenx.support.AbstractIntegrationTest;
import com.tokenx.support.TestAccounts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;

/** End-to-end API behaviour of registration, login and the current-user endpoint on real MySQL. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AppUserMapper userMapper;
  @Autowired private TokenAccountMapper accountMapper;
  @Autowired private PasswordEncoder passwordEncoder;

  private final List<String> createdUsernames = new ArrayList<>();

  @AfterEach
  void deleteCreatedRows() {
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

  @Test
  void shouldRegisterUserAndReturnOnlyPublicFields() throws Exception {
    String username = givenUsername("reg");
    String password = TestAccounts.password();

    mockMvc
        .perform(registerRequest(username, password))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNumber())
        .andExpect(jsonPath("$.username").value(username))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(jsonPath("$.*", hasSize(3)));

    AppUser stored = findByUsername(username);
    assertThat(stored).isNotNull();
    assertThat(stored.getRole()).isEqualTo(UserRole.USER);
    assertThat(stored.getStatus()).isEqualTo(UserStatus.ACTIVE);
  }

  @Test
  void shouldStorePasswordAsBcryptDigestVerifiedByTheCorrectPassword() throws Exception {
    String username = givenUsername("hash");
    String password = TestAccounts.password();

    mockMvc.perform(registerRequest(username, password)).andExpect(status().isCreated());

    String storedHash = findByUsername(username).getPasswordHash();
    assertThat(storedHash).isNotEqualTo(password).doesNotContain(password).startsWith("$2");
    assertThat(passwordEncoder.matches(password, storedHash))
        .as("the stored digest must verify the password that was sent")
        .isTrue();
    assertThat(passwordEncoder.matches(password + "-wrong", storedHash)).isFalse();
  }

  @Test
  void shouldRejectDuplicateUsername() throws Exception {
    String username = givenUsername("dup");
    String password = TestAccounts.password();

    mockMvc.perform(registerRequest(username, password)).andExpect(status().isCreated());

    mockMvc
        .perform(registerRequest(username, TestAccounts.password()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"))
        .andExpect(jsonPath("$.requestId").isNotEmpty());

    assertThat(countUsers(username)).isEqualTo(1);
  }

  @Test
  void shouldRejectUsernameThatDiffersOnlyByCase() throws Exception {
    String username = "Alice" + TestAccounts.shortSuffix();
    String password = TestAccounts.password();

    mockMvc.perform(registerRequest(username, password)).andExpect(status().isCreated());

    mockMvc
        .perform(registerRequest(username.toLowerCase(), TestAccounts.password()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"));
  }

  @Test
  void shouldCreateExactlyOneZeroBalanceAccountPerRegisteredUser() throws Exception {
    String username = givenUsername("acct");

    mockMvc.perform(registerRequest(username, TestAccounts.password())).andExpect(status().isCreated());

    Long userId = findByUsername(username).getId();
    List<TokenAccount> accounts =
        accountMapper.selectList(
            Wrappers.<TokenAccount>lambdaQuery().eq(TokenAccount::getUserId, userId));

    assertThat(accounts).hasSize(1);
    assertThat(accounts.get(0).getAvailableBalance()).isZero();
  }

  @Test
  void shouldIgnoreClientSuppliedRoleAndStatusOnRegistration() throws Exception {
    String username = givenUsername("role");
    Map<String, String> body = new LinkedHashMap<>();
    body.put("username", username);
    body.put("password", TestAccounts.password());
    body.put("role", "ADMIN");
    body.put("status", "DISABLED");

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("USER"));

    AppUser stored = findByUsername(username);
    assertThat(stored.getRole()).as("public registration can never grant admin").isEqualTo(UserRole.USER);
    assertThat(stored.getStatus()).isEqualTo(UserStatus.ACTIVE);
  }

  @Test
  void shouldRejectInvalidRegistrationPayload() throws Exception {
    mockMvc
        .perform(registerRequest("ab", TestAccounts.password()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    mockMvc
        .perform(registerRequest("has-dash", TestAccounts.password()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    mockMvc
        .perform(registerRequest(givenUsername("short"), "short"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void shouldLoginWithCorrectPasswordAndReadCurrentUser() throws Exception {
    String username = givenUsername("login");
    String password = TestAccounts.password();
    mockMvc.perform(registerRequest(username, password)).andExpect(status().isCreated());

    String token = login(username, password);

    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(findByUsername(username).getId().intValue()))
        .andExpect(jsonPath("$.username").value(username))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(jsonPath("$.*", hasSize(3)));
  }

  @Test
  void shouldFailWrongPasswordAndUnknownUserWithTheSameError() throws Exception {
    String username = givenUsername("cred");
    String password = TestAccounts.password();
    mockMvc.perform(registerRequest(username, password)).andExpect(status().isCreated());

    MvcResult wrongPassword =
        mockMvc
            .perform(loginRequest(username, password + "-wrong"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andReturn();

    MvcResult unknownUser =
        mockMvc
            .perform(loginRequest(givenUsername("ghost"), password))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andReturn();

    String wrongBody = wrongPassword.getResponse().getContentAsString();
    String unknownBody = unknownUser.getResponse().getContentAsString();
    assertThat(stripRequestId(wrongBody))
        .as("a failed login must not reveal whether the account exists")
        .isEqualTo(stripRequestId(unknownBody));
    assertThat(wrongBody).doesNotContain("accessToken");
  }

  @Test
  void shouldRefuseToIssueTokenForDisabledUser() throws Exception {
    String username = givenUsername("disabled");
    String password = TestAccounts.password();
    mockMvc.perform(registerRequest(username, password)).andExpect(status().isCreated());

    AppUser user = findByUsername(username);
    user.setStatus(UserStatus.DISABLED);
    userMapper.updateById(user);

    mockMvc
        .perform(loginRequest(username, password))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"))
        .andExpect(jsonPath("$.accessToken").doesNotExist());
  }

  private String givenUsername(String prefix) {
    String username = TestAccounts.uniqueUsername(prefix);
    createdUsernames.add(username);
    return username;
  }

  private MockHttpServletRequestBuilder registerRequest(
      String username, String password) throws Exception {
    return post("/api/v1/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(credentialsJson(username, password));
  }

  private MockHttpServletRequestBuilder loginRequest(
      String username, String password) throws Exception {
    return post("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content(credentialsJson(username, password));
  }

  private String credentialsJson(String username, String password) throws Exception {
    return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
  }

  private String login(String username, String password) throws Exception {
    MvcResult result =
        mockMvc
            .perform(loginRequest(username, password))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresInSeconds").isNumber())
            .andReturn();
    String token =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    assertThat(token).isNotBlank();
    return token;
  }

  private AppUser findByUsername(String username) {
    return userMapper.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username));
  }

  private long countUsers(String username) {
    return userMapper.selectCount(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username));
  }

  private static String stripRequestId(String json) {
    return json.replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"<id>\"");
  }
}
