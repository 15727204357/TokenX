package com.tokenx.order.controller;

import com.tokenx.infrastructure.security.CurrentUser;
import com.tokenx.order.dto.OrderResponse;
import com.tokenx.order.service.CreatedOrder;
import com.tokenx.order.service.SeckillOrderService;
import com.tokenx.order.support.IdempotencyKeys;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order endpoints: one creates, one reads back.
 *
 * <p>The controller only adapts HTTP: it takes the caller's identity from the verified token, checks
 * the shape of the {@code Idempotency-Key}, and turns "was it created now or replayed" into {@code
 * 201} or {@code 200}. Every business rule lives in {@link SeckillOrderService}, so no rule can be
 * bypassed by reaching the service directly.
 *
 * <p>There is no request body on purpose. The activity decides the price, the credits and the
 * deadline, and the token decides who is buying; a body would only give a client something to
 * tamper with. {@code userId} is never read from the request.
 *
 * <p>Neither path is publicly readable: {@code POST /activities/{id}/orders} is not covered by the
 * anonymous {@code GET} rule, and {@code /orders/**} requires a token like any other endpoint.
 */
@RestController
@RequestMapping("/api/v1")
public class OrderController {

  private final SeckillOrderService seckillOrderService;

  public OrderController(SeckillOrderService seckillOrderService) {
    this.seckillOrderService = seckillOrderService;
  }

  /**
   * Buys one unit of an activity.
   *
   * <p>{@code 201} means this request created the order and reserved the stock; {@code 200} means an
   * earlier request with the same key did, and this one replayed it without touching stock. The two
   * are distinguishable so a client can tell "I bought it" from "I already had bought it" without
   * calling again.
   */
  @PostMapping("/activities/{activityId}/orders")
  public ResponseEntity<OrderResponse> create(
      @PathVariable @Positive(message = "activityId must be positive") long activityId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @AuthenticationPrincipal Jwt jwt) {
    CurrentUser user = CurrentUser.from(jwt);
    UUID requestKey = IdempotencyKeys.parse(idempotencyKey);

    CreatedOrder created = seckillOrderService.create(user.id(), activityId, requestKey);
    HttpStatus status = created.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status).body(OrderResponse.from(created.order()));
  }

  /** One of the caller's own orders. Unknown and someone else's are the same {@code 404}. */
  @GetMapping("/orders/{orderNo}")
  public OrderResponse detail(@PathVariable String orderNo, @AuthenticationPrincipal Jwt jwt) {
    return seckillOrderService.findOwnOrder(CurrentUser.from(jwt).id(), orderNo);
  }
}
