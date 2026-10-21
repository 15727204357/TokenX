package com.tokenx.wallet.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * One ledger entry joined with the order number it refers to, as the wallet read returns it.
 *
 * <p>A plain mutable class, not a record: MyBatis fills it through setters, and a record's canonical
 * constructor cannot be populated column by column.
 *
 * <p>{@code bizId} is deliberately absent. The public order number is what the client needs, and the
 * internal id is not the client's business — so the join replaces one with the other instead of
 * returning both.
 */
public class LedgerEntryRow {

  private Long entryId;

  /** Stored code; converted through {@link LedgerBizType#fromCode(int)} before it leaves the server. */
  private Integer bizType;

  private String orderNo;

  /** Stored code; converted through {@link LedgerEffectType#fromCode(int)}. */
  private Integer effectType;

  private Long delta;

  private Long balanceAfter;

  private LocalDateTime createdAt;

  public Instant getCreatedInstant() {
    return createdAt == null ? null : createdAt.toInstant(ZoneOffset.UTC);
  }

  public Long getEntryId() {
    return entryId;
  }

  public void setEntryId(Long entryId) {
    this.entryId = entryId;
  }

  public Integer getBizType() {
    return bizType;
  }

  public void setBizType(Integer bizType) {
    this.bizType = bizType;
  }

  public String getOrderNo() {
    return orderNo;
  }

  public void setOrderNo(String orderNo) {
    this.orderNo = orderNo;
  }

  public Integer getEffectType() {
    return effectType;
  }

  public void setEffectType(Integer effectType) {
    this.effectType = effectType;
  }

  public Long getDelta() {
    return delta;
  }

  public void setDelta(Long delta) {
    this.delta = delta;
  }

  public Long getBalanceAfter() {
    return balanceAfter;
  }

  public void setBalanceAfter(Long balanceAfter) {
    this.balanceAfter = balanceAfter;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
