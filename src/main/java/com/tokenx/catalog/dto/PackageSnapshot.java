package com.tokenx.catalog.dto;

/**
 * What a sale needs to know about a package, and nothing else.
 *
 * <p>Both channels read packages through {@code CatalogService} instead of touching the catalogue's
 * mapper or entity, so the modules share an explicit, narrow contract: the values the server is
 * allowed to write into an order snapshot (or into an activity's credit snapshot). Whether the
 * package may be sold is decided by the service before this snapshot is returned, which is why there
 * is no {@code onSale} flag to misread here.
 *
 * <p>A normal purchase writes all four values into the order, so the order never needs to join back
 * to the catalogue to learn what was bought or what it cost. An activity only needs {@link #id} and
 * {@link #creditAmount}; it takes its price from the admin and its name is read again at order time.
 *
 * @param id package id
 * @param name package name as currently stored by the server
 * @param creditAmount credits granted on purchase, as currently stored by the server
 * @param listPriceCent list price in integer cents, the price a normal purchase charges
 */
public record PackageSnapshot(long id, String name, long creditAmount, long listPriceCent) {}
