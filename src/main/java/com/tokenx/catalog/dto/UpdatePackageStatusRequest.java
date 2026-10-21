package com.tokenx.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Admin request to publish or withdraw a package.
 *
 * <p>The status is a plain string rather than an enum so that an unsupported value produces the
 * project's stable {@code 400 VALIDATION_FAILED} error instead of an unreadable-body error.
 *
 * @param status {@code ON_SALE} or {@code OFF_SHELF} (exact spelling)
 */
public record UpdatePackageStatusRequest(
    @NotBlank(message = "status must not be blank")
        @Pattern(regexp = "ON_SALE|OFF_SHELF", message = "status must be ON_SALE or OFF_SHELF")
        String status) {}
