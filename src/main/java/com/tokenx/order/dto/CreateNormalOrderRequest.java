package com.tokenx.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code POST /orders}: the one thing a normal purchase lets a client choose.
 *
 * <p>Everything else about the order — the package name, the credit amount, the price, the status and
 * the payment deadline — is read by the server and snapshotted. None of it is accepted here, so none
 * of it can be tampered with. A body without {@code packageId}, or with a non-positive one, is a
 * {@code 400 VALIDATION_FAILED}.
 *
 * @param packageId package being bought; must be a positive id
 */
public record CreateNormalOrderRequest(
    @NotNull(message = "packageId is required") @Positive(message = "packageId must be positive")
        Long packageId) {}
