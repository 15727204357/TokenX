package com.tokenx.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenx.activity.model.ActivityStatus;
import com.tokenx.activity.model.PublishedActivityRow;
import com.tokenx.activity.model.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Access to {@code seckill_activity}.
 *
 * <p>The explicit statements below exist because status changes must be safe under concurrency: a
 * plain {@code SELECT} keeps reading the transaction snapshot even after another transaction
 * committed, so a concurrent publish/offline pair could return a state that no longer exists. Using
 * {@code SELECT ... FOR UPDATE} on the primary key makes InnoDB serialise the two requests on exactly
 * that one row — no version column, no Redis lock, no cross-row lock.
 */
@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

  /**
   * Reads one activity and locks its row until the current transaction ends.
   *
   * <p>Only this primary key row is locked, so activities are independent of each other; a second
   * status change of the same activity waits here and then reads the committed status.
   */
  @Select(
      """
      SELECT id, package_id, activity_price_cent, credit_amount_snapshot,
             start_at, end_at, status, created_at, updated_at
      FROM seckill_activity
      WHERE id = #{id}
      FOR UPDATE
      """)
  SeckillActivity selectByIdForUpdate(@Param("id") long id);

  /**
   * Reads the snapshot an order is created from, holding a shared lock on that one row.
   *
   * <p>{@code FOR SHARE} instead of {@code FOR UPDATE}: many buyers of the same activity read it
   * concurrently, so they must not serialise on it — the serialisation point is the stock row. The
   * shared lock is not decoration: it makes a concurrent withdraw ({@code FOR UPDATE} in {@code
   * ActivityService.changeStatus}) wait until the order transaction commits, so an activity can
   * never be taken offline between "it is published" and "the order exists".
   *
   * <p>{@code status} is returned rather than filtered here: the caller has to tell "no such
   * activity" from "not open for orders", and both need the row.
   */
  @Select(
      """
      SELECT id, package_id, activity_price_cent, credit_amount_snapshot,
             start_at, end_at, status
      FROM seckill_activity
      WHERE id = #{id}
      FOR SHARE
      """)
  SeckillActivity selectForShare(@Param("id") long id);

  /** Writes the status of one row. The caller must already hold its row lock. */
  @Update("UPDATE seckill_activity SET status = #{status} WHERE id = #{id}")
  int updateStatus(@Param("id") long id, @Param("status") ActivityStatus status);

  /**
   * Publishes one row, refreshing the credit snapshot from the package read in the same transaction.
   *
   * <p>This is the only statement that can write {@code credit_amount_snapshot} after creation, and
   * it always writes the value the service just read from {@code token_package}; the client never
   * supplies it. After this statement the snapshot is frozen, because no endpoint updates it again.
   */
  @Update(
      """
      UPDATE seckill_activity
         SET status = 1,
             credit_amount_snapshot = #{creditAmountSnapshot}
       WHERE id = #{id}
      """)
  int publish(
      @Param("id") long id, @Param("creditAmountSnapshot") long creditAmountSnapshot);

  /**
   * Public detail: activity plus stock in one primary-key join.
   *
   * <p>{@code a.status = 1} (PUBLISHED) is part of the query rather than a post-filter, so a draft or
   * withdrawn activity cannot be exposed by a forgotten check in the service.
   */
  @Select(
      """
      SELECT a.id,
             a.package_id,
             a.activity_price_cent,
             a.credit_amount_snapshot,
             a.start_at,
             a.end_at,
             i.total_stock,
             i.available_stock
      FROM seckill_activity a
      JOIN activity_inventory i ON i.activity_id = a.id
      WHERE a.id = #{id}
        AND a.status = 1
      """)
  PublishedActivityRow selectPublishedDetail(@Param("id") long id);
}
