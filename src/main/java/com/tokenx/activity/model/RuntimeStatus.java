package com.tokenx.activity.model;

import java.time.Instant;

/**
 * Run-time state of an activity, derived — never stored.
 *
 * <p>The window is half-open: {@code [startAt, endAt)}. Exactly at {@code startAt} the activity is
 * already active; exactly at {@code endAt} it has already ended. Both comparisons use the same
 * {@link Instant} precision as the database ({@code DATETIME(3)}), so a value read back from MySQL
 * compares exactly and a clock value must be truncated to milliseconds before it gets here.
 */
public enum RuntimeStatus {
  UPCOMING,
  ACTIVE,
  ENDED;

  public static RuntimeStatus of(Instant now, Instant startAt, Instant endAt) {
    if (now.isBefore(startAt)) {
      return UPCOMING;
    }
    return now.isBefore(endAt) ? ACTIVE : ENDED;
  }
}
