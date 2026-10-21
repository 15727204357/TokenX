package com.tokenx.order.service;

import com.tokenx.infrastructure.error.BusinessException;
import com.tokenx.order.mapper.PurchaseOrderMapper;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.order.support.OrderTarget;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The one idempotency lookup both sale channels perform, and the one refusal they share.
 *
 * <p>The rule is the same whether a unit of stock is at stake or not: an {@code Idempotency-Key}
 * buys one thing. A retry of the same request must answer with the order the first attempt created,
 * and the same key sent for anything else must be refused rather than silently resolved to a
 * different purchase.
 *
 * <p>Only the lookup is shared. What happens after it differs and stays in each service: a replayed
 * seckill order must not deduct stock again, a replayed normal order never dedicated any in the
 * first place, and the two services diagnose duplicate keys with different follow-up reads.
 */
@Component
public class OrderReplays {

  /** The same key was already used for a different order type or a different target. */
  public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

  private final PurchaseOrderMapper orderMapper;

  public OrderReplays(PurchaseOrderMapper orderMapper) {
    this.orderMapper = orderMapper;
  }

  /**
   * The order a previous attempt with this key produced, or {@code null} when there is none.
   *
   * <p>The query is scoped to the authenticated user, exactly like {@code uk_user_request}, so one
   * client's key can never resolve to another user's order.
   *
   * @throws BusinessException {@code 409 IDEMPOTENCY_KEY_REUSED} when the key already produced an
   *     order for something else
   */
  public PurchaseOrder find(long userId, byte[] requestId, OrderTarget target) {
    PurchaseOrder existing = orderMapper.selectByUserAndRequest(userId, requestId);
    if (existing == null) {
      return null;
    }
    if (!target.matches(existing)) {
      throw keyReused();
    }
    return existing;
  }

  /** {@code 409}: the key belongs to another purchase, so it cannot be reused here. */
  public static BusinessException keyReused() {
    return new BusinessException(
        HttpStatus.CONFLICT,
        IDEMPOTENCY_KEY_REUSED,
        "Idempotency-Key was already used for a different purchase");
  }
}
