package com.tokenx.order.timeout;

import com.tokenx.order.config.OrderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The trigger that makes the timeout scan happen without anyone asking.
 *
 * <p>It owns no business logic. It reads the configured batch size, calls
 * {@link OrderTimeoutService#closeExpiredOrders(int)} — which closes each order in its own
 * transaction — and logs a one-line summary. Anything worth alarming on is logged by the service;
 * an empty run is not logged at all, because "no expired order" is the normal state and not an
 * incident.
 *
 * <p>{@code fixedDelay} rather than {@code fixedRate}: the next run starts the configured interval
 * after the previous one <em>finished</em>, so a slow backlog cannot pile up overlapping runs in the
 * scheduler thread. Overlap between instances is handled by {@code FOR UPDATE SKIP LOCKED}, not by a
 * distributed lock — two instances that start together simply split the rows.
 *
 * <p>The bean is absent when {@code tokenx.order.timeout.enabled=false}, which is how tests turn the
 * background trigger off and call the scan directly. Nothing else in the application depends on this
 * class, so disabling it changes no behaviour except that expired orders wait for a manual run.
 */
@Component
@ConditionalOnProperty(
    prefix = "tokenx.order.timeout",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OrderTimeoutScheduler {

  private static final Logger log = LoggerFactory.getLogger(OrderTimeoutScheduler.class);

  private final OrderTimeoutService orderTimeoutService;
  private final OrderProperties orderProperties;

  public OrderTimeoutScheduler(
      OrderTimeoutService orderTimeoutService, OrderProperties orderProperties) {
    this.orderTimeoutService = orderTimeoutService;
    this.orderProperties = orderProperties;
  }

  /**
   * Closes at most one batch of expired orders.
   *
   * <p>The interval placeholder mirrors {@link OrderProperties.Timeout#DEFAULT_INTERVAL}; the
   * property itself is validated at startup, so an unusable value fails the boot rather than
   * producing a trigger that never fires or fires continuously.
   */
  @Scheduled(fixedDelayString = "${tokenx.order.timeout.interval:30s}")
  public void closeExpiredOrders() {
    int closed = orderTimeoutService.closeExpiredOrders(orderProperties.timeout().maxOrdersPerRun());
    if (closed > 0) {
      log.info("closed {} expired order(s)", closed);
    }
  }
}
