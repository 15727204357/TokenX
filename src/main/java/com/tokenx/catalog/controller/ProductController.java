package com.tokenx.catalog.controller;

import com.tokenx.catalog.dto.PackagePage;
import com.tokenx.catalog.dto.PackageResponse;
import com.tokenx.catalog.service.CatalogService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public catalogue. Anonymous visitors may read it; both endpoints only ever return on-sale
 * packages.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

  private final CatalogService catalogService;

  public ProductController(CatalogService catalogService) {
    this.catalogService = catalogService;
  }

  /**
   * @param cursor last id of the previous page; omit it for the first page
   * @param limit page size, defaults to {@value CatalogService#DEFAULT_PAGE_SIZE} and is capped at
   *     {@value CatalogService#MAX_PAGE_SIZE}
   */
  @GetMapping
  public PackagePage list(
      @RequestParam(required = false) @Positive(message = "cursor must be a positive id") Long cursor,
      @RequestParam(required = false)
          @Min(value = 1, message = "limit must be between 1 and " + CatalogService.MAX_PAGE_SIZE)
          @Max(value = CatalogService.MAX_PAGE_SIZE, message = "limit must be between 1 and "
              + CatalogService.MAX_PAGE_SIZE)
          Integer limit) {
    int size = limit == null ? CatalogService.DEFAULT_PAGE_SIZE : limit;
    return catalogService.findOnSale(cursor, size);
  }

  @GetMapping("/{id}")
  public PackageResponse detail(@PathVariable @Positive(message = "id must be positive") long id) {
    return catalogService.findOnSaleById(id);
  }
}
