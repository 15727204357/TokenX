package com.tokenx.order.timeout;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler for the order timeout trigger.
 *
 * <p>Enabled here rather than on the application class so the fact that this module has a background
 * job is visible in the module that runs it. The job itself is
 * {@link OrderTimeoutScheduler}, which is absent unless {@code tokenx.order.timeout.enabled} is true,
 * so switching scheduling infrastructure on costs nothing when the trigger is off — a scheduler with
 * no tasks registered never starts a thread.
 */
@Configuration
@EnableScheduling
public class OrderTimeoutConfig {}
