package com.tokenx.messaging.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls durable events when Outbox delivery is enabled. */
@Component
@ConditionalOnProperty(
    prefix = "tokenx.messaging", name = "outbox-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPollingTask {

  private final OutboxPublisher outboxPublisher;

  public OutboxPollingTask(OutboxPublisher outboxPublisher) {
    this.outboxPublisher = outboxPublisher;
  }

  @Scheduled(fixedDelayString = "${tokenx.messaging.outbox-poll-interval:1s}")
  public void publishDueEvents() {
    outboxPublisher.publishBatch();
  }
}
