package com.tokenx.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login payload.
 *
 * <p>The username is not pattern-validated here: an unknown or malformed username must produce the
 * same generic failure as a wrong password, so validation must not distinguish the two cases.
 *
 * @param username login name, matched case-insensitively by the database collation
 * @param password raw password compared against the stored BCrypt digest
 */
public record LoginRequest(
    @NotBlank(message = "username must not be blank") @Size(max = 64) String username,
    @NotBlank(message = "password must not be blank") @Size(max = 72) String password) {}
