package com.tokenx.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenx.order.model.PurchaseOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Access to {@code purchase_order}.
 *
 * <p>Three point lookups and the inherited insert; there is no generic update, because F4 never
 * modifies an existing order. Each lookup uses an existing unique key, so none of them can become a
 * scan as the table grows:
 *
 * <ul>
 *   <li>{@code (user_id, request_id)} → {@code uk_user_request}: has this exact request already
 *       produced an order?
 *   <li>{@code (activity_id, user_id)} → {@code uk_activity_user}: has this user already ordered
 *       from this activity?
 *   <li>{@code (order_no, user_id)} → {@code uk_order_no} plus the ownership predicate: may the
 *       caller see this order? Ownership is part of the statement, not a check in Java, so a
 *       forgotten comparison cannot expose someone else's order.
 * </ul>
 */
@Mapper
public interface PurchaseOrderMapper extends BaseMapper<PurchaseOrder> {

  /**
   * The order a previous attempt with the same idempotency key produced, if any.
   *
   * <p>Scoped to the authenticated user, exactly like {@code uk_user_request}: one client's key can
   * never resolve to another user's order.
   */
  @Select(
      """
      SELECT id, order_no, request_id, user_id, package_id, activity_id, order_type,
             package_name_snapshot, credit_amount, pay_amount_cent, status,
             expires_at, paid_at, closed_at, created_at, updated_at
      FROM purchase_order
      WHERE user_id = #{userId}
        AND request_id = #{requestId}
      """)
  PurchaseOrder selectByUserAndRequest(
      @Param("userId") long userId, @Param("requestId") byte[] requestId);

  /** The order this user already created for one activity; the second line of "one order per user". */
  @Select(
      """
      SELECT id, order_no, request_id, user_id, package_id, activity_id, order_type,
             package_name_snapshot, credit_amount, pay_amount_cent, status,
             expires_at, paid_at, closed_at, created_at, updated_at
      FROM purchase_order
      WHERE activity_id = #{activityId}
        AND user_id = #{userId}
      """)
  PurchaseOrder selectByActivityAndUser(
      @Param("activityId") long activityId, @Param("userId") long userId);

  /**
   * One order by its public number, only if it belongs to the caller.
   *
   * <p>A nonexistent order and another user's order both match no row, so the two cases are
   * indistinguishable from outside and this endpoint cannot be used to probe for order numbers.
   */
  @Select(
      """
      SELECT id, order_no, request_id, user_id, package_id, activity_id, order_type,
             package_name_snapshot, credit_amount, pay_amount_cent, status,
             expires_at, paid_at, closed_at, created_at, updated_at
      FROM purchase_order
      WHERE order_no = #{orderNo}
        AND user_id = #{userId}
      """)
  PurchaseOrder selectByOrderNoAndUser(
      @Param("orderNo") String orderNo, @Param("userId") long userId);
}
