package com.tokenx.catalog.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * Row of {@code token_package}. Never handed to clients directly: responses use
 * {@code PackageResponse} so that internal columns cannot leak into the public contract.
 *
 * <p>There is deliberately no stock, sales counter or price-at-activity-time column: stock belongs
 * to an activity, and activity prices are snapshotted on the activity itself.
 */
@TableName("token_package")
public class TokenPackage {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String name;

  private Long creditAmount;

  private Long listPriceCent;

  private PackageStatus status;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Long getCreditAmount() {
    return creditAmount;
  }

  public void setCreditAmount(Long creditAmount) {
    this.creditAmount = creditAmount;
  }

  public Long getListPriceCent() {
    return listPriceCent;
  }

  public void setListPriceCent(Long listPriceCent) {
    this.listPriceCent = listPriceCent;
  }

  public PackageStatus getStatus() {
    return status;
  }

  public void setStatus(PackageStatus status) {
    this.status = status;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
