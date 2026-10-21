package com.tokenx.order.model;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * Which sale channel produced an order. Stored as TINYINT.
 *
 * <p>Only {@link #SECKILL} is implemented in F4. {@link #NORMAL} exists because the column, the
 * database check and the unique-key semantics are shared, and adding a value later would mean
 * rewriting an executed migration; no code path creates a normal order yet.
 */
public enum OrderType implements IEnum<Integer> {
  SECKILL(1),
  NORMAL(2);

  private final int value;

  OrderType(int value) {
    this.value = value;
  }

  @Override
  public Integer getValue() {
    return value;
  }
}
