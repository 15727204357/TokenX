package com.tokenx.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenx.activity.model.ActivityInventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Access to {@code activity_inventory}.
 *
 * <p>F3 inserts the initial row. F4 adds exactly one write: reserving one unit. F5 adds its mirror
 * image: settling a reservation when the order behind it is paid. Both are conditional updates
 * rather than read-then-write, because "is the unit there" and "take it / give it up" must happen as
 * one atomic statement under concurrent writers — a plain {@code SELECT} followed by an
 * {@code UPDATE} would let two requests act on the same unit.
 */
@Mapper
public interface ActivityInventoryMapper extends BaseMapper<ActivityInventory> {

  /**
   * Reserves one unit for an order being created.
   *
   * <p>{@code available_stock > 0} is part of the statement, so the database refuses to subtract
   * below zero: the unsigned columns and this predicate are the only oversell defence, and no
   * caller-side check is trusted. The affected-row count is the answer — {@code 1} means one unit
   * was taken, {@code 0} means the activity is sold out.
   *
   * <p>This also takes the row's exclusive lock, which is what serialises concurrent buyers of the
   * same activity on exactly this row. Different activities are different rows.
   */
  @Update(
      """
      UPDATE activity_inventory
         SET available_stock = available_stock - 1,
             reserved_stock = reserved_stock + 1
       WHERE activity_id = #{activityId}
         AND available_stock > 0
      """)
  int reserveOne(@Param("activityId") long activityId);

  /**
   * Settles one reservation: the unit leaves {@code reserved_stock} and becomes sold.
   *
   * <p>{@code available_stock} is deliberately not touched. A paid unit was never available again,
   * and putting it back would make the activity look like it had stock to sell. Sold stock stays
   * derived ({@code total - available - reserved}), so nothing else needs updating.
   *
   * <p>The affected-row count is the invariant check: {@code 1} means one reservation was settled,
   * {@code 0} means the activity had no reservation to settle while an unpaid order claimed to hold
   * one. The caller must treat that as corruption and roll the whole payment back — a credit
   * without a settled reservation would break {@code available + reserved + paid = total}.
   */
  @Update(
      """
      UPDATE activity_inventory
         SET reserved_stock = reserved_stock - 1
       WHERE activity_id = #{activityId}
         AND reserved_stock > 0
      """)
  int settleReserved(@Param("activityId") long activityId);

  /**
   * Returns one reservation to the shelf: the unit leaves {@code reserved_stock} and becomes
   * available again.
   *
   * <p>This is the mirror image of {@link #reserveOne} and belongs to closing an expired order, where
   * the sale never happened. {@link #settleReserved} is the opposite outcome — the sale happened and
   * the unit is merely no longer available — so the two statements are never alternatives for the
   * same order: payment and closure each own exactly one of them, and the order row decides which.
   *
   * <p>{@code reserved_stock > 0} keeps the unsigned column from wrapping and doubles as the
   * invariant check: {@code 1} means a reservation existed and was returned, {@code 0} means the
   * order claimed a reservation the activity does not have. The caller must treat that as corruption
   * and roll the closure back, otherwise the order would end {@code CLOSED} with its unit neither
   * reserved nor sold.
   */
  @Update(
      """
      UPDATE activity_inventory
         SET reserved_stock = reserved_stock - 1,
             available_stock = available_stock + 1
       WHERE activity_id = #{activityId}
         AND reserved_stock > 0
      """)
  int releaseReserved(@Param("activityId") long activityId);
}
