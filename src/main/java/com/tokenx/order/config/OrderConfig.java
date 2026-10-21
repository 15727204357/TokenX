package com.tokenx.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link OrderProperties}.
 *
 * <p>Exists because an immutable properties record needs an explicit registration point: a plain
 * {@code @Component} would be treated as an ordinary bean and Spring would try to inject a {@code
 * Duration} instead of binding {@code tokenx.order.payment-timeout} into the constructor.
 *
 * <p>Registration is scoped to this one class rather than a global properties scan, so modules stay
 * independent: adding an order property cannot accidentally change how the auth module binds.
 */
@Configuration
@EnableConfigurationProperties(OrderProperties.class)
public class OrderConfig {}
