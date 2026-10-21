package com.tokenx.wallet.model;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * Which way a posting moves the balance. Stored as TINYINT and checked by {@code
 * ck_ledger_effect_type}, together with the sign of {@code delta}.
 *
 * <p>{@link #DEBIT} is reserved: V1 has no refund, spend or withdrawal, so nothing writes it yet.
 * Keeping it in the vocabulary is what lets a future reversal be a new posting instead of an update
 * of an old one.
 */
public enum LedgerEffectType implements IEnum<Integer> {
  CREDIT(1),
  DEBIT(2);

  private final int value;

  LedgerEffectType(int value) {
    this.value = value;
  }

  @Override
  public Integer getValue() {
    return value;
  }

  /** The enum behind a stored code; an unknown code is a contradiction between schema and code. */
  public static LedgerEffectType fromCode(int code) {
    for (LedgerEffectType effect : values()) {
      if (effect.value == code) {
        return effect;
      }
    }
    throw new IllegalStateException("unknown token_ledger.effect_type: " + code);
  }
}
