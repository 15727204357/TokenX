package com.tokenx.catalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin request to create a package. Only these three fields are accepted; the status is decided by
 * the server (a new package always starts off-shelf) and unknown client fields are ignored, so a
 * client cannot publish a package by sending {@code "status": "ON_SALE"}.
 *
 * <p>Amounts are bounded on both ends by {@link PackageLimits}, so every value the API accepts is
 * exactly representable by the browser.
 *
 * @param name display name; surrounding whitespace is removed before validation, so the 96 character
 *     limit applies to the value that would actually be stored
 * @param creditAmount credits granted on purchase, a positive integer within
 *     {@link PackageLimits#MAX_CREDIT_AMOUNT}
 * @param listPriceCent list price in integer cents (never a floating point amount), within
 *     {@link PackageLimits#MAX_LIST_PRICE_CENT}
 */
public record CreatePackageRequest(
    @NotBlank(message = "name must not be blank")
        @Size(max = 96, message = "name must be at most 96 characters")
        String name,
    @NotNull(message = "creditAmount is required")
        @Min(value = PackageLimits.MIN_CREDIT_AMOUNT, message = "creditAmount must be at least {value}")
        @Max(value = PackageLimits.MAX_CREDIT_AMOUNT, message = "creditAmount must be at most {value}")
        Long creditAmount,
    @NotNull(message = "listPriceCent is required")
        @Min(value = PackageLimits.MIN_LIST_PRICE_CENT, message = "listPriceCent must be at least {value}")
        @Max(value = PackageLimits.MAX_LIST_PRICE_CENT, message = "listPriceCent must be at most {value}")
        Long listPriceCent) {

  public CreatePackageRequest {
    name = name == null ? null : name.trim();
  }
}
