package com.tokenx.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.support.AbstractIntegrationTest;
import com.tokenx.support.TestAccounts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the F1 contract over a real servlet container and real HTTP, which is what the React login
 * page consumes. Complements the MockMvc tests instead of mocking the transport.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthHttpSmokeTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private ObjectMapper objectMapper;
  @Autowired private AppUserMapper userMapper;
  @Autowired private TokenAccountMapper accountMapper;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

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
  void shouldRegisterLoginAndReadCurrentUserOverHttp() throws Exception {
    username = TestAccounts.uniqueUsername("http");
    String password = TestAccounts.password();
    String body = objectMapper.writeValueAsString(Map.of("username", username, "password", password));

    HttpResponse<String> registered = post("/api/v1/auth/register", body);
    assertThat(registered.statusCode()).isEqualTo(201);

    HttpResponse<String> loggedIn = post("/api/v1/auth/login", body);
    assertThat(loggedIn.statusCode()).isEqualTo(200);
    String token =
        objectMapper.readTree(loggedIn.body()).get("accessToken").asText();
    assertThat(token).isNotBlank();

    HttpRequest meRequest =
        HttpRequest.newBuilder(uri("/api/v1/users/me"))
            .header("Authorization", "Bearer " + token)
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    HttpResponse<String> me = client.send(meRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(me.statusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(me.body()).get("username").asText()).isEqualTo(username);
  }

  @Test
  void shouldRejectAnonymousAccessOverHttp() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri("/api/v1/users/me")).timeout(Duration.ofSeconds(5)).GET().build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("UNAUTHORIZED");
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }
}
