package com.tokenx.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Admin request to create a package. Only these three fields are accepted; the status is decided by
 * the server (a new package always starts off-shelf) and unknown client fields are ignored, so a
 * client cannot publish a package by sending {@code "status": "ON_SALE"}.
 *
 * @param name display name; surrounding whitespace is removed before validation, so the 96 character
 *     limit applies to the value that would actually be stored
 * @param creditAmount credits granted on purchase, a positive integer
 * @param listPriceCent list price in integer cents (never a floating point amount)
 */
public record CreatePackageRequest(
    @NotBlank(message = "name must not be blank")
        @Size(max = 96, message = "name must be at most 96 characters")
        String name,
    @NotNull(message = "creditAmount is required")
        @Positive(message = "creditAmount must be a positive integer")
        Long creditAmount,
    @NotNull(message = "listPriceCent is required")
        @Positive(message = "listPriceCent must be a positive integer")
        Long listPriceCent) {

  public CreatePackageRequest {
    name = name == null ? null : name.trim();
  }
}
