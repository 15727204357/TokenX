package com.tokenx.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the production UTC clock with a controllable one for the activity tests.
 *
 * <p>Marked {@link Primary} so it wins over {@code ClockConfig}'s bean without removing it: the
 * production configuration stays exactly as it ships, and only the injection point changes.
 *
 * <p>Test classes import this configuration explicitly. Nothing is annotated globally, so a test that
 * does not ask for a fixed clock keeps the real one.
 */
@TestConfiguration
public class TestClockConfig {

  /** A round instant, so a boundary is obvious in a failure message. */
  public static final Instant BASE = Instant.parse("2026-10-21T12:00:00Z");

  private static final MutableClock CLOCK = new MutableClock(BASE);

  @Bean
  @Primary
  public Clock testClock() {
    return CLOCK;
  }

  /** Back to {@link #BASE} before each test, so tests cannot leak a shifted clock. */
  public static void reset() {
    CLOCK.setTo(BASE);
  }

  public static Instant now() {
    return CLOCK.instant();
  }

  public static void setTo(Instant instant) {
    CLOCK.setTo(instant);
  }

  public static void advance(Duration amount) {
    CLOCK.advance(amount);
  }
}
