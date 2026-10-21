package com.tokenx.catalog.dto;

import java.util.List;

/**
 * One page of on-sale packages, ordered by descending id.
 *
 * <p>Paging is cursor based ({@code id < cursor}) instead of {@code OFFSET}, so walking deep into
 * the catalogue does not get slower as the table grows. {@code nextCursor} is the id to send back to
 * fetch the following page, and is {@code null} exactly when there is nothing left — the client
 * never has to guess a cursor.
 *
 * @param items at most {@code limit} packages
 * @param nextCursor id to pass as {@code cursor} for the next page, or {@code null} at the end
 * @param hasMore whether another page exists
 */
public record PackagePage(List<PackageResponse> items, Long nextCursor, boolean hasMore) {}
