package com.tokenx.auth.dto;

import com.tokenx.infrastructure.validation.Utf8ByteLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration payload.
 *
 * <p>There is deliberately no {@code role} or {@code status} field: a public registration can never
 * choose its own privileges, so extra client fields are ignored rather than honoured.
 *
 * @param username 3-64 characters, letters/digits/underscore only (docs/DATABASE_DESIGN.md §4.1)
 * @param password raw password, hashed with BCrypt before it is stored; bounded by BCrypt's 72-byte
 *     input limit, measured in UTF-8 bytes rather than characters
 */
public record RegisterRequest(
    @NotBlank(message = "username must not be blank")
        @Pattern(
            regexp = "^[A-Za-z0-9_]{3,64}$",
            message = "username must be 3-64 characters of letters, digits or underscore")
        String username,
    @NotBlank(message = "password must not be blank")
        @Size(
            min = PasswordPolicy.MIN_CHARS,
            message = "password must be at least {min} characters")
        @Utf8ByteLength(
            max = PasswordPolicy.MAX_BYTES,
            message = "password must be at most {max} bytes in UTF-8")
        String password) {}
