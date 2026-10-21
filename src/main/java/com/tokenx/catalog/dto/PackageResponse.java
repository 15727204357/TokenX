package com.tokenx.catalog.dto;

import com.tokenx.catalog.model.TokenPackage;

/**
 * Package as seen by visitors and admins. Exactly five fields: internal columns such as
 * {@code created_at} stay inside the server.
 *
 * @param id package id
 * @param name stored display name
 * @param creditAmount credits granted on purchase
 * @param listPriceCent list price in integer cents
 * @param status {@code ON_SALE} or {@code OFF_SHELF}; the value is the server's, not the client's
 */
public record PackageResponse(
    Long id, String name, Long creditAmount, Long listPriceCent, String status) {

  public static PackageResponse from(TokenPackage tokenPackage) {
    return new PackageResponse(
        tokenPackage.getId(),
        tokenPackage.getName(),
        tokenPackage.getCreditAmount(),
        tokenPackage.getListPriceCent(),
        tokenPackage.getStatus().name());
  }
}
