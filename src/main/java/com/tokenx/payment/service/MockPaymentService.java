package com.tokenx.payment.service;

import com.tokenx.activity.mapper.ActivityInventoryMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.TokenAccount;
import com.tokenx.infrastructure.error.BusinessException;
import com.tokenx.order.dto.OrderResponse;
import com.tokenx.order.mapper.PurchaseOrderMapper;
import com.tokenx.order.model.OrderStatus;
import com.tokenx.order.model.OrderType;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.order.support.OrderNoGenerator;
import com.tokenx.wallet.mapper.TokenLedgerMapper;
import com.tokenx.wallet.model.TokenLedger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mock payment: the one place an order becomes paid and a wallet gains credits.
 *
 * <p>This is a local simulation, not a gateway integration. There is no callback, no external order
 * number and no {@code payment_record}: the order's own snapshot is the whole truth, so "success"
 * means one MySQL transaction committed.
 *
 * <p><b>One transaction, four writes, one fixed order.</b> Everything below happens inside the same
 * transaction and on the same connection, so a failure anywhere leaves no trace anywhere:
 *
 * <ol>
 *   <li>{@code UPDATE purchase_order … WHERE status = 0 AND expires_at > now} — the state gate, and
 *       the order row's exclusive lock. An affected-row count of 1 means this caller won;
 *   <li>a current read of that row, which supplies the trusted snapshot: order type, activity and
 *       the credit amount. Nothing here comes from the request;
 *   <li>for a {@code SECKILL} order only, {@code reserved_stock - 1}: the reservation is settled, and
 *       {@code available_stock} must not move;
 *   <li>{@code available_balance + credits} on the account, then the posting that records it.
 * </ol>
 *
 * <p><b>Lock order is always the same</b> ({@code purchase_order → activity_inventory → token_account
 * → INSERT token_ledger}), which is what keeps two payments from waiting on each other: an order row
 * is taken before any row it depends on, and nothing downstream is ever acquired before the order it
 * belongs to. A payment never takes a lock first and asks for the order second.
 *
 * <p><b>Repeats and races produce no second effect.</b> The gate is the {@code status = 0} predicate:
 * however many callers arrive, exactly one can move the row out of {@code UNPAID}, and it holds the
 * row lock until commit. Everyone else waits, then matches nothing, then re-reads the row with a
 * current read (a plain read could answer from a snapshot taken before the winner committed) and
 * finds {@code PAID} — which is answered as a {@code 200} replay of the same order. No stock is
 * settled twice and no balance is credited twice, because those statements are simply not reached.
 *
 * <p><b>{@code uk_ledger_biz} is the second line of defence, not the first.</b> If a posting ever
 * collided, it would mean an order was credited while still unpaid — a contradiction. That is
 * reported as a failed transaction rather than swallowed: rolling back is the only honest answer,
 * and the unique key has still done its job by making the duplicate impossible to record.
 */
@Service
public class MockPaymentService {

  static final String VALIDATION_FAILED = "VALIDATION_FAILED";

  static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";

  static final String ORDER_STATE_CONFLICT = "ORDER_STATE_CONFLICT";

  private static final Logger log = LoggerFactory.getLogger(MockPaymentService.class);

  private final PurchaseOrderMapper orderMapper;
  private final ActivityInventoryMapper inventoryMapper;
  private final TokenAccountMapper accountMapper;
  private final TokenLedgerMapper ledgerMapper;
  private final Clock clock;
  private final TransactionTemplate writeTemplate;

  public MockPaymentService(
      PurchaseOrderMapper orderMapper,
      ActivityInventoryMapper inventoryMapper,
      TokenAccountMapper accountMapper,
      TokenLedgerMapper ledgerMapper,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.orderMapper = orderMapper;
    this.inventoryMapper = inventoryMapper;
    this.accountMapper = accountMapper;
    this.ledgerMapper = ledgerMapper;
    this.clock = clock;
    // Explicit boundary rather than @Transactional: the transaction body is the thing being
    // reviewed here, and it should be readable as one list of statements.
    this.writeTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Pays one of the caller's own orders, or replays the payment that already happened.
   *
   * <p>Both outcomes are {@code 200} with the same body, because from the client's point of view
   * the order is paid either way; what must never happen is a second credit.
   *
   * @param userId authenticated caller, never taken from a request body
   * @param orderNo public order number from the path
   * @return the order as it now stands, with {@code status = PAID} and {@code paidAt} set
   * @throws BusinessException {@code 400 VALIDATION_FAILED} for a malformed number, {@code 404
   *     ORDER_NOT_FOUND} when it is unknown or belongs to somebody else, {@code 409
   *     ORDER_STATE_CONFLICT} when the order is closed or its deadline has passed
   */
  public OrderResponse pay(long userId, String orderNo) {
    if (!OrderNoGenerator.isCanonical(orderNo)) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          VALIDATION_FAILED,
          "orderNo must be 26 characters of Crockford Base32");
    }

    String canonicalOrderNo = OrderNoGenerator.canonical(orderNo);
    return Objects.requireNonNull(
        writeTemplate.execute(status -> payInTransaction(userId, canonicalOrderNo)),
        "payment produced no result");
  }

  // --- transaction body -------------------------------------------------------------------------

  private OrderResponse payInTransaction(long userId, String orderNo) {
    // One clock reading for the whole payment: the gate compares against it, and paid_at, the
    // posting timestamp and updated_at all describe that same instant. The client's clock is not
    // consulted, and a second reading could let the payment be stamped after the deadline it passed.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    LocalDateTime nowColumn = PurchaseOrder.toColumn(now);

    // 1. The gate. 1 = this caller won and holds the order row lock; 0 = the outcome is already
    //    decided (paid, closed, expired) or the order is not this caller's.
    int won = orderMapper.markPaid(orderNo, userId, nowColumn);

    // 2. A current read, deliberately locking: under REPEATABLE READ a plain SELECT could answer
    //    from a snapshot taken before the winner committed and would call a paid order unpaid.
    PurchaseOrder order = orderMapper.selectByOrderNoAndUserForShare(orderNo, userId);

    if (order == null) {
      if (won == 1) {
        // The row was just updated, so it cannot be missing; committing the status change without
        // the credit would be worse than failing.
        throw brokenInvariant("order was marked paid but cannot be read back", orderNo);
      }
      throw orderNotFound();
    }

    if (won == 0) {
      return replayOrRefuse(order, now);
    }

    return credit(order, now);
  }

  /**
   * What a lost race means. Only one of these can be true, and none of them writes anything.
   *
   * <p>{@code PAID} is checked before the deadline on purpose: an order that was paid in time and
   * has since passed its deadline is still paid, and its replay must not turn into a conflict.
   */
  private OrderResponse replayOrRefuse(PurchaseOrder order, Instant now) {
    if (order.getStatus() == OrderStatus.PAID) {
      return OrderResponse.from(order);
    }
    if (order.getStatus() == OrderStatus.CLOSED) {
      throw new BusinessException(HttpStatus.CONFLICT, ORDER_STATE_CONFLICT, "order is closed");
    }
    if (!order.getExpiresInstant().isAfter(now)) {
      // Still UNPAID and late. F5 refuses to credit it; moving it to CLOSED and giving the
      // reservation back belongs to the timeout path (F6).
      throw new BusinessException(HttpStatus.CONFLICT, ORDER_STATE_CONFLICT, "order has expired");
    }
    // Unpaid, unexpired, and the gate still refused: the statement and the row disagree.
    throw brokenInvariant("the payment gate refused an unpaid, unexpired order", order.getOrderNo());
  }

  /**
   * Applies the effects of a payment this caller has just won.
   *
   * <p>Every step asserts its affected-row count. A {@code 0} is not a business answer here: stock,
   * account and posting are all guaranteed by earlier transactions, so a mismatch means the data is
   * inconsistent, and the whole payment — including the status change in step 1 — must disappear.
   */
  private OrderResponse credit(PurchaseOrder order, Instant now) {
    if (order.getOrderType() == OrderType.SECKILL) {
      settleReservation(order);
    }

    long balanceAfter = addCreditsAndRecord(order, now);

    log.debug("mock payment committed orderNo={} balanceAfter={}", order.getOrderNo(), balanceAfter);
    return OrderResponse.from(order);
  }

  /**
   * Turns the reserved unit into a sold one. A normal purchase has no activity, so it skips this
   * entirely — not even a read of {@code activity_inventory} happens for it.
   */
  private void settleReservation(PurchaseOrder order) {
    Long activityId = order.getActivityId();
    if (activityId == null) {
      throw brokenInvariant("a seckill order has no activity to settle", order.getOrderNo());
    }
    if (inventoryMapper.settleReserved(activityId) != 1) {
      throw brokenInvariant(
          "activity " + activityId + " has no reserved unit to settle", order.getOrderNo());
    }
  }

  /**
   * Adds the snapshot's credits and records the posting in the same transaction and connection.
   *
   * <p>The balance is read back rather than computed, so {@code balance_after} is what the database
   * actually holds — including whatever concurrent payments of this user committed first.
   *
   * @return the balance after the increase
   */
  private long addCreditsAndRecord(PurchaseOrder order, Instant now) {
    long creditAmount = order.getCreditAmount();
    long userId = order.getUserId();

    if (accountMapper.creditBalance(userId, creditAmount) != 1) {
      // Registration creates the account row; a payment must never create one lazily.
      throw brokenInvariant("user " + userId + " has no token_account row", order.getOrderNo());
    }

    TokenAccount account = accountMapper.selectById(userId);
    if (account == null) {
      throw brokenInvariant("token_account vanished between update and read", order.getOrderNo());
    }

    long balanceAfter = account.getAvailableBalance();
    ledgerMapper.insert(
        TokenLedger.orderCredit(userId, order.getId(), creditAmount, balanceAfter, now));
    return balanceAfter;
  }

  private static BusinessException orderNotFound() {
    return new BusinessException(HttpStatus.NOT_FOUND, ORDER_NOT_FOUND, "order not found");
  }

  /**
   * An inconsistency the payment cannot resolve.
   *
   * <p>Logged with the order number only — never the token, the account balance or a request body —
   * and thrown so the transaction rolls back. The generic handler turns it into a {@code 500}; what
   * matters is that no partial effect survives.
   */
  private IllegalStateException brokenInvariant(String detail, String orderNo) {
    log.error("payment invariant broken orderNo={} detail={}", orderNo, detail);
    return new IllegalStateException(detail + "; order " + orderNo);
  }
}
