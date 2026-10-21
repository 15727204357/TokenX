package com.tokenx.infrastructure.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one clock every business rule reads.
 *
 * <p>Nothing in the codebase calls {@code Instant.now()} or {@code LocalDateTime.now()}: the current
 * time always arrives through this injected {@link Clock}. Production uses UTC, so a server in a
 * local timezone cannot shift an activity window; tests inject a fixed clock to pin the exact
 * second at which an activity turns active or ends.
 *
 * <p>{@link Clock#systemUTC()} is a thin wrapper over the system clock, not a new dependency, so
 * making it a bean costs nothing at runtime and makes the time source replaceable.
 */
@Configuration
public class ClockConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
