package com.tokenx.auth.model;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * Account state. Stored as TINYINT (0=ACTIVE, 1=DISABLED).
 *
 * <p>A disabled account can no longer obtain a token; tokens that were already issued stay valid
 * until they expire (docs/DATABASE_DESIGN.md §4.1). Adding a revocation list is deliberately out of
 * scope for V1 so the authentication fast path never queries the user table.
 */
public enum UserStatus implements IEnum<Integer> {
  ACTIVE(0),
  DISABLED(1);

  private final int value;

  UserStatus(int value) {
    this.value = value;
  }

  @Override
  public Integer getValue() {
    return value;
  }
}
