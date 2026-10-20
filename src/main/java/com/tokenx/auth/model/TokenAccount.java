package com.tokenx.auth.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * Balance row of a user. Created together with the user in the registration transaction.
 *
 * <p>F1 only creates the zero-balance row; reading or changing the balance belongs to the wallet
 * feature (F5) and is intentionally not exposed here.
 */
@TableName("token_account")
public class TokenAccount {

  @TableId(type = IdType.INPUT)
  private Long userId;

  private Long availableBalance;

  private LocalDateTime updatedAt;

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public Long getAvailableBalance() {
    return availableBalance;
  }

  public void setAvailableBalance(Long availableBalance) {
    this.availableBalance = availableBalance;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
