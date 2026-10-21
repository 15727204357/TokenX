package com.tokenx.catalog.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.catalog.dto.CreatePackageRequest;
import com.tokenx.catalog.dto.PackagePage;
import com.tokenx.catalog.dto.PackageResponse;
import com.tokenx.catalog.mapper.TokenPackageMapper;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.catalog.model.TokenPackage;
import com.tokenx.infrastructure.error.BusinessException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catalogue rules: what a new package looks like, how status transitions behave, and what visitors
 * are allowed to see.
 *
 * <p>Two invariants drive the code:
 *
 * <ul>
 *   <li>Visibility is decided by the query, not by the caller: every public read carries
 *       {@code status = ON_SALE} in its {@code WHERE} clause, so a withdrawn package cannot leak
 *       through a forgotten post-filter.
 *   <li>Taking a package off the shelf is a status change, never a delete, because activities and
 *       historical orders still point at the row.
 * </ul>
 */
@Service
public class CatalogService {

  /** Page size used when the caller does not ask for one. */
  public static final int DEFAULT_PAGE_SIZE = 20;

  /** Hard upper bound on a single page, so one request cannot ask for the whole table. */
  public static final int MAX_PAGE_SIZE = 100;

  static final String PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";

  private final TokenPackageMapper packageMapper;

  public CatalogService(TokenPackageMapper packageMapper) {
    this.packageMapper = packageMapper;
  }

  /**
   * Creates a package that is off-shelf until an admin publishes it. The status is never taken from
   * the request.
   */
  public PackageResponse create(CreatePackageRequest request) {
    TokenPackage tokenPackage = new TokenPackage();
    tokenPackage.setName(request.name());
    tokenPackage.setCreditAmount(request.creditAmount());
    tokenPackage.setListPriceCent(request.listPriceCent());
    tokenPackage.setStatus(PackageStatus.OFF_SHELF);

    packageMapper.insert(tokenPackage);
    return PackageResponse.from(tokenPackage);
  }

  /**
   * Publishes or withdraws a package.
   *
   * <p>Repeating the current status is a success that returns the current state, so an admin client
   * can retry safely. The update is conditional on the status that was just read: if another request
   * changed it in between, this one matches zero rows and the answer is still the real current state
   * rather than a corrupted transition.
   */
  @Transactional
  public PackageResponse changeStatus(long id, PackageStatus target) {
    TokenPackage current = packageMapper.selectById(id);
    if (current == null) {
      throw productNotFound();
    }
    if (current.getStatus() == target) {
      return PackageResponse.from(current);
    }

    packageMapper.update(
        null,
        Wrappers.<TokenPackage>lambdaUpdate()
            .eq(TokenPackage::getId, id)
            .eq(TokenPackage::getStatus, current.getStatus())
            .set(TokenPackage::getStatus, target));

    TokenPackage updated = packageMapper.selectById(id);
    if (updated == null) {
      throw productNotFound();
    }
    return PackageResponse.from(updated);
  }

  /**
   * One page of on-sale packages, newest first, keyed by id instead of an offset.
   *
   * <p>Reads {@code limit + 1} rows only to learn whether a further page exists; no {@code COUNT(*)}
   * is executed, because the page does not need to know how big the catalogue is.
   */
  public PackagePage findOnSale(Long cursor, int limit) {
    int size = Math.min(limit, MAX_PAGE_SIZE);

    List<TokenPackage> rows =
        packageMapper.selectList(
            Wrappers.<TokenPackage>lambdaQuery()
                .eq(TokenPackage::getStatus, PackageStatus.ON_SALE)
                .lt(cursor != null, TokenPackage::getId, cursor)
                .orderByDesc(TokenPackage::getId)
                // size is a server-side integer bounded by MAX_PAGE_SIZE, never client text.
                .last("LIMIT " + (size + 1)));

    boolean hasMore = rows.size() > size;
    List<TokenPackage> page = hasMore ? rows.subList(0, size) : rows;
    Long nextCursor = hasMore ? page.get(page.size() - 1).getId() : null;

    return new PackagePage(
        page.stream().map(PackageResponse::from).toList(), nextCursor, hasMore);
  }

  /**
   * Detail of an on-sale package. A missing package and a withdrawn one produce the same 404, so the
   * endpoint cannot be used to discover unpublished products.
   */
  public PackageResponse findOnSaleById(long id) {
    TokenPackage tokenPackage =
        packageMapper.selectOne(
            Wrappers.<TokenPackage>lambdaQuery()
                .eq(TokenPackage::getId, id)
                .eq(TokenPackage::getStatus, PackageStatus.ON_SALE));
    if (tokenPackage == null) {
      throw productNotFound();
    }
    return PackageResponse.from(tokenPackage);
  }

  private static BusinessException productNotFound() {
    return new BusinessException(HttpStatus.NOT_FOUND, PRODUCT_NOT_FOUND, "product not found");
  }
}
