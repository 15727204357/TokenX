package com.tokenx.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.catalog.mapper.TokenPackageMapper;
import com.tokenx.support.AbstractIntegrationTest;
import com.tokenx.support.TestAccounts;
import com.tokenx.support.TestAdmins;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the catalogue contract over a real servlet container and real HTTP, including that an
 * anonymous visitor really can read it. Complements the MockMvc tests instead of mocking the
 * transport.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogHttpSmokeTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private ObjectMapper objectMapper;
  @Autowired private AppUserMapper userMapper;
  @Autowired private TokenAccountMapper accountMapper;
  @Autowired private TokenPackageMapper packageMapper;
  @Autowired private PasswordEncoder passwordEncoder;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private final List<Long> createdPackageIds = new ArrayList<>();
  private Long adminId;
  private String regularUsername;

  @AfterEach
  void deleteCreatedRows() {
    for (Long id : createdPackageIds) {
      packageMapper.deleteById(id);
    }
    createdPackageIds.clear();

    if (adminId != null) {
      userMapper.deleteById(adminId);
      adminId = null;
    }
    if (regularUsername != null) {
      AppUser user =
          userMapper.selectOne(
              Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, regularUsername));
      if (user != null) {
        accountMapper.deleteById(user.getId());
        userMapper.deleteById(user.getId());
      }
      regularUsername = null;
    }
  }

  @Test
  void shouldServePublishedPackageToAnonymousVisitorsOverHttp() throws Exception {
    String adminToken = adminToken();

    HttpResponse<String> created =
        send(
            "POST",
            "/api/v1/admin/products",
            "{\"name\":\"HTTP 套餐\",\"creditAmount\":2000,\"listPriceCent\":1980}",
            adminToken);
    assertThat(created.statusCode()).isEqualTo(201);
    long id = objectMapper.readTree(created.body()).get("id").asLong();
    createdPackageIds.add(id);

    HttpResponse<String> published =
        send("PATCH", "/api/v1/admin/products/" + id + "/status", "{\"status\":\"ON_SALE\"}", adminToken);
    assertThat(published.statusCode()).isEqualTo(200);

    HttpResponse<String> list = send("GET", "/api/v1/products?limit=100", null, null);
    assertThat(list.statusCode()).as("visitors read the list without a token").isEqualTo(200);
    assertThat(list.body()).contains("\"id\":" + id);

    HttpResponse<String> detail = send("GET", "/api/v1/products/" + id, null, null);
    assertThat(detail.statusCode()).isEqualTo(200);
    JsonNode body = objectMapper.readTree(detail.body());
    assertThat(body.get("name").asText()).isEqualTo("HTTP 套餐");
    assertThat(body.get("status").asText()).isEqualTo("ON_SALE");
  }

  @Test
  void shouldRequireAdminRightsOverHttp() throws Exception {
    HttpResponse<String> anonymous =
        send("POST", "/api/v1/admin/products", "{\"name\":\"匿名\",\"creditAmount\":1,\"listPriceCent\":1}", null);
    assertThat(anonymous.statusCode()).isEqualTo(401);
    assertThat(anonymous.body()).contains("UNAUTHORIZED");

    regularUsername = TestAccounts.uniqueUsername("plain");
    String password = TestAccounts.password();
    String credentials =
        objectMapper.writeValueAsString(Map.of("username", regularUsername, "password", password));
    assertThat(send("POST", "/api/v1/auth/register", credentials, null).statusCode())
        .isEqualTo(201);
    String userToken = objectMapper.readTree(send("POST", "/api/v1/auth/login", credentials, null).body())
        .get("accessToken")
        .asText();

    HttpResponse<String> forbidden =
        send(
            "POST",
            "/api/v1/admin/products",
            "{\"name\":\"越权\",\"creditAmount\":1,\"listPriceCent\":1}",
            userToken);
    assertThat(forbidden.statusCode()).isEqualTo(403);
    assertThat(forbidden.body()).contains("FORBIDDEN");
  }

  private String adminToken() throws Exception {
    TestAdmins.Admin admin = TestAdmins.create(userMapper, passwordEncoder);
    adminId = admin.id();
    String credentials =
        objectMapper.writeValueAsString(
            Map.of("username", admin.username(), "password", admin.password()));
    HttpResponse<String> loggedIn = send("POST", "/api/v1/auth/login", credentials, null);
    assertThat(loggedIn.statusCode()).isEqualTo(200);
    return objectMapper.readTree(loggedIn.body()).get("accessToken").asText();
  }

  private HttpResponse<String> send(String method, String path, String body, String token)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri(path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json");
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    HttpRequest request =
        body == null
            ? builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
            : builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }
}
