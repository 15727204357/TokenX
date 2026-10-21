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
 * <p>The interesting case is the boundary: activity detail is public, but only for {@code GET}.
 * {@code POST /activities/{id}/orders} was added on the very same path prefix in F4 and must not
 * inherit anonymous access, so the security rule is scoped to the HTTP method and this class pins
 * that down from both sides: an anonymous caller is rejected, and a caller with a valid token
 * actually reaches the order handler instead of falling through.
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

    // The ordering path exists, and an anonymous caller is rejected by authentication rather than
    // being let through to the handler that decrements stock.
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
  void shouldReachTheAuthenticatedOrderHandlerWithoutInheritingAnonymousAccess() throws Exception {
    long id = publishedActivity(adminToken(), onSalePackage(100L), 100L, 5L);
    String token = userToken();

    // With credentials the request reaches the order controller, which rejects it for the missing
    // idempotency key. A 400 here is the proof that the route is authenticated *and* mapped: an
    // anonymous bypass would answer 401, and an open route would answer 201.
    mockMvc
        .perform(
            post("/api/v1/activities/" + id + "/orders").header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
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
