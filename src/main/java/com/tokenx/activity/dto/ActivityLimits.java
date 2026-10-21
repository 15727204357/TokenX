package com.tokenx.activity.dto;

/**
 * Fixed numbers that shape an activity, kept next to the DTOs that enforce the API contract.
 *
 * <p>{@link #PURCHASE_LIMIT_PER_USER} is not a stored column: "one order per user per activity" is a
 * project-wide rule that F4 enforces with a unique key on {@code purchase_order}. Activities only
 * report it so a client does not have to hardcode the number.
 */
public final class ActivityLimits {

  /** A sold-out activity is legal at creation time, so zero stock is allowed. */
  public static final int MIN_TOTAL_STOCK = 0;

  /**
   * The stock columns are {@code INT UNSIGNED}; keeping the API bound at the Java maximum avoids
   * narrowing.
   */
  public static final int MAX_TOTAL_STOCK = Integer.MAX_VALUE;

  /** One order per user per activity (enforced in F4), reported by every activity response. */
  public static final int PURCHASE_LIMIT_PER_USER = 1;

  private ActivityLimits() {}
}
