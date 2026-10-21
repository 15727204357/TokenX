package com.tokenx.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.support.AbstractIntegrationTest;
import com.tokenx.support.TestAccounts;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * BCrypt's password limit is 72 <em>bytes</em>, not 72 Java characters. These tests pin the boundary
 * from the HTTP side: the value must be rejected with a stable 400 before it can reach the encoder,
 * where it would surface as an unhandled 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordByteLengthTest extends AbstractIntegrationTest {

  /** 24 CJK characters = 72 UTF-8 bytes; still only 24 characters. */
  private static final String SEVENTY_TWO_BYTES_MULTIBYTE = "密".repeat(24);

  /** 25 CJK characters = 75 UTF-8 bytes. */
  private static final String SEVENTY_FIVE_BYTES_MULTIBYTE = "密".repeat(25);

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
  void shouldAcceptPasswordOfExactly72AsciiBytes() throws Exception {
    String username = givenUsername("bytes72");
    String password = "a".repeat(72);

    mockMvc.perform(register(username, password)).andExpect(status().isCreated());
    assertThat(loginToken(username, password)).isNotBlank();
  }

  @Test
  void shouldAcceptPasswordOfExactly72BytesWrittenWithMultiByteCharacters() throws Exception {
    String username = givenUsername("mb72");

    mockMvc
        .perform(register(username, SEVENTY_TWO_BYTES_MULTIBYTE))
        .andExpect(status().isCreated());
    assertThat(loginToken(username, SEVENTY_TWO_BYTES_MULTIBYTE)).isNotBlank();
  }

  @Test
  void shouldRejectMultiBytePasswordOver72BytesWith400() throws Exception {
    String username = givenUsername("mb75");

    mockMvc
        .perform(register(username, SEVENTY_FIVE_BYTES_MULTIBYTE))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("password must be at most 72 bytes in UTF-8"));

    assertThat(countUsers(username)).as("a rejected registration must not create a user").isZero();
  }

  @Test
  void shouldRejectAsciiPasswordLongerThan72BytesWith400() throws Exception {
    String username = givenUsername("ascii73");

    mockMvc
        .perform(register(username, "a".repeat(73)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(countUsers(username)).isZero();
  }

  @Test
  void shouldRejectOverlongPasswordOnLoginWith400InsteadOfServerError() throws Exception {
    String username = givenUsername("loginbytes");
    String password = TestAccounts.password();
    mockMvc.perform(register(username, password)).andExpect(status().isCreated());

    mockMvc
        .perform(login(username, SEVENTY_FIVE_BYTES_MULTIBYTE))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.accessToken").doesNotExist());
  }

  @Test
  void shouldKeepAcceptingOrdinaryPasswords() throws Exception {
    String username = givenUsername("ordinary");
    String password = "Passw0rd!23";

    mockMvc.perform(register(username, password)).andExpect(status().isCreated());
    assertThat(loginToken(username, password)).isNotBlank();
  }

  private String givenUsername(String prefix) {
    String username = TestAccounts.uniqueUsername(prefix);
    createdUsernames.add(username);
    return username;
  }

  private MockHttpServletRequestBuilder register(
      String username, String password) throws Exception {
    return post("/api/v1/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(credentials(username, password));
  }

  private MockHttpServletRequestBuilder login(
      String username, String password) throws Exception {
    return post("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content(credentials(username, password));
  }

  private String loginToken(String username, String password) throws Exception {
    MvcResult result =
        mockMvc
            .perform(login(username, password))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andReturn();
    return objectMapper
        .readTree(result.getResponse().getContentAsString())
        .get("accessToken")
        .asText();
  }

  private String credentials(String username, String password) throws Exception {
    return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
  }

  private long countUsers(String username) {
    return userMapper.selectCount(
        Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username));
  }
}
