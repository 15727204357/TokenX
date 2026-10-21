package com.tokenx.activity.dto;

import com.tokenx.infrastructure.money.AmountLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * Admin request to create an activity. Exactly these five fields are accepted.
 *
 * <p>A client cannot decide the credit amount, the configuration status, any stock quantity or the
 * purchase limit: those are server facts. Sending them is simply ignored, so they cannot be
 * tampered with.
 *
 * <p>Times are absolute instants in ISO-8601 with an explicit offset ({@code 2026-10-22T02:00:00Z}).
 * A local date-time without an offset is rejected, because the server would have to guess a zone.
 *
 * <p>The cross-field rules ({@code startAt < endAt}, {@code endAt > now}) need the injected clock, so
 * they are enforced by the service; everything a single field can decide is enforced here.
 *
 * @param packageId package being sold; must exist and be on sale
 * @param activityPriceCent activity price in integer cents, within {@link AmountLimits}
 * @param totalStock initial stock; {@code 0} is legal and means already sold out
 * @param startAt window start (inclusive); may be in the past, meaning "starts immediately"
 * @param endAt window end (exclusive); must be in the future
 */
public record CreateActivityRequest(
    @NotNull(message = "packageId is required")
        @Positive(message = "packageId must be positive")
        Long packageId,
    @NotNull(message = "activityPriceCent is required")
        @Min(value = AmountLimits.MIN_PRICE_CENT, message = "activityPriceCent must be at least {value}")
        @Max(value = AmountLimits.MAX_PRICE_CENT, message = "activityPriceCent must be at most {value}")
        Long activityPriceCent,
    @NotNull(message = "totalStock is required")
        @Min(value = ActivityLimits.MIN_TOTAL_STOCK, message = "totalStock must be at least {value}")
        @Max(value = ActivityLimits.MAX_TOTAL_STOCK, message = "totalStock must be at most {value}")
        Long totalStock,
    @NotNull(message = "startAt is required") Instant startAt,
    @NotNull(message = "endAt is required") Instant endAt) {}
