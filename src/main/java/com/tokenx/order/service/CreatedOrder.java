package com.tokenx.order.service;

import com.tokenx.order.model.PurchaseOrder;

/**
 * Outcome of an order-create request.
 *
 * <p>{@code created} is not a detail: {@code true} means this request performed the reservation and
 * answers {@code 201}; {@code false} means an earlier request with the same idempotency key already
 * produced this order, nothing was deducted this time, and the honest answer is a replayed {@code
 * 200}. Returning {@code 201} for a replay would tell the client a second order exists when it does
 * not.
 *
 * @param order the stored order, as the database now holds it
 * @param created whether this call created it
 */
public record CreatedOrder(PurchaseOrder order, boolean created) {}
