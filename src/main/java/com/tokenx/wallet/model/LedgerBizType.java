package com.tokenx.wallet.model;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * What a ledger posting is about. Stored as TINYINT and checked by {@code ck_ledger_biz_type}.
 *
 * <p>Only {@link #ORDER} exists in V1. Adding another kind means a migration that widens the CHECK,
 * so the stored vocabulary cannot grow by accident.
 */
public enum LedgerBizType implements IEnum<Integer> {
  ORDER(1);

  private final int value;

  LedgerBizType(int value) {
    this.value = value;
  }

  @Override
  public Integer getValue() {
    return value;
  }

  /**
   * The enum behind a stored code.
   *
   * <p>Used when a row is read through explicit SQL: an unknown code means the database and the
   * code disagree, so it fails loudly instead of being rendered as some other business type.
   */
  public static LedgerBizType fromCode(int code) {
    for (LedgerBizType type : values()) {
      if (type.value == code) {
        return type;
      }
    }
    throw new IllegalStateException("unknown token_ledger.biz_type: " + code);
  }
}
