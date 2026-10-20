package com.tokenx.auth.dto;

/**
 * Login result.
 *
 * @param accessToken signed JWT to send as {@code Authorization: Bearer <token>}
 * @param tokenType always {@code Bearer}
 * @param expiresInSeconds lifetime of the token in seconds
 */
public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {}
