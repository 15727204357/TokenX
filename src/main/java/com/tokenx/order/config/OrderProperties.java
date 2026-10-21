package com.tokenx.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Order timing policy.
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
 * @param paymentTimeout how long a new order stays payable, default 15 minutes
 */
@ConfigurationProperties("tokenx.order")
public record OrderProperties(Duration paymentTimeout) {

  /** Applied when the property is absent, so the system has one documented default. */
  public static final Duration DEFAULT_PAYMENT_TIMEOUT = Duration.ofMinutes(15);

  /**
   * Upper bound. Beyond a day an unpaid order would reserve stock for longer than a sale can
   * plausibly run, and the timeout scan added later would have nothing to do.
   */
  public static final Duration MAX_PAYMENT_TIMEOUT = Duration.ofHours(24);

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
  }
}
