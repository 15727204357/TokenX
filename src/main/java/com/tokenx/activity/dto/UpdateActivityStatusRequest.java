package com.tokenx.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Admin request to publish or withdraw an activity.
 *
 * <p>{@code DRAFT} is accepted as a <em>value</em> on purpose: it is a real state, so asking for it
 * should be answered by the state machine ("no edge leads back to a draft", {@code 409
 * ACTIVITY_STATE_CONFLICT}) rather than by argument validation. Rejecting it here would hide the
 * reason and duplicate the transition rules in two places.
 *
 * <p>The value is a plain string rather than an enum so an unknown value produces the project's
 * stable {@code 400 VALIDATION_FAILED} instead of an unreadable-body error.
 *
 * @param status {@code DRAFT}, {@code PUBLISHED} or {@code OFFLINE} (exact spelling)
 */
public record UpdateActivityStatusRequest(
    @NotBlank(message = "status must not be blank")
        @Pattern(
            regexp = "DRAFT|PUBLISHED|OFFLINE",
            message = "status must be one of DRAFT, PUBLISHED, OFFLINE")
        String status) {}
