package com.tokenx.order.service;

import com.tokenx.activity.dto.ActivityOrderSnapshot;
import com.tokenx.catalog.dto.PackageSnapshot;
import com.tokenx.order.config.OrderProperties;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.order.model.OrderType;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.order.support.OrderNoGenerator;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Builds the row both sale channels insert, so the two order types cannot drift apart.
 *
 * <p>The difference between a seckill order and a normal purchase is what happens <em>before</em> the
 * insert — reserving a unit of activity stock, or nothing at all. Everything that is written is
 * identical: a server-generated order number, the price and credit snapshots, {@code UNPAID}, the
 * payment deadline derived from one clock reading, and the creation timestamps. That is why the
 * assembly lives here and not in either service; duplicating it would let one channel start writing a
 * deadline computed differently, or forget a snapshot column, without any test noticing.
 *
 * <p>It is a plain collaborator, not an abstraction: no interface, no strategy, no factory. Both
 * callers are concrete services in this package, and there is exactly one implementation because
 * there is exactly one order table.
 *
 * <p>{@link #MAX_ORDER_NO_ATTEMPTS} also belongs here: the order number is generated here, so the
 * bound on how often a collision may be retried is a property of generation, not of a channel.
 */
@Component
public class OrderDraft {

  /**
   * Order numbers are random, so a collision is astronomically unlikely. It is still bounded: after
   * this many attempts the duplicate is reported as the real failure instead of retrying forever.
   */
  public static final int MAX_ORDER_NO_ATTEMPTS = 3;

  private final OrderNoGenerator orderNoGenerator;
  private final OrderProperties orderProperties;

  public OrderDraft(OrderNoGenerator orderNoGenerator, OrderProperties orderProperties) {
    this.orderNoGenerator = orderNoGenerator;
    this.orderProperties = orderProperties;
  }

  /**
   * A normal purchase: the whole snapshot comes from the package row the caller has just locked.
   *
   * @param userId authenticated buyer, never taken from a request body
   * @param requestId the {@code Idempotency-Key} as {@code BINARY(16)}
   * @param tokenPackage the locked, on-sale package: name, credits and list price
   * @param now the caller's single clock reading, truncated to milliseconds
   */
  public PurchaseOrder buildNormal(
      long userId, byte[] requestId, PackageSnapshot tokenPackage, Instant now) {
    return assemble(
        userId,
        OrderType.NORMAL,
        null,
        tokenPackage.id(),
        requestId,
        tokenPackage.name(),
        tokenPackage.creditAmount(),
        tokenPackage.listPriceCent(),
        now);
  }

  /**
   * A seckill order: price and credits come from the activity's own snapshot, while the name comes
   * from the package the activity sells.
   *
   * @param userId authenticated buyer, never taken from a request body
   * @param requestId the {@code Idempotency-Key} as {@code BINARY(16)}
   * @param activity the locked activity: activity id, package id, activity price and credits
   * @param packageName package name at order time
   * @param now the caller's single clock reading, truncated to milliseconds
   */
  public PurchaseOrder buildSeckill(
      long userId,
      byte[] requestId,
      ActivityOrderSnapshot activity,
      String packageName,
      Instant now) {
    return assemble(
        userId,
        OrderType.SECKILL,
        activity.activityId(),
        activity.packageId(),
        requestId,
        packageName,
        activity.creditAmount(),
        activity.activityPriceCent(),
        now);
  }

  /**
   * The single place an order row is written.
   *
   * <p>{@code now} is passed in rather than read here: the caller took one clock reading and already
   * used it for its own decision (the activity window, or nothing), and the creation time and the
   * deadline must describe that same instant. A second reading would let an order be created a
   * millisecond "after" the deadline it was validated against.
   */
  private PurchaseOrder assemble(
      long userId,
      OrderType type,
      Long activityId,
      long packageId,
      byte[] requestId,
      String packageName,
      long creditAmount,
      long payAmountCent,
      Instant now) {
    PurchaseOrder order = new PurchaseOrder();
    order.setOrderNo(orderNoGenerator.next(now));
    order.setRequestId(requestId);
    order.setUserId(userId);
    order.setPackageId(packageId);
    order.setActivityId(activityId);
    order.setOrderType(type);
    order.setPackageNameSnapshot(packageName);
    order.setCreditAmount(creditAmount);
    order.setPayAmountCent(payAmountCent);
    order.setStatus(OrderStatus.UNPAID);
    order.setExpiresAt(PurchaseOrder.toColumn(now.plus(orderProperties.paymentTimeout())));
    order.setCreatedAt(PurchaseOrder.toColumn(now));
    order.setUpdatedAt(PurchaseOrder.toColumn(now));
    return order;
  }
}
