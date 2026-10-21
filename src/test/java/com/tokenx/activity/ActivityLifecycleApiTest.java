package com.tokenx.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.activity.model.ActivityStatus;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.catalog.model.TokenPackage;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/**
 * The configuration state machine: {@code DRAFT -> PUBLISHED -> OFFLINE}, with {@code OFFLINE}
 * terminal and repetitions idempotent.
 *
 * <p>Each rejection also asserts that the stored row did not move, because "returned 409" alone
 * would not prove the activity is still a draft.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityLifecycleApiTest extends AbstractActivityApiTest {

  @Test
  void shouldPublishADraft() throws Exception {
    String token = adminToken();
    long id = createDraft(token, onSalePackage(1000L), 799L, 10L);

    changeStatus(token, id, "PUBLISHED")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.publishStatus").value("PUBLISHED"))
        .andExpect(jsonPath("$.totalStock").value(10));

    assertThat(storedActivity(id).getStatus()).isEqualTo(ActivityStatus.PUBLISHED);
  }

  @Test
  void shouldCancelADraftDirectlyToOffline() throws Exception {
    String token = adminToken();
    long id = createDraft(token, onSalePackage(1000L), 799L, 10L);

    changeStatus(token, id, "OFFLINE")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishStatus").value("OFFLINE"));

    assertThat(storedActivity(id).getStatus()).isEqualTo(ActivityStatus.OFFLINE);
  }

  @Test
  void shouldWithdrawAPublishedActivityWithoutDeletingOrResettingIt() throws Exception {
    String token = adminToken();
    long id = publishedActivity(token, onSalePackage(1000L), 799L, 10L);
    ActivityInventory before = storedInventory(id);

    changeStatus(token, id, "OFFLINE")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishStatus").value("OFFLINE"));

    assertThat(storedActivity(id)).as("withdrawing must keep the activity row").isNotNull();
    ActivityInventory after = storedInventory(id);
    assertThat(after).as("withdrawing must keep the stock row").isNotNull();
    assertThat(after.getTotalStock()).isEqualTo(before.getTotalStock());
    assertThat(after.getAvailableStock()).isEqualTo(before.getAvailableStock());
    assertThat(after.getReservedStock()).isEqualTo(before.getReservedStock());

    mockMvc
        .perform(get("/api/v1/activities/" + id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
  }

  @Test
  void shouldTreatRepeatingTheCurrentStatusAsSuccess() throws Exception {
    String token = adminToken();
    long id = createDraft(token, onSalePackage(1000L), 799L, 10L);

    changeStatus(token, id, "PUBLISHED").andExpect(status().isOk());

    // The package is withdrawn after publishing. Re-publishing is an idempotent no-op, so it must
    // not re-run the publish preconditions and start failing.
    TokenPackage tokenPackage = packageMapper.selectById(storedActivity(id).getPackageId());
    tokenPackage.setStatus(PackageStatus.OFF_SHELF);
    packageMapper.updateById(tokenPackage);

    changeStatus(token, id, "PUBLISHED")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishStatus").value("PUBLISHED"));

    changeStatus(token, id, "OFFLINE").andExpect(status().isOk());
    changeStatus(token, id, "OFFLINE")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishStatus").value("OFFLINE"));

    assertThat(storedActivity(id).getStatus()).isEqualTo(ActivityStatus.OFFLINE);
  }

  @Test
  void shouldRefuseToPublishAnOfflineActivity() throws Exception {
    String token = adminToken();
    long id = createDraft(token, onSalePackage(1000L), 799L, 10L);
    changeStatus(token, id, "OFFLINE").andExpect(status().isOk());

    changeStatus(token, id, "PUBLISHED")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));

    assertThat(storedActivity(id).getStatus())
        .as("OFFLINE is terminal")
        .isEqualTo(ActivityStatus.OFFLINE);
  }

  @Test
  void shouldRefuseToReturnToDraft() throws Exception {
    String token = adminToken();
    long published = publishedActivity(token, onSalePackage(1000L), 799L, 10L);
    long offline = createDraft(token, onSalePackage(1000L), 799L, 10L);
    changeStatus(token, offline, "OFFLINE").andExpect(status().isOk());

    for (long id : java.util.List.of(published, offline)) {
      changeStatus(token, id, "DRAFT")
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));
    }

    assertThat(storedActivity(published).getStatus()).isEqualTo(ActivityStatus.PUBLISHED);
    assertThat(storedActivity(offline).getStatus()).isEqualTo(ActivityStatus.OFFLINE);
  }

  @Test
  void shouldRefuseToPublishWhenThePackageIsNoLongerOnSale() throws Exception {
    String token = adminToken();
    long packageId = onSalePackage(1000L);
    long id = createDraft(token, packageId, 799L, 10L);

    TokenPackage tokenPackage = packageMapper.selectById(packageId);
    tokenPackage.setStatus(PackageStatus.OFF_SHELF);
    packageMapper.updateById(tokenPackage);

    changeStatus(token, id, "PUBLISHED")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PACKAGE_NOT_AVAILABLE"));

    assertThat(storedActivity(id).getStatus())
        .as("a failed publish must leave the activity in draft")
        .isEqualTo(ActivityStatus.DRAFT);
  }

  @Test
  void shouldRefuseToPublishAnActivityThatHasAlreadyEnded() throws Exception {
    String token = adminToken();
    long id = createDraft(token, onSalePackage(1000L), 799L, 10L);

    // Move past endAt instead of waiting for it.
    com.tokenx.support.TestClockConfig.advance(Duration.ofHours(3));

    changeStatus(token, id, "PUBLISHED")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACTIVITY_STATE_CONFLICT"));

    assertThat(storedActivity(id).getStatus()).isEqualTo(ActivityStatus.DRAFT);
  }

  @Test
  void shouldRefreshTheCreditSnapshotFromThePackageWhenPublishing() throws Exception {
    String token = adminToken();
    long packageId = onSalePackage(1000L);
    long id = createDraft(token, packageId, 799L, 10L);

    // The package credit amount changes between creation and publication.
    TokenPackage tokenPackage = packageMapper.selectById(packageId);
    tokenPackage.setCreditAmount(1500L);
    packageMapper.updateById(tokenPackage);

    changeStatus(token, id, "PUBLISHED")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.creditAmountSnapshot").value(1500));

    assertThat(storedActivity(id).getCreditAmountSnapshot())
        .as("the published snapshot must come from the package, not from creation time")
        .isEqualTo(1500L);
    assertThat(storedActivity(id).getActivityPriceCent())
        .as("publishing must not change the admin-chosen price")
        .isEqualTo(799L);
  }
}
