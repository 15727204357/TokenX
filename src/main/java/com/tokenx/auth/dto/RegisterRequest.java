package com.tokenx.auth.dto;

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
 * @param password raw password, hashed with BCrypt before it is stored (72-byte BCrypt input limit)
 */
public record RegisterRequest(
    @NotBlank(message = "username must not be blank")
        @Pattern(
            regexp = "^[A-Za-z0-9_]{3,64}$",
            message = "username must be 3-64 characters of letters, digits or underscore")
        String username,
    @NotBlank(message = "password must not be blank")
        @Size(min = 8, max = 72, message = "password must be 8-72 characters")
        String password) {}
