package com.tokenx.activity.model;

import java.time.LocalDateTime;

/**
 * One published activity together with its stock, read in a single primary-key join.
 *
 * <p>This is a read model, not an entity and not an API DTO: it carries exactly the columns the
 * public detail needs, so {@code reserved_stock} and the internal timestamps are never loaded and
 * cannot leak by accident. A single statement also avoids the inconsistency window of reading the
 * activity and the stock in two round trips.
 */
public class PublishedActivityRow {

  private Long id;

  private Long packageId;

  private Long activityPriceCent;

  private Long creditAmountSnapshot;

  private LocalDateTime startAt;

  private LocalDateTime endAt;

  private Integer totalStock;

  private Integer availableStock;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getPackageId() {
    return packageId;
  }

  public void setPackageId(Long packageId) {
    this.packageId = packageId;
  }

  public Long getActivityPriceCent() {
    return activityPriceCent;
  }

  public void setActivityPriceCent(Long activityPriceCent) {
    this.activityPriceCent = activityPriceCent;
  }

  public Long getCreditAmountSnapshot() {
    return creditAmountSnapshot;
  }

  public void setCreditAmountSnapshot(Long creditAmountSnapshot) {
    this.creditAmountSnapshot = creditAmountSnapshot;
  }

  public LocalDateTime getStartAt() {
    return startAt;
  }

  public void setStartAt(LocalDateTime startAt) {
    this.startAt = startAt;
  }

  public LocalDateTime getEndAt() {
    return endAt;
  }

  public void setEndAt(LocalDateTime endAt) {
    this.endAt = endAt;
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
}
