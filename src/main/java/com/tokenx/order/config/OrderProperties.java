package com.tokenx.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Order timing policy, including how the timeout scan paces itself.
 *
 * <p>{@code tokenx.order.payment-timeout} is how long a created order stays payable. It is read from
 * configuration, overridable by the {@code TOKENX_ORDER_PAYMENT_TIMEOUT} environment variable, and
 * validated at startup: a negative, zero or absurd deadline would produce orders that are born
 * expired or effectively never expire, and both are worse than refusing to boot.
 *
 * <p>The value is applied once, when the order is created: {@code expiresAt = createdAt + timeout}.
 * Changing the property later only affects new orders, which is what makes the stored deadline a
 * fact instead of a moving target.
 *
 * <p>{@code tokenx.order.timeout} describes the scan that closes orders nobody paid for. It lives
 * here rather than in a separate properties class because it is the same policy question — how long
 * an unpaid order is allowed to exist — asked at the other end of the order's life.
 *
 * @param paymentTimeout how long a new order stays payable, default 15 minutes
 * @param timeout how the expiry scan runs; see {@link Timeout}
 */
@ConfigurationProperties("tokenx.order")
public record OrderProperties(Duration paymentTimeout, Timeout timeout) {

  /** Applied when the property is absent, so the system has one documented default. */
  public static final Duration DEFAULT_PAYMENT_TIMEOUT = Duration.ofMinutes(15);

  /**
   * Upper bound. Beyond a day an unpaid order would reserve stock for longer than a sale can
   * plausibly run, and the timeout scan would have nothing to do.
   */
  public static final Duration MAX_PAYMENT_TIMEOUT = Duration.ofHours(24);

  /** Keeps every caller that only cares about the payment window from inventing a scan config. */
  public OrderProperties {
    if (paymentTimeout == null) {
      paymentTimeout = DEFAULT_PAYMENT_TIMEOUT;
    }
    if (paymentTimeout.isZero() || paymentTimeout.isNegative()) {
      throw new IllegalArgumentException(
          "tokenx.order.payment-timeout must be positive, got " + paymentTimeout);
    }
    if (paymentTimeout.compareTo(MAX_PAYMENT_TIMEOUT) > 0) {
      throw new IllegalArgumentException(
          "tokenx.order.payment-timeout must be at most "
              + MAX_PAYMENT_TIMEOUT
              + ", got "
              + paymentTimeout);
    }
    if (timeout == null) {
      timeout = Timeout.defaults();
    }
  }

  /**
   * The expiry scan's own settings.
   *
   * <p>{@code enabled} exists so tests can turn the background trigger off and drive the scan
   * themselves; a scheduler thread that fires during a test would close the same rows the test is
   * about to assert on. Production leaves it on.
   *
   * <p>{@code max-orders-per-run} is a budget of claims, not a count of closures: one trigger may
   * <em>attempt</em> at most this many orders, and a failed attempt spends one. Without that
   * rule, a loop stepping over a row that refuses to close would have no bound of its own, so
   * the number of orders actually closed can be smaller than the configured value. A backlog is
   * still worked down over several triggers rather than by one statement that touches every
   * expired row: the scan holds a row lock while it works, so an unbounded batch would hold one
   * for an unbounded time.
   *
   * @param enabled whether the scheduled trigger runs; default {@code true}
   * @param interval delay between two triggers, default 30 seconds
   * @param maxOrdersPerRun order claims/attempts one trigger may make, including failed attempts;
   *     default 100, at most 1000
   */
  public record Timeout(Boolean enabled, Duration interval, Integer maxOrdersPerRun) {

    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);

    public static final int DEFAULT_MAX_ORDERS_PER_RUN = 100;

    /**
     * Upper bound for one trigger's claim budget. Each attempt is its own transaction, so this caps
     * how long a trigger can occupy the scheduler thread and how much work one instance does before
     * another instance gets a turn — not a limit on throughput.
     */
    public static final int MAX_ORDERS_PER_RUN = 1000;

    public static Timeout defaults() {
      return new Timeout(true, DEFAULT_INTERVAL, DEFAULT_MAX_ORDERS_PER_RUN);
    }

    public Timeout {
      if (enabled == null) {
        enabled = Boolean.TRUE;
      }
      if (interval == null) {
        interval = DEFAULT_INTERVAL;
      }
      if (interval.isZero() || interval.isNegative()) {
        throw new IllegalArgumentException(
            "tokenx.order.timeout.interval must be positive, got " + interval);
      }
      if (maxOrdersPerRun == null) {
        maxOrdersPerRun = DEFAULT_MAX_ORDERS_PER_RUN;
      }
      if (maxOrdersPerRun < 1) {
        throw new IllegalArgumentException(
            "tokenx.order.timeout.max-orders-per-run must be positive, got " + maxOrdersPerRun);
      }
      if (maxOrdersPerRun > MAX_ORDERS_PER_RUN) {
        throw new IllegalArgumentException(
            "tokenx.order.timeout.max-orders-per-run must be at most "
                + MAX_ORDERS_PER_RUN
                + ", got "
                + maxOrdersPerRun);
      }
    }
  }
}
