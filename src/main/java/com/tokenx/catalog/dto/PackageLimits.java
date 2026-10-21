package com.tokenx.catalog.dto;

/**
 * Accepted range for package amounts.
 *
 * <p>The caps are not arbitrary: the browser represents every numeric JSON field as a JavaScript
 * {@code number}, so amounts must stay exactly representable (within {@code 2^53-1}). Capping them
 * here keeps the API contract honest instead of silently rounding large values in the client.
 *
 * <p>DTO validation, the database {@code CHECK} constraints and the tests all read these constants so
 * the three cannot drift apart.
 */
public final class PackageLimits {

  /** Smallest accepted credit amount: credits must be a positive integer. */
  public static final long MIN_CREDIT_AMOUNT = 1L;

  /** Largest accepted credit amount (10 亿额度). */
  public static final long MAX_CREDIT_AMOUNT = 1_000_000_000L;

  /** Smallest accepted price in cents. */
  public static final long MIN_LIST_PRICE_CENT = 1L;

  /** Largest accepted price in cents: 100 000 000 000 分 = 10 亿元. */
  public static final long MAX_LIST_PRICE_CENT = 100_000_000_000L;

  private PackageLimits() {}
}
