package com.tokenx.order.service;

import com.tokenx.infrastructure.error.BusinessException;
import com.tokenx.order.dto.OrderResponse;
import com.tokenx.order.mapper.PurchaseOrderMapper;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.order.support.OrderNoGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Reads one order back, for both sale channels.
 *
 * <p>There is nothing channel-specific to read: the table, the state machine and the response are
 * shared, so a seckill order and a normal purchase are looked up by exactly the same statement. A
 * second copy of this logic would be a second answer to "may I see this order", which is the one
 * question that must never have two answers.
 *
 * <p>Ownership is part of the query. A malformed number is a {@code 400} (it cannot exist), while a
 * well-formed one that matches nothing — because it is unknown <em>or</em> belongs to somebody else —
 * is the same {@code 404}, so the endpoint cannot be used to discover other buyers' orders.
 */
@Service
public class OrderQueryService {

  static final String VALIDATION_FAILED = "VALIDATION_FAILED";

  static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";

  private final PurchaseOrderMapper orderMapper;

  public OrderQueryService(PurchaseOrderMapper orderMapper) {
    this.orderMapper = orderMapper;
  }

  /**
   * The order behind one public order number, as seen by its owner.
   *
   * @throws BusinessException {@code 400 VALIDATION_FAILED} for a non-canonical number, {@code 404
   *     ORDER_NOT_FOUND} when it exists for nobody the caller may see
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
}
