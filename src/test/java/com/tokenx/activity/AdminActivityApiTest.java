package com.tokenx.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.activity.model.ActivityStatus;
import com.tokenx.activity.model.SeckillActivity;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.infrastructure.money.AmountLimits;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Activity creation: the request contract, where each stored value comes from, and who may call it.
 *
 * <p>The clock is fixed, so "the window must end in the future" is decided by a known instant rather
 * than by whatever the machine clock happens to read while the test runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminActivityApiTest extends AbstractActivityApiTest {

  @Test
  void shouldCreateDraftActivityAndInitialiseStockExactly() throws Exception {
    String token = adminToken();
    long packageId = onSalePackage(1000L);

    MvcResult result =
        postActivity(token, activityBody(packageId, 799L, 100L, START, END))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.packageId").value(packageId))
            .andExpect(jsonPath("$.activityPriceCent").value(799))
            // Snapshot comes from the package row (1000), not from the request.
            .andExpect(jsonPath("$.creditAmountSnapshot").value(1000))
            .andExpect(jsonPath("$.publishStatus").value("DRAFT"))
            .andExpect(jsonPath("$.runtimeStatus").value("UPCOMING"))
            .andExpect(jsonPath("$.totalStock").value(100))
            .andExpect(jsonPath("$.availableStock").value(100))
            .andExpect(jsonPath("$.reservedStock").value(0))
            .andExpect(jsonPath("$.soldOut").value(false))
            .andExpect(jsonPath("$.purchaseLimit").value(1))
            // A UTC ISO-8601 instant with an explicit offset, not a local date-time.
            .andExpect(jsonPath("$.startAt").value("2026-10-21T13:00:00Z"))
            .andExpect(jsonPath("$.endAt").value("2026-10-21T14:00:00Z"))
            .andExpect(jsonPath("$.*", hasSize(13)))
            .andReturn();

    long id = track(result);
    SeckillActivity activity = storedActivity(id);
    assertThat(activity.getStatus()).as("a new activity is always a draft").isEqualTo(ActivityStatus.DRAFT);

    ActivityInventory inventory = storedInventory(id);
    assertThat(inventory.getTotalStock()).isEqualTo(100);
    assertThat(inventory.getAvailableStock()).isEqualTo(100);
    assertThat(inventory.getReservedStock()).isZero();
  }

  @Test
  void shouldIgnoreClientSuppliedStatusStockAndSnapshot() throws Exception {
    String token = adminToken();
    long packageId = onSalePackage(2000L);

    Map<String, Object> body = activityBody(packageId, 500L, 10L, START, END);
    body.put("creditAmountSnapshot", 999_999);
    body.put("status", "PUBLISHED");
    body.put("availableStock", 1);
    body.put("reservedStock", 5);
    body.put("soldStock", 7);
    body.put("purchaseLimit", 99);
    body.put("userId", 42);

    long id = track(postActivity(token, body).andExpect(status().isCreated()).andReturn());

    SeckillActivity activity = storedActivity(id);
    assertThat(activity.getStatus()).isEqualTo(ActivityStatus.DRAFT);
    assertThat(activity.getCreditAmountSnapshot())
        .as("the snapshot is a server fact, never a client field")
        .isEqualTo(2000L);

    ActivityInventory inventory = storedInventory(id);
    assertThat(inventory.getTotalStock()).isEqualTo(10);
    assertThat(inventory.getAvailableStock()).isEqualTo(10);
    assertThat(inventory.getReservedStock()).isZero();
  }

  @Test
  void shouldAllowZeroStockAndReportItAsSoldOut() throws Exception {
    String token = adminToken();
    long packageId = onSalePackage(100L);

    MvcResult result =
        postActivity(token, activityBody(packageId, 1L, 0L, START, END))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.totalStock").value(0))
            .andExpect(jsonPath("$.availableStock").value(0))
            .andExpect(jsonPath("$.soldOut").value(true))
            // Sold out is not a time state: the window has not started yet.
            .andExpect(jsonPath("$.runtimeStatus").value("UPCOMING"))
            .andReturn();

    long id = track(result);
    assertThat(storedInventory(id).getAvailableStock()).isZero();
  }

  @Test
  void shouldAcceptAWindowThatStartsInThePast() throws Exception {
    String token = adminToken();
    long packageId = onSalePackage(100L);

    MvcResult result =
        postActivity(
                token,
                activityBody(
                    packageId,
                    100L,
                    5L,
                    BASE.minus(Duration.ofHours(1)),
                    BASE.plus(Duration.ofMinutes(30))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.startAt").value("2026-10-21T11:00:00Z"))
            // Already inside the window, so it is active from the first read.
            .andExpect(jsonPath("$.runtimeStatus").value("ACTIVE"))
            .andReturn();

    track(result);
  }

  @Test
  void shouldAcceptTheLargestTotalStock() throws Exception {
    String token = adminToken();
    long packageId = onSalePackage(100L);

    MvcResult result =
        postActivity(token, activityBody(packageId, 100L, Integer.MAX_VALUE, START, END))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.totalStock").value(Integer.MAX_VALUE))
            .andExpect(jsonPath("$.availableStock").value(Integer.MAX_VALUE))
            .andReturn();

    assertThat(storedInventory(track(result)).getTotalStock()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void shouldRejectInvalidCreationPayload() throws Exception {
    String token = adminToken();
    long packageId = onSalePackage(100L);

    Map<String, Object> missingPackage = activityBody(packageId, 100L, 1L, START, END);
    missingPackage.remove("packageId");

    Map<String, Object> zeroPrice = activityBody(packageId, 0L, 1L, START, END);
    Map<String, Object> negativePrice = activityBody(packageId, -1L, 1L, START, END);
    Map<String, Object> priceAboveCap =
        activityBody(packageId, AmountLimits.MAX_PRICE_CENT + 1, 1L, START, END);
    Map<String, Object> missingPrice = activityBody(packageId, 100L, 1L, START, END);
    missingPrice.remove("activityPriceCent");
    Map<String, Object> negativeStock = activityBody(packageId, 100L, -1L, START, END);
    Map<String, Object> stockAboveJavaMax =
        activityBody(packageId, 100L, (long) Integer.MAX_VALUE + 1, START, END);
    Map<String, Object> missingStock = activityBody(packageId, 100L, 1L, START, END);
    missingStock.remove("totalStock");
    Map<String, Object> missingStart = activityBody(packageId, 100L, 1L, START, END);
    missingStart.remove("startAt");
    Map<String, Object> missingEnd = activityBody(packageId, 100L, 1L, START, END);
    missingEnd.remove("endAt");
    Map<String, Object> equalBounds = activityBody(packageId, 100L, 1L, START, START);
    Map<String, Object> reversedBounds = activityBody(packageId, 100L, 1L, END, START);
    Map<String, Object> endingNow = activityBody(packageId, 100L, 1L, BASE.minus(Duration.ofHours(2)), BASE);
    Map<String, Object> alreadyEnded =
        activityBody(packageId, 100L, 1L, BASE.minus(Duration.ofHours(3)), BASE.minus(Duration.ofMinutes(1)));
    Map<String, Object> zeroPackageId = activityBody(0L, 100L, 1L, START, END);
    // Before year 1000 the value no longer fits the DATETIME column: reject it as a bad request
    // instead of letting the insert fail as a server error. The window itself is otherwise valid, so
    // only the storage range can be what rejects it.
    Map<String, Object> beforeMysqlRange =
        activityBody(packageId, 100L, 1L, Instant.parse("0001-01-01T00:00:00Z"), END);

    for (Map<String, Object> body :
        java.util.List.of(
            missingPackage,
            zeroPackageId,
            zeroPrice,
            negativePrice,
            priceAboveCap,
            missingPrice,
            negativeStock,
            stockAboveJavaMax,
            missingStock,
            missingStart,
            missingEnd,
            equalBounds,
            reversedBounds,
            endingNow,
            alreadyEnded,
            beforeMysqlRange)) {
      postActivity(token, body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    assertThat(
            activityMapper.selectCount(
                Wrappers.<SeckillActivity>lambdaQuery()
                    .eq(SeckillActivity::getPackageId, packageId)))
        .as("a rejected request must not create an activity or a stock row")
        .isZero();
  }

  @Test
  void shouldRejectUnparseableCreationPayload() throws Exception {
    String token = adminToken();
    long packageId = onSalePackage(100L);

    // No offset: the server would have to guess a timezone, so this is not accepted.
    String noOffset =
        """
        {"packageId":%d,"activityPriceCent":100,"totalStock":1,
         "startAt":"2026-10-21T13:00:00","endAt":"2026-10-21T14:00:00Z"}
        """
            .formatted(packageId);

    mockMvc
        .perform(
            post("/api/v1/admin/activities")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(noOffset))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
  }

  @Test
  void shouldRejectUnknownPackage() throws Exception {
    String token = adminToken();

    postActivity(token, activityBody(9_999_999L, 100L, 1L, START, END))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
        .andExpect(jsonPath("$.*", hasSize(3)));
  }

  @Test
  void shouldRejectPackageThatIsOffTheShelf() throws Exception {
    String token = adminToken();
    long offShelf = packageRow(1000L, PackageStatus.OFF_SHELF);

    postActivity(token, activityBody(offShelf, 100L, 1L, START, END))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PACKAGE_NOT_AVAILABLE"));

    assertThat(
            activityMapper.selectCount(
                Wrappers.<SeckillActivity>lambdaQuery().eq(SeckillActivity::getPackageId, offShelf)))
        .as("no activity may be created for a package that is not on sale")
        .isZero();
  }

  @Test
  void shouldRequireAuthenticationForAdminEndpoints() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(activityBody(1L, 100L, 1L, START, END))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    mockMvc
        .perform(
            patch("/api/v1/admin/activities/1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PUBLISHED\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void shouldRefuseOrdinaryUserAtAdminEndpoints() throws Exception {
    String token = userToken();
    long packageId = onSalePackage(100L);

    postActivity(token, activityBody(packageId, 100L, 1L, START, END))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    long id = createDraft(adminToken(), packageId, 100L, 1L);

    changeStatus(token, id, "PUBLISHED")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(storedActivity(id).getStatus())
        .as("a refused request must not change the row")
        .isEqualTo(ActivityStatus.DRAFT);
  }

  @Test
  void shouldValidateTheActivityIdOnStatusChanges() throws Exception {
    String token = adminToken();

    for (String id : java.util.List.of("0", "-1", "not-a-number")) {
      MvcResult result =
          mockMvc
              .perform(
                  patch("/api/v1/admin/activities/" + id + "/status")
                      .header("Authorization", "Bearer " + token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"status\":\"PUBLISHED\"}"))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
              .andReturn();

      assertThat(body(result))
          .as("errors must not leak stack traces or SQL")
          .doesNotContain("Exception")
          .doesNotContain("com.tokenx");
    }

    changeStatus(token, 9_999_999L, "PUBLISHED")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
  }

  @Test
  void shouldRejectUnsupportedStatusValue() throws Exception {
    String token = adminToken();
    long id = createDraft(token, onSalePackage(100L), 100L, 1L);

    for (String status : java.util.List.of("published", "", "ON_SALE", "PUBLISHED ")) {
      changeStatus(token, id, status)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.message").value(containsString("status")));
    }

    assertThat(storedActivity(id).getStatus()).isEqualTo(ActivityStatus.DRAFT);
  }
}
