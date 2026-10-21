package com.tokenx.catalog.controller;

import com.tokenx.catalog.dto.CreatePackageRequest;
import com.tokenx.catalog.dto.PackageResponse;
import com.tokenx.catalog.dto.UpdatePackageStatusRequest;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.catalog.service.CatalogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin package management. Access is enforced by the security filter chain ({@code ROLE_ADMIN}); the
 * controller only validates the payload and maps the result to a status code.
 */
@RestController
@RequestMapping("/api/v1/admin/products")
public class AdminProductController {

  private final CatalogService catalogService;

  public AdminProductController(CatalogService catalogService) {
    this.catalogService = catalogService;
  }

  /** Creates an off-shelf package; publishing is a separate, explicit step. */
  @PostMapping
  public ResponseEntity<PackageResponse> create(@Valid @RequestBody CreatePackageRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.create(request));
  }

  /** Publishes or withdraws a package. Repeating the current status is a success. */
  @PatchMapping("/{id}/status")
  public PackageResponse changeStatus(
      @PathVariable long id, @Valid @RequestBody UpdatePackageStatusRequest request) {
    return catalogService.changeStatus(id, PackageStatus.valueOf(request.status()));
  }
}
