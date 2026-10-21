package com.tokenx.activity.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * Row of {@code activity_inventory}: one row per activity, created in the same transaction as the
 * activity itself.
 *
 * <p>Three quantities, and the sold one is derived: {@code sold = total - available - reserved}.
 * F3 only ever initialises this row ({@code N / N / 0}); no endpoint in this task changes stock.
 */
@TableName("activity_inventory")
public class ActivityInventory {

  /**
   * The activity id <em>is</em> the key and is supplied by the caller, not generated, so the type is
   * {@code INPUT}. The global default is auto-increment, which would leave this column out of the
   * INSERT and fail against the NOT NULL primary key.
   */
  @TableId(type = IdType.INPUT)
  private Long activityId;

  private Integer totalStock;

  private Integer availableStock;

  private Integer reservedStock;

  private LocalDateTime updatedAt;

  /** A brand-new activity: everything is available and nothing is reserved yet. */
  public static ActivityInventory initial(long activityId, int totalStock) {
    ActivityInventory inventory = new ActivityInventory();
    inventory.setActivityId(activityId);
    inventory.setTotalStock(totalStock);
    inventory.setAvailableStock(totalStock);
    inventory.setReservedStock(0);
    return inventory;
  }

  public Long getActivityId() {
    return activityId;
  }

  public void setActivityId(Long activityId) {
    this.activityId = activityId;
  }

  public Integer getTotalStock() {
    return totalStock;
  }

  public void setTotalStock(Integer totalStock) {
    this.totalStock = totalStock;
  }

  public Integer getAvailableStock() {
    return availableStock;
  }

  public void setAvailableStock(Integer availableStock) {
    this.availableStock = availableStock;
  }

  public Integer getReservedStock() {
    return reservedStock;
  }

  public void setReservedStock(Integer reservedStock) {
    this.reservedStock = reservedStock;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
