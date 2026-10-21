package com.tokenx.activity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/**
 * Access rules around the activity endpoints.
 *
 * <p>The interesting case is the boundary: activity detail is public, but only for {@code GET}. F4
 * will add {@code POST /activities/{id}/orders} on the very same path prefix, and it must not inherit
 * anonymous access — so the security rule is scoped to the HTTP method and this test pins that down
 * before the endpoint even exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActivitySecurityTest extends AbstractActivityApiTest {

  @Test
  void shouldServeTheDetailToAnonymousVisitors() throws Exception {
    long id = publishedActivity(adminToken(), onSalePackage(100L), 100L, 5L);

    mockMvc
        .perform(get("/api/v1/activities/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
  }

  @Test
  void shouldNotOpenNonGetPathsUnderActivities() throws Exception {
    long id = publishedActivity(adminToken(), onSalePackage(100L), 100L, 5L);

    // The F4 ordering path does not exist yet: an anonymous caller is rejected by authentication
    // rather than being let through to a handler.
    mockMvc
        .perform(post("/api/v1/activities/" + id + "/orders"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    mockMvc
        .perform(delete("/api/v1/activities/" + id))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void shouldStillRequireAuthenticationForAnAuthenticatedCallToAnUnmappedActivityPath()
      throws Exception {
    long id = publishedActivity(adminToken(), onSalePackage(100L), 100L, 5L);
    String token = userToken();

    // With credentials the request reaches MVC, where nothing is mapped yet — so it is a 404, not a
    // silent success and not a 401. That is exactly the state F3 leaves ordering in.
    mockMvc
        .perform(post("/api/v1/activities/" + id + "/orders").header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void shouldProtectTheAdminActivityPaths() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/activities/1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    mockMvc
        .perform(
            post("/api/v1/admin/activities")
                .header("Authorization", "Bearer " + userToken())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }
}
