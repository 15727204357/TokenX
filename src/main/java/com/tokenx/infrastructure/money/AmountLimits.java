package com.tokenx.infrastructure.money;

/**
 * Accepted range for every money and credit amount the API exposes.
 *
 * <p>The caps are not arbitrary: the browser represents every numeric JSON field as a JavaScript
 * {@code number}, so amounts must stay exactly representable (within {@code 2^53-1}). Capping them
 * here keeps the API contract honest instead of silently rounding large values in the client.
 *
 * <p>These constants live in {@code infrastructure} rather than in a business module because both
 * the catalogue (list price, credit amount) and activities (activity price, credit snapshot) quote
 * the same numbers. DTO validation, the database {@code CHECK} constraints and the tests all read
 * them, so the three cannot drift apart.
 */
public final class AmountLimits {

  /** Smallest accepted credit amount: credits must be a positive integer. */
  public static final long MIN_CREDIT_AMOUNT = 1L;

  /** Largest accepted credit amount (10 亿额度). */
  public static final long MAX_CREDIT_AMOUNT = 1_000_000_000L;

  /** Smallest accepted price in cents. */
  public static final long MIN_PRICE_CENT = 1L;

  /** Largest accepted price in cents: 100 000 000 000 分 = 10 亿元. */
  public static final long MAX_PRICE_CENT = 100_000_000_000L;

  private AmountLimits() {}
}
