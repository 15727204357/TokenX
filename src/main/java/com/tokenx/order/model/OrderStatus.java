package com.tokenx.order.model;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * Order lifecycle. Stored as TINYINT.
 *
 * <p>{@code UNPAID -> PAID} and {@code UNPAID -> CLOSED} are the only transitions; both are
 * conditional updates on {@code status = 0} so exactly one of payment and timeout can win. F4 only
 * creates {@link #UNPAID} orders: payment arrives in F5, timeout closing in F6.
 */
public enum OrderStatus implements IEnum<Integer> {
  UNPAID(0),
  PAID(1),
  CLOSED(2);

  private final int value;

  OrderStatus(int value) {
    this.value = value;
  }

  @Override
  public Integer getValue() {
    return value;
  }
}
