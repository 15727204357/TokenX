package com.tokenx.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenx.activity.model.ActivityInventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Access to {@code activity_inventory}.
 *
 * <p>F3 inserts the initial row. F4 adds exactly one write: reserving one unit. It is a conditional
 * {@code UPDATE} rather than a read-then-write because "is there stock left" and "take one" must
 * happen as one atomic statement under concurrent buyers — a plain {@code SELECT} followed by an
 * {@code UPDATE} would let two requests both read the last unit.
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
}
