package com.tokenx.activity.dto;

/**
 * What an order needs to know about an activity, and nothing else.
 *
 * <p>Ordering reads activities through {@code ActivityService} instead of touching the activity
 * table, so the two modules share one explicit contract. Everything here is a server fact read
 * inside the order transaction: the price the buyer pays, the credits the activity promises, and
 * the package that produced it.
 *
 * <p>Stock is deliberately absent. It is consumed by a conditional update whose affected-row count
 * is the answer, not by reading a number that is stale the moment it is read.
 *
 * @param activityId activity the order belongs to
 * @param packageId package the activity sells
 * @param activityPriceCent price in integer cents, as snapshotted by the activity
 * @param creditAmount credits granted on purchase, as snapshotted by the activity
 */
public record ActivityOrderSnapshot(
    long activityId, long packageId, long activityPriceCent, long creditAmount) {}
