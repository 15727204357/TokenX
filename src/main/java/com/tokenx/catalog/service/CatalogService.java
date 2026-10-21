package com.tokenx.catalog.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.catalog.dto.CreatePackageRequest;
import com.tokenx.catalog.dto.PackagePage;
import com.tokenx.catalog.dto.PackageResponse;
import com.tokenx.catalog.dto.PackageSnapshot;
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

  /** A package that exists but is off the shelf cannot be sold through a new activity. */
  static final String PACKAGE_NOT_AVAILABLE = "PACKAGE_NOT_AVAILABLE";

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
   * <p>The row is locked with {@code SELECT ... FOR UPDATE} before its status is read, so concurrent
   * status changes of the same package are serialised by the database: the waiting transaction reads
   * the committed status rather than the snapshot it started with. Only that one primary key row is
   * locked, and no version column or cross-row lock is involved.
   *
   * <p>Repeating the current status returns the current state without executing any {@code UPDATE},
   * so an admin client can retry safely.
   */
  @Transactional
  public PackageResponse changeStatus(long id, PackageStatus target) {
    TokenPackage current = packageMapper.selectByIdForUpdate(id);
    if (current == null) {
      throw productNotFound();
    }
    if (current.getStatus() == target) {
      return PackageResponse.from(current);
    }

    packageMapper.updateStatus(id, target);
    current.setStatus(target);
    return PackageResponse.from(current);
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

  /**
   * Reads a package that is about to be sold, applying the catalogue's own rules, and returns the
   * server-side snapshot of it.
   *
   * <p>Two callers have the same question and must get the same answer: an activity is being created
   * or published for this package, or a buyer is purchasing it directly. It is deliberately not the
   * visitor-facing detail, because both have to tell "no such package" from "package exists but is
   * not on sale", and both answers belong to the catalogue — so the catalogue decides them here
   * instead of leaking two error codes into another module.
   *
   * <p>The row is read with {@code FOR SHARE} inside the caller's transaction, so a concurrent
   * withdraw (which takes {@code FOR UPDATE} on the same primary key) cannot commit between the check
   * and the write that depends on it: the waiting transaction reads the committed status instead of
   * the snapshot it started with. Many buyers share the lock, so they still run in parallel.
   *
   * @throws BusinessException {@code 404 PRODUCT_NOT_FOUND} when the package does not exist,
   *     {@code 409 PACKAGE_NOT_AVAILABLE} when it is not on sale
   */
  @Transactional
  public PackageSnapshot requireOnSalePackage(long packageId) {
    TokenPackage tokenPackage = packageMapper.selectByIdForShare(packageId);
    if (tokenPackage == null) {
      throw productNotFound();
    }
    if (tokenPackage.getStatus() != PackageStatus.ON_SALE) {
      throw new BusinessException(
          HttpStatus.CONFLICT, PACKAGE_NOT_AVAILABLE, "package is not on sale");
    }
    return new PackageSnapshot(
        tokenPackage.getId(),
        tokenPackage.getName(),
        tokenPackage.getCreditAmount(),
        tokenPackage.getListPriceCent());
  }

  /**
   * The package name an order copies into its snapshot.
   *
   * <p>Deliberately not {@link #requireOnSalePackage}: withdrawing a package must not
   * invalidate an activity that is already published, so ordering must not re-check saleability.
   * It is a plain consistent read with no lock, because the order only needs the text — the price
   * and the credit amount come from the activity's own snapshot, and a concurrent rename may
   * land before or after this read without changing what the buyer agreed to.
   *
   * <p>Packages are never deleted (they are taken off the shelf by status), so a missing row here
   * means the data is inconsistent rather than that the caller did something wrong.
   */
  public String packageNameFor(long packageId) {
    TokenPackage tokenPackage = packageMapper.selectById(packageId);
    if (tokenPackage == null) {
      throw new IllegalStateException(
          "package " + packageId + " is referenced by an activity but no longer exists");
    }
    return tokenPackage.getName();
  }

  private static BusinessException productNotFound() {
    return new BusinessException(HttpStatus.NOT_FOUND, PRODUCT_NOT_FOUND, "product not found");
  }
}
