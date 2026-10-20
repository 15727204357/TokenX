package com.tokenx.auth.model;

import com.baomidou.mybatisplus.annotation.IEnum;

/** Authorization role of an account. Stored as TINYINT (0=USER, 1=ADMIN). */
public enum UserRole implements IEnum<Integer> {
  USER(0),
  ADMIN(1);

  private final int value;

  UserRole(int value) {
    this.value = value;
  }

  @Override
  public Integer getValue() {
    return value;
  }
}
