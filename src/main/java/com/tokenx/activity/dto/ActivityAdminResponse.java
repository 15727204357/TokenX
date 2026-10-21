package com.tokenx.activity.dto;

import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.activity.model.RuntimeStatus;
import com.tokenx.activity.model.SeckillActivity;
import java.time.Instant;

/**
 * Activity as seen by an administrator: configuration state plus stock.
 *
 * <p>Separate from the public response because an admin needs the internal quantities
 * ({@code creditAmountSnapshot}, {@code reservedStock}, {@code publishStatus}) that a visitor must
 * not see, and because the two contracts change for different reasons.
 *
 * @param publishStatus configuration state: {@code DRAFT}, {@code PUBLISHED} or {@code OFFLINE}
 * @param runtimeStatus derived from the time window and the server clock; never stored
 * @param soldOut derived as {@code availableStock == 0}; a sold-out activity can still be ACTIVE
 * @param purchaseLimit always {@value ActivityLimits#PURCHASE_LIMIT_PER_USER} in V1
 */
public record ActivityAdminResponse(
    Long id,
    Long packageId,
    Long activityPriceCent,
    Long creditAmountSnapshot,
    Instant startAt,
    Instant endAt,
    String publishStatus,
    String runtimeStatus,
    Integer totalStock,
    Integer availableStock,
    Integer reservedStock,
    boolean soldOut,
    int purchaseLimit) {

  public static ActivityAdminResponse from(
      SeckillActivity activity, ActivityInventory inventory, Instant now) {
    Instant startAt = activity.getStartInstant();
    Instant endAt = activity.getEndInstant();
    return new ActivityAdminResponse(
        activity.getId(),
        activity.getPackageId(),
        activity.getActivityPriceCent(),
        activity.getCreditAmountSnapshot(),
        startAt,
        endAt,
        activity.getStatus().name(),
        RuntimeStatus.of(now, startAt, endAt).name(),
        inventory.getTotalStock(),
        inventory.getAvailableStock(),
        inventory.getReservedStock(),
        inventory.getAvailableStock() == 0,
        ActivityLimits.PURCHASE_LIMIT_PER_USER);
  }
}
