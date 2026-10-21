package com.tokenx.order.timeout;

import com.tokenx.activity.mapper.ActivityInventoryMapper;
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
 * <p><b>One order, one transaction.</b> Each call to {@link #closeNextExpiredOrder()} opens its own
 * transaction, claims one row, closes it, settles the stock and commits. Nothing is batched: a
 * transaction that closed twenty orders would hold twenty order locks and one stock lock for the
 * length of the whole batch, and a failure at the end would undo work that had already been
 * reported. A batch is a loop of such transactions, which is also why a crashed worker leaves a
 * fully consistent table — some orders closed, the rest still unpaid and still reserved.
 *
 * <p><b>Lock order is the payment's lock order</b> ({@code purchase_order → activity_inventory}).
 * Both paths take the order row first, so neither can be waiting for a stock row while the other
 * waits for the order it belongs to. The claim uses {@code FOR UPDATE SKIP LOCKED}, so a worker that
 * loses the race for a row does not queue behind the winner; it moves on.
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
  private final TransactionTemplate writeTemplate;

  public OrderTimeoutService(
      PurchaseOrderMapper orderMapper,
      ActivityInventoryMapper inventoryMapper,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.orderMapper = orderMapper;
    this.inventoryMapper = inventoryMapper;
    this.clock = clock;
    // Explicit boundary for the same reason as payment: the transaction body is what a reviewer has
    // to be able to read as one list of statements, and each call here must be exactly one of them.
    this.writeTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Closes up to {@code maxOrders} expired orders, each in its own transaction.
   *
   * <p>The loop is bounded twice: by the batch size, and by the scan itself returning no row. It
   * stops on the first failure as well — a row that cannot be closed (for example because the stock
   * it should release is gone) would be claimed again immediately by the next iteration, because the
   * failed transaction released its lock without changing the row. Retrying it in place would spin
   * on the same order forever, so the run ends and the failure is logged; the next trigger tries
   * again, which is bounded by the interval instead of by a loop.
   *
   * @param maxOrders batch size; a non-positive value closes nothing
   * @return how many orders this run closed
   */
  public int closeExpiredOrders(int maxOrders) {
    int closed = 0;
    while (closed < maxOrders) {
      try {
        if (!closeNextExpiredOrder()) {
          break;
        }
      } catch (RuntimeException failure) {
        log.error("order timeout run stopped after closing {} order(s)", closed, failure);
        break;
      }
      closed++;
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
    return Boolean.TRUE.equals(writeTemplate.execute(status -> closeOne()));
  }

  // --- transaction body -------------------------------------------------------------------------

  private boolean closeOne() {
    // One clock reading for the whole closure: the claim compares deadlines against it, and
    // closed_at and updated_at both describe that same instant. A second reading could stamp a
    // closure later than the deadline it was justified by.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    LocalDateTime nowColumn = PurchaseOrder.toColumn(now);

    PurchaseOrder order = orderMapper.selectNextExpiredForUpdateSkipLocked(nowColumn);
    if (order == null) {
      return false;
    }

    // The claim chose this row; this statement is the gate. Zero rows means the row stopped being
    // expendable between the two statements, which no concurrent writer can cause while we hold the
    // lock — so it is an inconsistency, not a race, and closing it anyway would be a guess.
    if (orderMapper.markClosed(order.getId(), nowColumn) != 1) {
      throw brokenInvariant("order " + order.getOrderNo() + " refused to become CLOSED");
    }

    if (order.getOrderType() == OrderType.SECKILL) {
      releaseReservation(order);
    }

    log.debug("closed expired order orderNo={} type={}", order.getOrderNo(), order.getOrderType());
    return true;
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
      throw brokenInvariant(
          "a seckill order has no activity to release stock for: " + order.getOrderNo());
    }
    if (inventoryMapper.releaseReserved(activityId) != 1) {
      throw brokenInvariant(
          "activity "
              + activityId
              + " has no reservation to release for order "
              + order.getOrderNo());
    }
  }

  /**
   * An inconsistency the closure cannot resolve.
   *
   * <p>Logged with the order number only — never the buyer, the balance or a request body — and
   * thrown so the transaction rolls back. What matters is that the order stays {@code UNPAID} and
   * the stock stays as it was, rather than the two drifting apart.
   */
  private IllegalStateException brokenInvariant(String detail) {
    log.error("order timeout invariant broken: {}", detail);
    return new IllegalStateException(detail);
  }
}
