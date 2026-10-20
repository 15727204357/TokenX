package com.tokenx.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.databind.ObjectMapper;
import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.auth.model.TokenAccount;
import com.tokenx.support.AbstractIntegrationTest;
import com.tokenx.support.TestAccounts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Proves that the unique index, not an application pre-check, decides concurrent registrations: one
 * request wins and every loser gets the stable 409 business error instead of an unhandled 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthConcurrencyTest extends AbstractIntegrationTest {

  private static final int CONCURRENT_REQUESTS = 6;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
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
  void shouldLetExactlyOneConcurrentRegistrationWinAndReturn409ForTheRest() throws Exception {
    username = TestAccounts.uniqueUsername("race");
    String body =
        objectMapper.writeValueAsString(
            Map.of("username", username, "password", TestAccounts.password()));

    CountDownLatch startLine = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
    try {
      List<Future<Integer>> responses = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        responses.add(
            pool.submit(
                () -> {
                  startLine.await();
                  MvcResult result =
                      mockMvc
                          .perform(
                              post("/api/v1/auth/register")
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content(body))
                          .andReturn();
                  return result.getResponse().getStatus();
                }));
      }
      startLine.countDown();

      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> response : responses) {
        statuses.add(response.get(30, TimeUnit.SECONDS));
      }

      assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
      assertThat(statuses).filteredOn(status -> status == 409).hasSize(CONCURRENT_REQUESTS - 1);
      assertThat(statuses).as("a duplicate key must never surface as a 500").doesNotContain(500);
    } finally {
      pool.shutdownNow();
    }

    assertThat(
            userMapper.selectCount(
                Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username)))
        .isEqualTo(1);
    Long userId =
        userMapper.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username)).getId();
    assertThat(
            accountMapper.selectCount(
                Wrappers.<TokenAccount>lambdaQuery().eq(TokenAccount::getUserId, userId)))
        .isEqualTo(1);
  }
}
