package com.tokenx.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.databind.ObjectMapper;
import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.support.AbstractIntegrationTest;
import com.tokenx.support.SqlStatementCounter;
import com.tokenx.support.TestAccounts;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Guards the performance contract of authentication: a request carrying a valid token is answered
 * from the verified claims, so no statement reaches the database.
 *
 * <p>The counter is first proven to work on a login request (which really does query {@code
 * app_user}); otherwise a zero count would prove nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthHotPathTest.CounterConfig.class)
class AuthHotPathTest extends AbstractIntegrationTest {

  @TestConfiguration
  static class CounterConfig {
    @Bean
    SqlStatementCounter sqlStatementCounter() {
      return new SqlStatementCounter();
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private SqlStatementCounter counter;
  @Autowired private AppUserMapper userMapper;
  @Autowired private TokenAccountMapper accountMapper;

  private String username;

  @AfterEach
  void deleteCreatedRows() {
    if (username == null) {
      return;
    }
    AppUser user =
        userMapper.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username));
    if (user != null) {
      accountMapper.deleteById(user.getId());
      userMapper.deleteById(user.getId());
    }
  }

  @Test
  void shouldServeAuthenticatedRequestsWithoutQueryingTheUserTable() throws Exception {
    username = TestAccounts.uniqueUsername("hot");
    String password = TestAccounts.password();
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(username, password)))
        .andExpect(status().isCreated());

    counter.reset();
    MvcResult login =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(username, password)))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(counter.count())
        .as("login must read app_user; otherwise the counter is not wired and the next assert is void")
        .isPositive();

    String token =
        objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();

    counter.reset();
    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
          .andExpect(status().isOk());
    }

    assertThat(counter.count())
        .as("authenticated requests must be answered from the verified token, not from a query")
        .isZero();
  }

  private String json(String username, String password) throws Exception {
    return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
  }
}
