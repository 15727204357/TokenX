package com.tokenx.order.service;

import com.tokenx.catalog.dto.PackageSnapshot;
import com.tokenx.catalog.service.CatalogService;
import com.tokenx.order.mapper.PurchaseOrderMapper;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.order.support.IdempotencyKeys;
import com.tokenx.order.support.OrderTarget;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Selling a package directly, at list price, with no activity and no stock.
 *
 * <p>This is the second of the two purchase channels, and the smaller one. It shares the table, the
 * state machine, the query path and the payment deadline with {@link SeckillOrderService}; what it
 * does not share is the reason a seckill order is hard. There is no unit to reserve, so there is no
 * conditional update, no inventory row, and no {@code uk_activity_user} to fight: a buyer may own any
 * number of normal orders for the same package, and each one is a real purchase at the price that was
 * current when it was created.
 *
 * <p><b>The reservation order</b> is consequently short, and its first step is the important one:
 *
 * <ol>
 *   <li>look up {@code (user_id, request_id)} — a retry answers here, before the package is even
 *       read, so an order that was really created stays reachable after the package is withdrawn;
 *   <li>read the package with {@code FOR SHARE} and require {@code ON_SALE}, taking the name, the
 *       credit amount and the list price from that one locked row;
 *   <li>{@code INSERT purchase_order} — the last statement of the transaction.
 * </ol>
 *
 * <p><b>Nothing here touches {@code activity_inventory}</b>, not even to read it. A normal purchase
 * has no activity, so inventing a stock check would either couple the channel to a sale it is not
 * part of, or silently start consuming a reservation it can never give back.
 *
 * <p><b>A duplicate key rolls the whole creation back</b>, and the cause is diagnosed afterwards in a
 * fresh read-only transaction: inside the failed one the winner's row may still be invisible and the
 * transaction is doomed anyway. The only two unique keys this channel can hit are {@code
 * uk_user_request} — two requests with the same key racing — and {@code uk_order_no}. The first is
 * resolved to a replay, the second retried a bounded number of times with a new number.
 */
@Service
public class NormalOrderService {

  private final PurchaseOrderMapper orderMapper;
  private final CatalogService catalogService;
  private final OrderDraft orderDraft;
  private final OrderReplays orderReplays;
  private final Clock clock;
  private final TransactionTemplate writeTemplate;
  private final TransactionTemplate readTemplate;

  public NormalOrderService(
      PurchaseOrderMapper orderMapper,
      CatalogService catalogService,
      OrderDraft orderDraft,
      OrderReplays orderReplays,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.orderMapper = orderMapper;
    this.catalogService = catalogService;
    this.orderDraft = orderDraft;
    this.orderReplays = orderReplays;
    this.clock = clock;
    // Explicit boundaries rather than @Transactional, for the same reason as the seckill path: the
    // duplicate-key diagnosis must run after the failed transaction has ended.
    this.writeTemplate = new TransactionTemplate(transactionManager);
    this.readTemplate = new TransactionTemplate(transactionManager);
    this.readTemplate.setReadOnly(true);
  }

  /**
   * Creates one normal order, or replays the one an earlier attempt already created.
   *
   * @param userId authenticated user, never taken from the request body
   * @param packageId package being bought
   * @param requestKey parsed {@code Idempotency-Key}
   * @return the order plus whether this call created it ({@code 201}) or replayed it ({@code 200})
   * @throws com.tokenx.infrastructure.error.BusinessException {@code 400 VALIDATION_FAILED}, {@code
   *     404 PRODUCT_NOT_FOUND}, {@code 409 PACKAGE_NOT_AVAILABLE}, {@code 409
   *     IDEMPOTENCY_KEY_REUSED}
   */
  public CreatedOrder create(long userId, long packageId, UUID requestKey) {
    byte[] requestId = IdempotencyKeys.toBytes(requestKey);
    OrderTarget target = OrderTarget.normal(packageId);

    for (int attempt = 1; attempt <= OrderDraft.MAX_ORDER_NO_ATTEMPTS; attempt++) {
      try {
        CreatedOrder created =
            writeTemplate.execute(status -> createInTransaction(userId, target, requestId));
        return Objects.requireNonNull(created, "order creation produced no result");
      } catch (DuplicateKeyException ex) {
        // The transaction is gone: rolled back, connection returned. Everything below is read from
        // committed data.
        CreatedOrder replayed = resolveDuplicate(userId, target, requestId);
        if (replayed != null) {
          return replayed;
        }
        // The request key explains nothing, so only `uk_order_no` is left. Try another number; if
        // the last attempt collides too, report the real failure instead of looping.
        if (attempt == OrderDraft.MAX_ORDER_NO_ATTEMPTS) {
          throw ex;
        }
      }
    }
    throw new IllegalStateException("unreachable: the retry loop either returned or threw");
  }

  // --- transaction body -------------------------------------------------------------------------

  private CreatedOrder createInTransaction(long userId, OrderTarget target, byte[] requestId) {
    // 1. A retried request is answered before the package is validated, so an order created while
    //    the package was on sale stays reachable after it is withdrawn.
    PurchaseOrder existing = orderReplays.find(userId, requestId, target);
    if (existing != null) {
      return new CreatedOrder(existing, false);
    }

    // 2. One clock reading for the whole creation: created_at and expires_at must describe one
    //    instant, and the client's clock has no say in either.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);

    // 3. The locked read both authorises the sale and supplies the snapshot: a concurrent withdraw
    //    (FOR UPDATE on the same row) either waits for this transaction or is seen by it.
    PackageSnapshot tokenPackage = catalogService.requireOnSalePackage(target.packageId());

    // 4. Last statement, and the only write: no stock row, no activity, nothing to give back.
    PurchaseOrder order = orderDraft.buildNormal(userId, requestId, tokenPackage, now);
    orderMapper.insert(order);
    return new CreatedOrder(order, true);
  }

  /**
   * Explains a duplicate key after the failed transaction ended.
   *
   * @return the order to replay, or {@code null} when the request key explains nothing — which
   *     leaves {@code uk_order_no} as the only possible cause
   */
  private CreatedOrder resolveDuplicate(long userId, OrderTarget target, byte[] requestId) {
    return readTemplate.execute(
        status -> {
          PurchaseOrder existing = orderReplays.find(userId, requestId, target);
          return existing == null ? null : new CreatedOrder(existing, false);
        });
  }
}
