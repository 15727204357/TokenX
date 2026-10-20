package com.tokenx.infrastructure.web;

/**
 * Single error envelope for every failure (PROJECT_SPEC §7).
 *
 * @param code stable machine-readable code, e.g. {@code USERNAME_ALREADY_EXISTS}
 * @param message human-readable message with no credential or account-existence detail
 * @param requestId correlation id, also returned in the {@code X-Request-Id} header
 */
public record ApiError(String code, String message, String requestId) {}
