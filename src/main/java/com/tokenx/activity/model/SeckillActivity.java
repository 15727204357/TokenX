package com.tokenx.activity.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Row of {@code seckill_activity}. Never handed to clients: the API returns dedicated DTOs so
 * internal columns such as {@code created_at} and the numeric status code cannot leak.
 *
 * <p>Times are stored as UTC wall-clock in {@code DATETIME(3)} (the connection sets
 * {@code connectionTimeZone=UTC}), therefore they are held as {@link LocalDateTime} here and
 * converted to {@link java.time.Instant} by the service. Keeping the conversion in one place is what
 * makes the millisecond precision explicit.
 */
@TableName("seckill_activity")
public class SeckillActivity {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long packageId;

  private Long activityPriceCent;

  private Long creditAmountSnapshot;

  private LocalDateTime startAt;

  private LocalDateTime endAt;

  private ActivityStatus status;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;

  /**
   * Stores a time window as UTC wall-clock, which is exactly what the {@code DATETIME(3)} columns
   * hold. Truncating to milliseconds is not cosmetic: MySQL keeps three digits, so an untruncated
   * {@link java.time.Instant} (nanoseconds) would compare as "later than" the value read back and
   * flip a boundary case.
   */
  public void setWindow(Instant startAt, Instant endAt) {
    this.startAt = toColumn(startAt);
    this.endAt = toColumn(endAt);
  }

  public Instant getStartInstant() {
    return startAt == null ? null : startAt.toInstant(ZoneOffset.UTC);
  }

  public Instant getEndInstant() {
    return endAt == null ? null : endAt.toInstant(ZoneOffset.UTC);
  }

  private static LocalDateTime toColumn(Instant instant) {
    return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

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

  public ActivityStatus getStatus() {
    return status;
  }

  public void setStatus(ActivityStatus status) {
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
