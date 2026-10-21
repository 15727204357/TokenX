package com.tokenx.order.service;

import com.tokenx.activity.dto.ActivityOrderSnapshot;
import com.tokenx.activity.mapper.ActivityInventoryMapper;
import com.tokenx.activity.service.ActivityService;
import com.tokenx.catalog.service.CatalogService;
import com.tokenx.infrastructure.error.BusinessException;
import com.tokenx.order.config.OrderProperties;
import com.tokenx.order.dto.OrderResponse;
import com.tokenx.order.mapper.PurchaseOrderMapper;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.order.model.OrderType;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.order.support.IdempotencyKeys;
import com.tokenx.order.support.OrderNoGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Synchronous seckill ordering: MySQL is the only thing that decides who wins.
 *
 * <p>This is the strong-consistency baseline. There is no Redis pre-check, no message queue, no
 * local buffer and no distributed lock in the correctness path — every rule below is enforced by a
 * statement the database executes atomically, so the result is the same whether one buyer or a
 * thousand arrive at once.
 *
 * <p><b>The reservation order is fixed</b> and follows {@code DATABASE_DESIGN.md §5.1}:
 *
 * <ol>
 *   <li>look up {@code (user_id, request_id)} — a retry of the same request answers here and never
 *       touches stock, even if the activity has since ended or been withdrawn;
 *   <li>read the activity with {@code FOR SHARE} and check {@code PUBLISHED} and {@code now ∈
 *       [startAt, endAt)};
 *   <li>prepare the order number, the deadline and every snapshot <em>before</em> the stock lock, so
 *       the time between taking the stock row and committing contains two statements only;
 *   <li>conditional {@code UPDATE activity_inventory ... WHERE available_stock > 0} — the affected
 *       row count is the sold-out answer, never a previously read number;
 *   <li>{@code INSERT purchase_order}, then commit, then answer.
 * </ol>
 *
 * <p><b>Uniqueness is enforced once, by the database.</b> Two unique keys do the work: {@code
 * uk_user_request} replays a retried request, {@code uk_activity_user} makes "one order per user per
 * activity" permanent. There is deliberately no pre-check of {@code (activity_id, user_id)}: it could
 * never be authoritative, so for a serial re-buy it would only add a read to the hot path and leave
 * the real decision to the same unique key. The re-buy is stopped by the constraint, one unit of
 * stock is reserved and then rolled back with the failed insert, and the client gets {@code 409}.
 *
 * <p><b>A duplicate key rolls back the whole creation.</b> The insert is the last statement of the
 * transaction, so a conflict means the reservation taken in step 4 must disappear too. Swallowing
 * the exception and committing would leave stock consumed by an order that does not exist. The
 * transaction therefore rolls back completely and the cause is diagnosed <em>after</em> it ended, in
 * a fresh read, because inside the old one the winner's row may be invisible and the losing
 * transaction is doomed anyway.
 */
@Service
public class SeckillOrderService {

  static final String VALIDATION_FAILED = "VALIDATION_FAILED";

  /** The activity exists and is published, but there is no stock left. */
  static final String ACTIVITY_SOLD_OUT = "ACTIVITY_SOLD_OUT";

  /** This user already owns an order for this activity; the rule is permanent. */
  static final String ALREADY_PURCHASED = "ALREADY_PURCHASED";

  /** The same idempotency key was already used for a different activity. */
  static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

  static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";

  /**
   * Order numbers are random, so a collision is astronomically unlikely. It is still bounded: after
   * this many attempts the duplicate is reported as the real failure instead of retrying forever.
   */
  private static final int MAX_ORDER_NO_ATTEMPTS = 3;

  private final PurchaseOrderMapper orderMapper;
  private final ActivityInventoryMapper inventoryMapper;
  private final ActivityService activityService;
  private final CatalogService catalogService;
  private final OrderNoGenerator orderNoGenerator;
  private final OrderProperties orderProperties;
  private final Clock clock;
  private final TransactionTemplate writeTemplate;
  private final TransactionTemplate readTemplate;

  public SeckillOrderService(
      PurchaseOrderMapper orderMapper,
      ActivityInventoryMapper inventoryMapper,
      ActivityService activityService,
      CatalogService catalogService,
      OrderNoGenerator orderNoGenerator,
      OrderProperties orderProperties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.orderMapper = orderMapper;
    this.inventoryMapper = inventoryMapper;
    this.activityService = activityService;
    this.catalogService = catalogService;
    this.orderNoGenerator = orderNoGenerator;
    this.orderProperties = orderProperties;
    this.clock = clock;
    // Explicit boundaries rather than @Transactional: the duplicate-key diagnosis has to run after
    // the transaction has ended, and a self-invoking annotated method would silently join the very
    // transaction it is trying to escape.
    this.writeTemplate = new TransactionTemplate(transactionManager);
    this.readTemplate = new TransactionTemplate(transactionManager);
    this.readTemplate.setReadOnly(true);
  }

  /**
   * Creates one seckill order, or replays the one an earlier attempt already created.
   *
   * @param userId authenticated user, never taken from the request body
   * @param activityId activity being ordered
   * @param requestKey parsed {@code Idempotency-Key}
   * @return the order plus whether this call created it ({@code 201}) or replayed it ({@code 200})
   * @throws BusinessException {@code 404 ACTIVITY_NOT_FOUND}, {@code 409 ACTIVITY_NOT_ACTIVE},
   *     {@code 409 ACTIVITY_SOLD_OUT}, {@code 409 ALREADY_PURCHASED}, {@code 409
   *     IDEMPOTENCY_KEY_REUSED}
   */
  public CreatedOrder create(long userId, long activityId, UUID requestKey) {
    byte[] requestId = IdempotencyKeys.toBytes(requestKey);

    for (int attempt = 1; attempt <= MAX_ORDER_NO_ATTEMPTS; attempt++) {
      try {
        CreatedOrder created =
            writeTemplate.execute(status -> createInTransaction(userId, activityId, requestId));
        return Objects.requireNonNull(created, "order creation produced no result");
      } catch (DuplicateKeyException ex) {
        // The transaction is gone by now: rolled back, connection returned. Whatever we conclude
        // next is read from committed data.
        CreatedOrder replayed = resolveDuplicate(userId, activityId, requestId);
        if (replayed != null) {
          return replayed;
        }
        // Neither unique key explains the duplicate, so only `uk_order_no` is left. Try another
        // number; if the last attempt also collides, report the real failure instead of looping.
        if (attempt == MAX_ORDER_NO_ATTEMPTS) {
          throw ex;
        }
      }
    }
    throw new IllegalStateException("unreachable: the retry loop either returned or threw");
  }

  /**
   * The order behind one public order number, as seen by its owner.
   *
   * <p>Ownership is part of the query. A malformed number is a {@code 400} (it cannot exist), while
   * a well-formed one that matches nothing — because it is unknown <em>or</em> belongs to somebody
   * else — is the same {@code 404}, so the endpoint cannot be used to discover other buyers' orders.
   */
  public OrderResponse findOwnOrder(long userId, String orderNo) {
    if (!OrderNoGenerator.isCanonical(orderNo)) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          VALIDATION_FAILED,
          "orderNo must be 26 characters of Crockford Base32");
    }
    PurchaseOrder order =
        orderMapper.selectByOrderNoAndUser(OrderNoGenerator.canonical(orderNo), userId);
    if (order == null) {
      throw new BusinessException(HttpStatus.NOT_FOUND, ORDER_NOT_FOUND, "order not found");
    }
    return OrderResponse.from(order);
  }

  // --- transaction body -------------------------------------------------------------------------

  private CreatedOrder createInTransaction(long userId, long activityId, byte[] requestId) {
    // 1. A retried request is answered before anything is validated or deducted, so an order that
    //    was really created stays reachable even if the activity has ended in the meantime.
    PurchaseOrder existing = orderMapper.selectByUserAndRequest(userId, requestId);
    if (existing != null) {
      if (!Objects.equals(existing.getActivityId(), activityId)) {
        throw idempotencyKeyReused();
      }
      return new CreatedOrder(existing, false);
    }

    // 2. One clock reading for the whole creation: the window decision, created_at and expires_at
    //    must describe the same instant, and the client's clock has no say in any of them.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    ActivityOrderSnapshot activity = activityService.requireOrderable(activityId, now);

    // 3. Everything that will be written is prepared here, before the stock row is locked.
    PurchaseOrder order = newOrder(userId, activity, requestId, now);

    // 4. Reserve exactly one unit, or learn that there is none left.
    if (inventoryMapper.reserveOne(activityId) != 1) {
      throw new BusinessException(
          HttpStatus.CONFLICT, ACTIVITY_SOLD_OUT, "activity is sold out");
    }

    // 5. Last statement: the order itself. A conflict here aborts the reservation above.
    orderMapper.insert(order);
    return new CreatedOrder(order, true);
  }

  private PurchaseOrder newOrder(
      long userId, ActivityOrderSnapshot activity, byte[] requestId, Instant now) {
    PurchaseOrder order = new PurchaseOrder();
    order.setOrderNo(orderNoGenerator.next(now));
    order.setRequestId(requestId);
    order.setUserId(userId);
    order.setPackageId(activity.packageId());
    order.setActivityId(activity.activityId());
    order.setOrderType(OrderType.SECKILL);
    order.setPackageNameSnapshot(catalogService.packageNameFor(activity.packageId()));
    order.setCreditAmount(activity.creditAmount());
    order.setPayAmountCent(activity.activityPriceCent());
    order.setStatus(OrderStatus.UNPAID);
    order.setExpiresAt(PurchaseOrder.toColumn(now.plus(orderProperties.paymentTimeout())));
    order.setCreatedAt(PurchaseOrder.toColumn(now));
    order.setUpdatedAt(PurchaseOrder.toColumn(now));
    return order;
  }

  /**
   * Explains a duplicate key after the failed transaction ended.
   *
   * <p>Both reads share one read-only transaction so they observe the same committed state; two
   * independent statements could otherwise straddle a competing commit and pick the wrong answer.
   *
   * @return the order to replay, or {@code null} when neither unique key conflicts — which leaves
   *     {@code uk_order_no} as the only possible cause
   * @throws BusinessException {@code 409 IDEMPOTENCY_KEY_REUSED} or {@code 409 ALREADY_PURCHASED}
   */
  private CreatedOrder resolveDuplicate(long userId, long activityId, byte[] requestId) {
    return readTemplate.execute(
        status -> {
          PurchaseOrder byRequest = orderMapper.selectByUserAndRequest(userId, requestId);
          if (byRequest != null) {
            if (!Objects.equals(byRequest.getActivityId(), activityId)) {
              throw idempotencyKeyReused();
            }
            return new CreatedOrder(byRequest, false);
          }
          if (orderMapper.selectByActivityAndUser(activityId, userId) != null) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                ALREADY_PURCHASED,
                "user already has an order for this activity");
          }
          return null;
        });
  }

  private static BusinessException idempotencyKeyReused() {
    return new BusinessException(
        HttpStatus.CONFLICT,
        IDEMPOTENCY_KEY_REUSED,
        "Idempotency-Key was already used for a different activity");
  }
}
