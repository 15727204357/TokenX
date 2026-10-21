package com.tokenx.wallet.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Row of {@code token_ledger}: one immutable posting.
 *
 * <p>Nothing in the application updates or deletes a row of this table. A wrong posting is answered
 * with a second posting in the opposite direction, which is why the columns are all set at insert
 * time and no setter for {@code id} is ever used after that.
 *
 * <p>{@code createdAt} is written explicitly from the caller's clock instead of leaning on the
 * column default. Inside a payment the posting must carry the very same instant as {@code paid_at},
 * otherwise the evidence and the fact it evidences would be stamped by two different clocks.
 */
@TableName("token_ledger")
public class TokenLedger {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long userId;

  private LedgerBizType bizType;

  /** Business primary key: for {@link LedgerBizType#ORDER} this is {@code purchase_order.id}. */
  private Long bizId;

  private LedgerEffectType effectType;

  /** Signed, and required by a CHECK to agree with {@link #effectType}. */
  private Long delta;

  /** What the balance became, frozen — it cannot be recomputed once later postings exist. */
  private Long balanceAfter;

  private LocalDateTime createdAt;

  /**
   * One credit earned by one order.
   *
   * @param userId account being credited
   * @param orderId internal id of the order that was paid; never the public order number, because
   *     the ledger refers to the fact rather than to what a client typed
   * @param creditAmount credits granted, taken from the order snapshot
   * @param balanceAfter the balance read back inside the same transaction, after the increase
   * @param now the payment's single clock reading, already truncated to milliseconds
   */
  public static TokenLedger orderCredit(
      long userId, long orderId, long creditAmount, long balanceAfter, Instant now) {
    TokenLedger posting = new TokenLedger();
    posting.setUserId(userId);
    posting.setBizType(LedgerBizType.ORDER);
    posting.setBizId(orderId);
    posting.setEffectType(LedgerEffectType.CREDIT);
    posting.setDelta(creditAmount);
    posting.setBalanceAfter(balanceAfter);
    // Truncated here as well as by the caller: writing a value the DATETIME(3) column cannot keep
    // would make a read-back differ from what the transaction decided.
    posting.setCreatedAt(
        LocalDateTime.ofInstant(now.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC));
    return posting;
  }

  public Instant getCreatedInstant() {
    return createdAt == null ? null : createdAt.toInstant(ZoneOffset.UTC);
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public LedgerBizType getBizType() {
    return bizType;
  }

  public void setBizType(LedgerBizType bizType) {
    this.bizType = bizType;
  }

  public Long getBizId() {
    return bizId;
  }

  public void setBizId(Long bizId) {
    this.bizId = bizId;
  }

  public LedgerEffectType getEffectType() {
    return effectType;
  }

  public void setEffectType(LedgerEffectType effectType) {
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
