package com.tokenx.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.activity.mapper.ActivityInventoryMapper;
import com.tokenx.activity.mapper.SeckillActivityMapper;
import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.activity.model.SeckillActivity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Proves that creating an activity is all-or-nothing.
 *
 * <p>The activity and its stock row are two inserts in one transaction with no foreign key between
 * them, so nothing in the schema would stop an activity from surviving without stock. The only
 * guarantee is the transaction itself, and the only honest way to test it is to make the second
 * insert fail for real: the spy fails the stock insert, MySQL performs the activity insert, and the
 * assertion then reads the database to see whether that insert was rolled back.
 *
 * <p>The captured entity proves the first insert actually reached the database, so the test cannot
 * pass by failing before anything was written.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityCreateRollbackTest extends AbstractActivityApiTest {

  @MockitoSpyBean private ActivityInventoryMapper inventoryMapperSpy;
  @MockitoSpyBean private SeckillActivityMapper activityMapperSpy;

  @Test
  void shouldRollBackTheActivityWhenTheStockInsertFails() throws Exception {
    String token = adminToken();
    long packageId = onSalePackage(1000L);

    doThrow(new DataIntegrityViolationException("forced stock insert failure"))
        .when(inventoryMapperSpy)
        .insert(any(ActivityInventory.class));

    postActivity(token, activityBody(packageId, 799L, 10L, START, END))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

    ArgumentCaptor<SeckillActivity> captured = ArgumentCaptor.forClass(SeckillActivity.class);
    verify(activityMapperSpy).insert(captured.capture());
    long attemptedId = captured.getValue().getId();

    assertThat(attemptedId)
        .as("the activity insert really ran, so the rollback is what removed the row")
        .isPositive();
    assertThat(activityMapper.selectById(attemptedId)).isNull();
    assertThat(activityMapper.selectCount(
            Wrappers.<SeckillActivity>lambdaQuery().eq(SeckillActivity::getPackageId, packageId)))
        .as("no activity may survive a failed stock insert")
        .isZero();
    assertThat(inventoryMapper.selectById(attemptedId)).isNull();
  }

  @Test
  void shouldRollBackTheStockRowWhenTheActivityInsertFails() throws Exception {
    String token = adminToken();
    long packageId = onSalePackage(1000L);

    doThrow(new DataIntegrityViolationException("forced activity insert failure"))
        .when(activityMapperSpy)
        .insert(any(SeckillActivity.class));

    postActivity(token, activityBody(packageId, 799L, 10L, START, END))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

    assertThat(activityMapper.selectCount(
            Wrappers.<SeckillActivity>lambdaQuery().eq(SeckillActivity::getPackageId, packageId)))
        .isZero();
  }
}
