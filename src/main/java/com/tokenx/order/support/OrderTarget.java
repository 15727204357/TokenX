package com.tokenx.order.support;

import com.tokenx.order.model.OrderType;
import com.tokenx.order.model.PurchaseOrder;
import java.util.Objects;

/**
 * What a purchase request names, in the terms an idempotency key is scoped to.
 *
 * <p>An {@code Idempotency-Key} identifies one <em>attempt at one purchase</em>. That only means
 * something if the attempt can be compared with what an earlier request with the same key actually
 * produced, and the comparison has to be exact: the same key must replay the same order, and any
 * other use of it must be refused instead of silently returning an order for something else.
 *
 * <p>So the comparison covers the sale channel as well as the target. A seckill request is identified
 * by its activity, a normal purchase by its package, and an order of one channel never satisfies a
 * request from the other — even when both happen to sell the same package. Comparing only "the id in
 * the URL" would let a client reuse a key across the two endpoints and be told it had already bought
 * something it had not.
 *
 * <p>Exactly one of {@code activityId} and {@code packageId} is set, and the seckill target carries
 * no package: the package of an activity is only known once the activity has been read, while the
 * replay check runs before that read on purpose.
 *
 * @param type sale channel the request belongs to
 * @param activityId activity being bought, {@code null} for a normal purchase
 * @param packageId package being bought, {@code null} for a seckill request
 */
public record OrderTarget(OrderType type, Long activityId, Long packageId) {

  /** A seckill request: the activity is the target, and it decides the price and the stock. */
  public static OrderTarget seckill(long activityId) {
    return new OrderTarget(OrderType.SECKILL, activityId, null);
  }

  /** A normal purchase: the package is the whole target, with no activity and no stock. */
  public static OrderTarget normal(long packageId) {
    return new OrderTarget(OrderType.NORMAL, null, packageId);
  }

  /**
   * Whether an existing order is the one this request would have created.
   *
   * <p>Deliberately not "does the caller own an order" and not "is the amount the same": a replay is
   * decided by identity alone, because the amount of the existing order is a snapshot that a later
   * price change cannot invalidate.
   */
  public boolean matches(PurchaseOrder order) {
    if (order == null || order.getOrderType() != type) {
      return false;
    }
    return type == OrderType.SECKILL
        ? Objects.equals(order.getActivityId(), activityId)
        : Objects.equals(order.getPackageId(), packageId);
  }
}
