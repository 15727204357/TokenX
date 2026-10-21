package com.tokenx.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the test drives by hand.
 *
 * <p>Activity behaviour depends entirely on the current instant: a window boundary is the difference
 * between "upcoming" and "active". Waiting for the wall clock would make those tests slow and flaky,
 * so production code takes a {@link Clock} and the tests supply this one instead.
 */
public final class MutableClock extends Clock {

  private final ZoneId zone;
  private volatile Instant instant;

  public MutableClock(Instant initial) {
    this(initial, ZoneOffset.UTC);
  }

  private MutableClock(Instant initial, ZoneId zone) {
    this.instant = initial;
    this.zone = zone;
  }

  public void setTo(Instant target) {
    this.instant = target;
  }

  public void advance(Duration amount) {
    this.instant = this.instant.plus(amount);
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId newZone) {
    return new MutableClock(instant, newZone);
  }

  @Override
  public Instant instant() {
    return instant;
  }
}
