package com.tokenx.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.databind.ObjectMapper;
import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.support.AbstractIntegrationTest;
import com.tokenx.support.JwtTestFactory;
import com.tokenx.support.TestAccounts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Token verification rules: what must be rejected, what may pass, and which fields come back. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthJwtTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AppUserMapper userMapper;
  @Autowired private TokenAccountMapper accountMapper;

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
  void shouldRejectRequestWithoutToken() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.requestId").isNotEmpty());
  }

  @Test
  void shouldRejectMalformedToken() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void shouldRejectTokenSignedWithAnotherKey() throws Exception {
    String forged =
        JwtTestFactory.token(
            "1",
            "intruder",
            "ADMIN",
            Instant.now(),
            Instant.now().plusSeconds(600),
            JwtTestFactory.randomSecret());

    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + forged))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldRejectExpiredToken() throws Exception {
    String expired =
        JwtTestFactory.token(
            "1", "someone", "USER", Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));

    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + expired))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldAcceptValidTokenAndExposeOnlyPublicUserFields() throws Exception {
    String username = TestAccounts.uniqueUsername("jwt");
    createdUsernames.add(username);
    String password = TestAccounts.password();
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("username", username, "password", password))))
        .andExpect(status().isCreated());

    MvcResult login =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of("username", username, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
    String token =
        objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();

    MvcResult me =
        mockMvc
            .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value(username))
            .andExpect(jsonPath("$.role").value("USER"))
            .andReturn();

    String body = me.getResponse().getContentAsString();
    assertThat(body).doesNotContain("password").doesNotContain("passwordHash");
    assertThat(objectMapper.readTree(body).size()).isEqualTo(3);
  }

  @Test
  void shouldNotTrustAUserIdSentByTheClient() throws Exception {
    String username = TestAccounts.uniqueUsername("spoof");
    createdUsernames.add(username);
    String password = TestAccounts.password();
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("username", username, "password", password))))
        .andExpect(status().isCreated());

    MvcResult login =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of("username", username, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
    String token =
        objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    Long realId =
        userMapper
            .selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username))
            .getId();

    mockMvc
        .perform(
            get("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .param("userId", String.valueOf(realId + 999)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(realId.intValue()));
  }
}
