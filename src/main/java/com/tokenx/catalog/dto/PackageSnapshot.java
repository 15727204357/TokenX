package com.tokenx.catalog.dto;

/**
 * What an activity needs to know about a package, and nothing else.
 *
 * <p>Activities read packages through {@code CatalogService} instead of touching the catalogue's
 * mapper or entity, so the two modules share an explicit, narrow contract: the server-side credit
 * amount to snapshot. Whether the package may be sold is decided by the service before this snapshot
 * is returned, which is why there is no {@code onSale} flag to misread here.
 *
 * @param id package id
 * @param creditAmount credits granted on purchase, as currently stored by the server
 */
public record PackageSnapshot(long id, long creditAmount) {}
