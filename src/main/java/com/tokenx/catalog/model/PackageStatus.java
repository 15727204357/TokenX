package com.tokenx.catalog.model;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * Whether visitors can see a package. Stored as TINYINT (0=OFF_SHELF, 1=ON_SALE).
 *
 * <p>Taking a package off the shelf only flips this column: the row is never deleted, because
 * activities and past orders keep referring to it (docs/DEVELOPMENT_PLAN.md F2 rule 10).
 */
public enum PackageStatus implements IEnum<Integer> {
  OFF_SHELF(0),
  ON_SALE(1);

  private final int value;

  PackageStatus(int value) {
    this.value = value;
  }

  @Override
  public Integer getValue() {
    return value;
  }
}
