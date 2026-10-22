package com.tokenx.order.timeout;

import com.tokenx.activity.mapper.ActivityInventoryMapper;
import com.tokenx.activity.service.ActivityService;
import com.tokenx.order.mapper.PurchaseOrderMapper;
import com.tokenx.order.model.OrderType;
import com.tokenx.order.model.PurchaseOrder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes orders nobody paid for before their deadline, and gives back what they were holding.
 *
 * <p>This is the other half of what payment does. Payment and closure are two answers to one
 * question — did this order's deadline pass before somebody paid? — and they are kept apart by two
 * predicates that can never both hold: payment requires {@code expires_at > now}, closure requires
 * {@code expires_at <= now}. Whichever runs second therefore finds a row that no longer matches and
 * reports a business answer instead of acting.
 *
 * <p><b>One order, one transaction — and always a new one.</b> The boundary is
 * {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW}, not the default {@code REQUIRED}: a batch
 * invoked from inside somebody else's transaction must still commit each order separately. With
 * {@code REQUIRED} the orders would silently join the caller's transaction, so a later rollback
 * would undo closures that had already been reported, and the caller's transaction would hold one
 * order lock per order for its whole lifetime.
 *
 * <p><b>Lock order is the payment's lock order</b> ({@code purchase_order → activity_inventory}).
 * Both paths take the order row first, so neither can be waiting for a stock row while the other
 * waits for the order it belongs to. The claim uses {@code FOR UPDATE SKIP LOCKED}, so a worker that
 * loses the race for a row does not queue behind the winner; it moves on.
 *
 * <p><b>A broken order does not block the backlog.</b> An order that cannot be closed keeps its
 * {@code UNPAID} status and stays first in the scan order, so the run would claim it again on the
 * very next iteration and fail on it forever. Instead the run remembers where it failed and continues
 * from just after that row, which lets every healthy order behind it be closed in the same run. The
 * broken row is not deleted, not hidden and not forced closed: it is the first candidate again on
 * the next run, and its failure is logged every time until somebody fixes the data.
 *
 * <p>Nothing here credits, posts or deletes. A closed order keeps {@code paid_at} empty, gains
 * {@code closed_at}, and stays in the table — the permanent {@code (activity_id, user_id)} key is
 * what keeps "one order per user per activity" true after a timeout, so the buyer cannot retry the
 * same sale by letting an order expire.
 */
@Service
public class OrderTimeoutService {

  private static final Logger log = LoggerFactory.getLogger(OrderTimeoutService.class);

  private final PurchaseOrderMapper orderMapper;
  private final ActivityInventoryMapper inventoryMapper;
  private final Clock clock;
  private final ActivityService activityService;
  private final TransactionTemplate writeTemplate;

  public OrderTimeoutService(
      PurchaseOrderMapper orderMapper,
      ActivityInventoryMapper inventoryMapper,
      ActivityService activityService,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.orderMapper = orderMapper;
    this.inventoryMapper = inventoryMapper;
    this.clock = clock;
    this.activityService = activityService;
    // Explicit boundary for the same reason as payment: the transaction body is what a reviewer has
    // to be able to read as one list of statements, and each call here must be exactly one of them.
    this.writeTemplate = new TransactionTemplate(transactionManager);
    // REQUIRES_NEW, not the default REQUIRED: "one order, one transaction" has to hold even when a
    // caller already has a transaction open. Joining it would let a caller's rollback undo closures
    // this method has already committed and reported.
    this.writeTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Closes expired orders, one per transaction, until the budget runs out or the backlog is empty.
   *
   * <p>{@code maxAttempts} bounds <em>claims</em>, not successes: a broken order consumes a slot and
   * so does a healthy one. That is what keeps a run bounded when the front of the backlog is
   * unhealthy — otherwise "one failure per iteration" would either be unbounded or permanently block
   * everything behind the failure.
   *
   * <p>Two kinds of failure are treated differently, on purpose:
   *
   * <ul>
   *   <li>an order whose own invariants are broken (its reservation is missing, or it refused to
   *       change state) is rolled back, logged, and skipped by moving the cursor past it — the rest
   *       of the run still closes what it can;
   *   <li>anything else — the claim statement itself failing, the connection dying, the transaction
   *       manager refusing to start — propagates. At that point there is no row to move past, and
   *       pretending the run was simply empty would hide an outage.
   * </ul>
   *
   * <p>The cursor only lives for this call. The next run starts from the beginning of the scan, so a
   * broken order is retried every time and is never quietly skipped forever.
   *
   * @param maxAttempts how many orders this run may claim, successful or not; non-positive does nothing
   * @return how many orders this run closed
   */
  public int closeExpiredOrders(int maxAttempts) {
    int closed = 0;
    int attempts = 0;
    LocalDateTime cursorExpiresAt = null;
    Long cursorId = null;

    while (attempts < maxAttempts) {
      attempts++;
      try {
        PurchaseOrder closedOrder = closeOne(cursorExpiresAt, cursorId);
        if (closedOrder == null) {
          break;
        }
        closed++;
        cursorExpiresAt = closedOrder.getExpiresAt();
        cursorId = closedOrder.getId();
      } catch (OrderTimeoutFailure failure) {
        // The row is intact and still UNPAID: only its own transaction rolled back. Move the cursor
        // past it so the healthy orders behind it are not starved by it.
        log.error(
            "order {} failed to close; skipping it for the rest of this run (attempt {} of {})",
            failure.orderId(),
            attempts,
            maxAttempts,
            failure);
        cursorExpiresAt = failure.expiresAtColumn();
        cursorId = failure.orderId();
      }
    }
    return closed;
  }

  /**
   * Closes the oldest expired order, if there is one, in a transaction of its own.
   *
   * <p>Returns {@code false} when the scan finds nothing — an empty backlog, which is the normal case
   * and not an error. Any inconsistency found on the way out is thrown instead, so the transaction
   * rolls back and no half-closed order survives.
   *
   * @return whether an order was closed
   */
  public boolean closeNextExpiredOrder() {
    return closeOne(null, null) != null;
  }

  // --- transaction body -------------------------------------------------------------------------

  /**
   * One claim-and-close inside one {@code REQUIRES_NEW} transaction.
   *
   * @param afterExpiresAt deadline of the row this run last attempted, {@code null} for the first
   * @param afterId id of the row this run last attempted, {@code null} for the first
   * @return the order that was closed, or {@code null} when nothing expired is left
   */
  private PurchaseOrder closeOne(LocalDateTime afterExpiresAt, Long afterId) {
    PurchaseOrder closed = writeTemplate.execute(status -> closeInTransaction(afterExpiresAt, afterId));
    if (closed != null && closed.getOrderType() == OrderType.SECKILL) {
      activityService.invalidatePublicCacheAfterCommit(closed.getActivityId());
    }
    return closed;
  }

  private PurchaseOrder closeInTransaction(LocalDateTime afterExpiresAt, Long afterId) {
    // One clock reading for the whole closure: the claim compares deadlines against it, and
    // closed_at and updated_at both describe that same instant. A second reading could stamp a
    // closure later than the deadline it was justified by.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    LocalDateTime nowColumn = PurchaseOrder.toColumn(now);

    PurchaseOrder order = orderMapper.selectNextExpiredAfter(nowColumn, afterExpiresAt, afterId);
    if (order == null) {
      return null;
    }

    // The claim chose this row; this statement is the gate. Zero rows means the row stopped being
    // expendable between the two statements, which no concurrent writer can cause while we hold the
    // lock — so it is an inconsistency, not a race, and closing it anyway would be a guess.
    if (orderMapper.markClosed(order.getId(), nowColumn) != 1) {
      throw brokenInvariant(order, "refused to become CLOSED");
    }

    if (order.getOrderType() == OrderType.SECKILL) {
      releaseReservation(order);
    }

    log.debug("closed expired order orderNo={} type={}", order.getOrderNo(), order.getOrderType());
    return order;
  }

  /**
   * Gives the reservation back for a closed seckill order.
   *
   * <p>A normal purchase never had a reservation and has no activity, so it skips this entirely —
   * not even a read of {@code activity_inventory} happens for it.
   */
  private void releaseReservation(PurchaseOrder order) {
    Long activityId = order.getActivityId();
    if (activityId == null) {
      throw brokenInvariant(order, "seckill order has no activity to release stock for");
    }
    if (inventoryMapper.releaseReserved(activityId) != 1) {
      throw brokenInvariant(
          order, "activity " + activityId + " has no reservation to release");
    }
  }

  /**
   * An inconsistency that belongs to one order, not to the run.
   *
   * <p>Carries the row's {@code (expires_at, id)} so the batch can continue after it. It extends
   * {@link IllegalStateException} because that is what "an invariant this code cannot repair" has
   * always been reported as here; the cursor is extra information for the caller, not a new failure
   * category. Logged with identifiers only — never the buyer, the balance or a request body.
   */
  private static final class OrderTimeoutFailure extends IllegalStateException {

    private final LocalDateTime expiresAtColumn;
    private final long orderId;

    OrderTimeoutFailure(PurchaseOrder order, String detail) {
      super(detail + "; orderNo=" + order.getOrderNo());
      this.expiresAtColumn = order.getExpiresAt();
      this.orderId = order.getId();
    }

    LocalDateTime expiresAtColumn() {
      return expiresAtColumn;
    }

    long orderId() {
      return orderId;
    }
  }

  /** Reports one order's broken invariant with the coordinates the batch needs to move past it. */
  private OrderTimeoutFailure brokenInvariant(PurchaseOrder order, String detail) {
    OrderTimeoutFailure failure = new OrderTimeoutFailure(order, detail);
    log.error("order timeout invariant broken: {}", failure.getMessage());
    return failure;
  }
}
