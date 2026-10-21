package com.tokenx.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenx.order.model.PurchaseOrder;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Access to {@code purchase_order}.
 *
 * <p>Three point lookups, one conditional status transition, and the inherited insert. There is no
 * generic update: an order only ever changes through the single transition described below.
 *
 * <p>Each lookup uses an existing unique key, so none of them can become a scan as the table grows:
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

  /**
   * Moves one unpaid, unexpired order of this user to {@code PAID}, or changes nothing at all.
   *
   * <p>This is the first gate of payment and the only place an order becomes paid. Every condition
   * is part of the statement rather than a check before it:
   *
   * <ul>
   *   <li>{@code order_no} plus {@code user_id}: one row is addressed, and only its owner's;
   *   <li>{@code status = 0}: an order leaves {@code UNPAID} exactly once, so of any number of
   *       concurrent payers exactly one can see an affected row;
   *   <li>{@code expires_at > now}: a deadline that has passed cannot be paid, even though the
   *       order is still {@code UNPAID} — closing it is a separate step (F6).
   * </ul>
   *
   * <p>The affected-row count is the answer, not an exception: {@code 1} means this caller won and
   * now holds the order row's exclusive lock; {@code 0} means the outcome was already decided, and
   * the caller classifies it with {@link #selectByOrderNoAndUserForShare}.
   *
   * <p>{@code paid_at} and {@code updated_at} are both written from the one clock reading the
   * caller took, so a payment cannot claim to have happened before the deadline it was checked
   * against.
   */
  @Update(
      """
      UPDATE purchase_order
         SET status = 1,
             paid_at = #{now},
             updated_at = #{now}
       WHERE order_no = #{orderNo}
         AND user_id = #{userId}
         AND status = 0
         AND expires_at > #{now}
      """)
  int markPaid(
      @Param("orderNo") String orderNo,
      @Param("userId") long userId,
      @Param("now") LocalDateTime now);

  /**
   * The same row, read with a shared lock so the result is the latest <em>committed</em> state.
   *
   * <p>Used after {@link #markPaid} matches nothing. Under REPEATABLE READ a plain {@code SELECT}
   * answers from the transaction's snapshot, which may predate the payment that just won; a locking
   * read is a current read, so it either sees the committed {@code PAID} row or waits for the
   * in-flight winner and then sees it. Getting this wrong would report "still unpaid" for a paid
   * order and invite a second credit attempt.
   *
   * <p>When this transaction already won the update it holds the exclusive lock, so the shared lock
   * is granted immediately and the row returned is the one it just wrote.
   */
  @Select(
      """
      SELECT id, order_no, request_id, user_id, package_id, activity_id, order_type,
             package_name_snapshot, credit_amount, pay_amount_cent, status,
             expires_at, paid_at, closed_at, created_at, updated_at
      FROM purchase_order
      WHERE order_no = #{orderNo}
        AND user_id = #{userId}
      FOR SHARE
      """)
  PurchaseOrder selectByOrderNoAndUserForShare(
      @Param("orderNo") String orderNo, @Param("userId") long userId);
}
