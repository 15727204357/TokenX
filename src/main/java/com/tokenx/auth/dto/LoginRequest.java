package com.tokenx.auth.dto;

import com.tokenx.infrastructure.validation.Utf8ByteLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login payload.
 *
 * <p>The username is not pattern-validated here: an unknown or malformed username must produce the
 * same generic failure as a wrong password, so validation must not distinguish the two cases.
 *
 * <p>The password is bounded by the same 72-byte UTF-8 limit as registration. Such a value can never
 * match a stored BCrypt digest, and rejecting it at the edge keeps the encoder (which throws on it)
 * out of the request path.
 *
 * @param username login name, matched case-insensitively by the database collation
 * @param password raw password compared against the stored BCrypt digest
 */
public record LoginRequest(
    @NotBlank(message = "username must not be blank") @Size(max = 64) String username,
    @NotBlank(message = "password must not be blank")
        @Utf8ByteLength(
            max = PasswordPolicy.MAX_BYTES,
            message = "password must be at most {max} bytes in UTF-8")
        String password) {}
