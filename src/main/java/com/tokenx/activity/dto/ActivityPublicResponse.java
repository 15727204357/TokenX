package com.tokenx.activity.dto;

import com.tokenx.activity.model.PublishedActivityRow;
import com.tokenx.activity.model.RuntimeStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Public detail of a published activity.
 *
 * <p>Deliberately smaller than the admin response: {@code reservedStock}, the numeric status code and
 * the row timestamps stay inside the server. The credit amount is named {@code creditAmount} because
 * that is what a buyer receives; the fact that it is a snapshot is an internal concern.
 *
 * @param creditAmount credits granted on purchase, snapshotted by the server
 * @param runtimeStatus {@code UPCOMING}, {@code ACTIVE} or {@code ENDED}, computed from the server clock
 * @param soldOut {@code availableStock == 0}
 * @param purchaseLimit always {@value ActivityLimits#PURCHASE_LIMIT_PER_USER} in V1
 */
public record ActivityPublicResponse(
    Long id,
    Long packageId,
    Long activityPriceCent,
    Long creditAmount,
    Instant startAt,
    Instant endAt,
    String runtimeStatus,
    Integer totalStock,
    Integer availableStock,
    boolean soldOut,
    int purchaseLimit) {

  public static ActivityPublicResponse from(PublishedActivityRow row, Instant now) {
    Instant startAt = toInstant(row.getStartAt());
    Instant endAt = toInstant(row.getEndAt());
    return new ActivityPublicResponse(
        row.getId(),
        row.getPackageId(),
        row.getActivityPriceCent(),
        row.getCreditAmountSnapshot(),
        startAt,
        endAt,
        RuntimeStatus.of(now, startAt, endAt).name(),
        row.getTotalStock(),
        row.getAvailableStock(),
        row.getAvailableStock() == 0,
        ActivityLimits.PURCHASE_LIMIT_PER_USER);
  }

  private static Instant toInstant(LocalDateTime column) {
    return column.toInstant(ZoneOffset.UTC);
  }
}
