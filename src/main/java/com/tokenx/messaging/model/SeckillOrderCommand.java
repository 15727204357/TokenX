package com.tokenx.messaging.model;

import java.time.Instant;
import java.util.UUID;

/** Versioned command placed on the single asynchronous order queue. */
public record SeckillOrderCommand(int schemaVersion, UUID requestId, long activityId, long userId, Instant admittedAt) {}
