package com.tokenx.infrastructure.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Clock for business rules whose outcome depends on "now".
 *
 * <p>Activity windows are the first such rule: whether an activity is upcoming, active or ended, and
 * whether it may still be published, are decided against this clock. Injecting it — instead of
 * reading the wall clock inside the rule — is what lets a test pin the exact instant at which a
 * window opens or closes. Other time-dependent rules should follow the same pattern when they
 * appear.
 *
 * <p>Production uses UTC, so a server in a local timezone cannot shift an activity window.
 *
 * <p>This bean is not a mandate to reroute every timestamp in the application: code that only needs
 * a value for display or for its own protocol (health checks, JWT issuance) keeps its existing time
 * source, and changing that is neither required nor implied by this configuration.
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
