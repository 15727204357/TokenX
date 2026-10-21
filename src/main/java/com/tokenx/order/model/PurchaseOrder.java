package com.tokenx.order.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Row of {@code purchase_order}. Never handed to clients: the API returns {@code OrderResponse}, so
 * the internal {@code id}, the idempotency key and the numeric status code cannot leak.
 *
 * <p>Every money column is a <em>snapshot</em> written by the server at creation time. A later price
 * change on the activity or the package cannot reach an existing order, which is why nothing here is
 * read through a join.
 *
 * <p>Times are UTC wall-clock in {@code DATETIME(3)}, exactly like the activity table, so they are
 * held as {@link LocalDateTime} and converted in one place. Truncating to milliseconds before
 * writing is what makes a value read back equal to the value compared.
 */
@TableName("purchase_order")
public class PurchaseOrder {

  @TableId(type = IdType.AUTO)
  private Long id;

  /** The only identifier that leaves the server. */
  private String orderNo;

  /** Canonical {@code Idempotency-Key} UUID as {@code BINARY(16)}, not a string. */
  private byte[] requestId;

  private Long userId;

  private Long packageId;

  /** Set for {@link OrderType#SECKILL}, {@code null} for a normal purchase. */
  private Long activityId;

  private OrderType orderType;

  private String packageNameSnapshot;

  private Long creditAmount;

  private Long payAmountCent;

  private OrderStatus status;

  private LocalDateTime expiresAt;

  private LocalDateTime paidAt;

  private LocalDateTime closedAt;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;

  public Instant getExpiresInstant() {
    return toInstant(expiresAt);
  }

  public Instant getPaidInstant() {
    return toInstant(paidAt);
  }

  public Instant getClosedInstant() {
    return toInstant(closedAt);
  }

  public Instant getCreatedInstant() {
    return toInstant(createdAt);
  }

  /** Writes an instant to a {@code DATETIME(3)} column without losing what the database keeps. */
  public static LocalDateTime toColumn(Instant instant) {
    return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  /** Reads a {@code DATETIME(3)} column back as the instant the server originally meant. */
  public static Instant toInstant(LocalDateTime column) {
    return column == null ? null : column.toInstant(ZoneOffset.UTC);
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getOrderNo() {
    return orderNo;
  }

  public void setOrderNo(String orderNo) {
    this.orderNo = orderNo;
  }

  public byte[] getRequestId() {
    return requestId;
  }

  public void setRequestId(byte[] requestId) {
    this.requestId = requestId;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public Long getPackageId() {
    return packageId;
  }

  public void setPackageId(Long packageId) {
    this.packageId = packageId;
  }

  public Long getActivityId() {
    return activityId;
  }

  public void setActivityId(Long activityId) {
    this.activityId = activityId;
  }

  public OrderType getOrderType() {
    return orderType;
  }

  public void setOrderType(OrderType orderType) {
    this.orderType = orderType;
  }

  public String getPackageNameSnapshot() {
    return packageNameSnapshot;
  }

  public void setPackageNameSnapshot(String packageNameSnapshot) {
    this.packageNameSnapshot = packageNameSnapshot;
  }

  public Long getCreditAmount() {
    return creditAmount;
  }

  public void setCreditAmount(Long creditAmount) {
    this.creditAmount = creditAmount;
  }

  public Long getPayAmountCent() {
    return payAmountCent;
  }

  public void setPayAmountCent(Long payAmountCent) {
    this.payAmountCent = payAmountCent;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public void setStatus(OrderStatus status) {
    this.status = status;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(LocalDateTime expiresAt) {
    this.expiresAt = expiresAt;
  }

  public LocalDateTime getPaidAt() {
    return paidAt;
  }

  public void setPaidAt(LocalDateTime paidAt) {
    this.paidAt = paidAt;
  }

  public LocalDateTime getClosedAt() {
    return closedAt;
  }

  public void setClosedAt(LocalDateTime closedAt) {
    this.closedAt = closedAt;
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
