package com.tokenx.messaging.model;

import java.time.Instant;
import java.util.UUID;

/** Versioned fact that a local payment transaction committed successfully. */
public record OrderPaidEvent(
    int schemaVersion,
    UUID eventId,
    String eventType,
    String orderNo,
    String orderType,
    long payAmountCent,
    long creditAmount,
    Instant paidAt) {}
