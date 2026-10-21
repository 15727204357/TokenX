package com.tokenx.order.dto;

import com.tokenx.order.model.PurchaseOrder;
import java.time.Instant;

/**
 * One order as a client may see it.
 *
 * <p>Everything here is a server-written snapshot or a server-derived value. The internal {@code
 * id}, the {@code user_id} and the raw {@code request_id} never appear: an order number is the only
 * identifier that leaves the process, and a buyer has no use for the others.
 *
 * <p>Enumerations are rendered by name ({@code SECKILL}, {@code UNPAID}) rather than by their stored
 * code, so the response does not publish the numeric layout of the table.
 *
 * @param orderNo public order number, 26 Crockford Base32 characters
 * @param orderType {@code SECKILL} in this stage; {@code NORMAL} belongs to a later stage
 * @param activityId the activity this order came from, {@code null} for a normal purchase
 * @param packageId package the order sells, for reference
 * @param packageName package name at order time
 * @param creditAmount credits granted on payment, snapshotted at order time
 * @param payAmountCent amount due in integer cents, snapshotted at order time
 * @param status {@code UNPAID}, {@code PAID} or {@code CLOSED}
 * @param expiresAt payment deadline, fixed when the order was created
 * @param paidAt when payment happened, {@code null} while unpaid
 * @param closedAt when the order was closed, {@code null} unless closed
 * @param createdAt when the order was created
 */
public record OrderResponse(
    String orderNo,
    String orderType,
    Long activityId,
    Long packageId,
    String packageName,
    Long creditAmount,
    Long payAmountCent,
    String status,
    Instant expiresAt,
    Instant paidAt,
    Instant closedAt,
    Instant createdAt) {

  public static OrderResponse from(PurchaseOrder order) {
    return new OrderResponse(
        order.getOrderNo(),
        order.getOrderType() == null ? null : order.getOrderType().name(),
        order.getActivityId(),
        order.getPackageId(),
        order.getPackageNameSnapshot(),
        order.getCreditAmount(),
        order.getPayAmountCent(),
        order.getStatus() == null ? null : order.getStatus().name(),
        order.getExpiresInstant(),
        order.getPaidInstant(),
        order.getClosedInstant(),
        order.getCreatedInstant());
  }
}
