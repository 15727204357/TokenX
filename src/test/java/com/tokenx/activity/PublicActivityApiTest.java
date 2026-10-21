package com.tokenx.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityStatus;
import com.tokenx.support.TestClockConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The public detail: what a visitor may see, and exactly where the run-time state switches.
 *
 * <p>Boundary assertions use the injected clock, so {@code now == startAt} and {@code now == endAt}
 * are tested as real instants instead of being approximated with sleeps. The window is half-open,
 * {@code [startAt, endAt)}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicActivityApiTest extends AbstractActivityApiTest {

  @Test
  void shouldExposePublishedActivityToAnonymousVisitors() throws Exception {
    String token = adminToken();
    long packageId = onSalePackage(1000L);
    long id = publishedActivity(token, packageId, 799L, 100L);

    mockMvc
        .perform(get("/api/v1/activities/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.packageId").value(packageId))
        .andExpect(jsonPath("$.activityPriceCent").value(799))
        .andExpect(jsonPath("$.creditAmount").value(1000))
        .andExpect(jsonPath("$.startAt").value("2026-10-21T13:00:00Z"))
        .andExpect(jsonPath("$.endAt").value("2026-10-21T14:00:00Z"))
        .andExpect(jsonPath("$.runtimeStatus").value("UPCOMING"))
        .andExpect(jsonPath("$.totalStock").value(100))
        .andExpect(jsonPath("$.availableStock").value(100))
        .andExpect(jsonPath("$.soldOut").value(false))
        .andExpect(jsonPath("$.purchaseLimit").value(1))
        // Nothing internal may ride along: 11 fields, no reserved stock, no raw state, no timestamps.
        .andExpect(jsonPath("$.*", hasSize(11)))
        .andExpect(jsonPath("$.reservedStock").doesNotExist())
        .andExpect(jsonPath("$.creditAmountSnapshot").doesNotExist())
        .andExpect(jsonPath("$.publishStatus").doesNotExist())
        .andExpect(jsonPath("$.status").doesNotExist())
        .andExpect(jsonPath("$.createdAt").doesNotExist())
        .andExpect(jsonPath("$.updatedAt").doesNotExist());
  }

  @Test
  void shouldReportSoldOutWithoutChangingTheRuntimeState() throws Exception {
    String token = adminToken();
    long id = publishedActivity(token, onSalePackage(100L), 100L, 0L);

    mockMvc
        .perform(get("/api/v1/activities/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availableStock").value(0))
        .andExpect(jsonPath("$.soldOut").value(true))
        .andExpect(jsonPath("$.runtimeStatus").value("UPCOMING"));
  }

  @Test
  void shouldReturnTheSame404ForDraftOfflineAndUnknownActivities() throws Exception {
    String token = adminToken();
    long packageId = onSalePackage(1000L);

    long draft = createDraft(token, packageId, 799L, 10L);
    long offline = createDraft(token, packageId, 799L, 10L);
    changeStatus(token, offline, "OFFLINE").andExpect(status().isOk());
    long unknown = 9_999_999L;

    List<String> bodies = new ArrayList<>();
    for (long id : List.of(draft, offline, unknown)) {
      MvcResult result =
          mockMvc
              .perform(get("/api/v1/activities/" + id))
              .andExpect(status().isNotFound())
              .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"))
              .andExpect(jsonPath("$.*", hasSize(3)))
              .andReturn();
      bodies.add(messageOf(result));
    }

    assertThat(bodies)
        .as("the three cases must be indistinguishable, otherwise the endpoint leaks existence")
        .containsOnly(bodies.get(0));
    assertThat(storedActivity(draft).getStatus()).isEqualTo(ActivityStatus.DRAFT);
  }

  @Test
  void shouldSwitchRuntimeStatusExactlyAtTheWindowBoundaries() throws Exception {
    String token = adminToken();
    long id = publishedActivity(token, onSalePackage(1000L), 799L, 10L);

    assertThat(runtimeStatusAt(id, START.minus(Duration.ofMillis(1)))).isEqualTo("UPCOMING");
    assertThat(runtimeStatusAt(id, START)).as("startAt is inclusive").isEqualTo("ACTIVE");
    assertThat(runtimeStatusAt(id, START.plus(Duration.ofSeconds(1)))).isEqualTo("ACTIVE");
    assertThat(runtimeStatusAt(id, START.plus(Duration.ofMinutes(30)))).isEqualTo("ACTIVE");
    assertThat(runtimeStatusAt(id, END.minus(Duration.ofMillis(1)))).isEqualTo("ACTIVE");
    assertThat(runtimeStatusAt(id, END)).as("endAt is exclusive").isEqualTo("ENDED");
    assertThat(runtimeStatusAt(id, END.plus(Duration.ofSeconds(1)))).isEqualTo("ENDED");
  }

  @Test
  void shouldValidateTheActivityIdOnThePublicEndpoint() throws Exception {
    for (String id : List.of("0", "-1", "not-a-number")) {
      mockMvc
          .perform(get("/api/v1/activities/" + id))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
  }

  private String runtimeStatusAt(long activityId, Instant instant) throws Exception {
    TestClockConfig.setTo(instant);
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/activities/" + activityId))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(body(result)).get("runtimeStatus").asText();
  }

  private String messageOf(MvcResult result) throws Exception {
    return objectMapper.readTree(body(result)).get("message").asText();
  }
}
