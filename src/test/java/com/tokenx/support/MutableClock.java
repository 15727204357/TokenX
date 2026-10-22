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

  /**
   * An instant <em>this thread</em> reads instead of the shared one, invisible to every other thread.
   *
   * <p>Needed when two threads must disagree about "now" at the same moment: a race between payment
   * and closure only reaches the interesting edge if one side reads just inside the deadline while
   * the other reads the deadline itself. Advancing the shared instant between the two reads would
   * serialise them, and the race would test nothing.
   */
  private final ThreadLocal<Instant> pinnedHere = new ThreadLocal<>();

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

  /** Makes the calling thread read {@code target} until {@link #unpinCurrentThread()}. */
  public void pinCurrentThread(Instant target) {
    pinnedHere.set(target);
  }

  /** Drops the calling thread's override; it reads the shared instant again. */
  public void unpinCurrentThread() {
    pinnedHere.remove();
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
    Instant pinned = pinnedHere.get();
    return pinned != null ? pinned : instant;
  }
}
