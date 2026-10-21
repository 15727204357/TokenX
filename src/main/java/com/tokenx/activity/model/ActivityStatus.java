package com.tokenx.activity.model;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * Configuration state of an activity, stored as TINYINT.
 *
 * <p>This is <em>not</em> the run-time state: whether an activity is upcoming, active or ended is
 * derived from its time window and the server clock (see {@link RuntimeStatus}).
 *
 * <p>The only legal edges are the two below. {@code OFFLINE} is terminal: an activity that was
 * withdrawn or whose draft was cancelled can never be published again, and no state can go back to
 * {@code DRAFT}. Re-requesting the current state is handled by the service before this rule is
 * consulted, so it stays an idempotent success instead of a conflict.
 */
public enum ActivityStatus implements IEnum<Integer> {
  DRAFT(0),
  PUBLISHED(1),
  OFFLINE(2);

  private final int value;

  ActivityStatus(int value) {
    this.value = value;
  }

  @Override
  public Integer getValue() {
    return value;
  }

  /** Whether {@code this -> target} is one of the two allowed edges. */
  public boolean canTransitionTo(ActivityStatus target) {
    return switch (this) {
      case DRAFT -> target == PUBLISHED || target == OFFLINE;
      case PUBLISHED -> target == OFFLINE;
      case OFFLINE -> false;
    };
  }
}
