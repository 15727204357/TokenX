package com.tokenx.order.controller;

import com.tokenx.infrastructure.security.CurrentUser;
import com.tokenx.infrastructure.web.ApiError;
import com.tokenx.infrastructure.web.RequestIdFilter;
import com.tokenx.order.dto.CreateNormalOrderRequest;
import com.tokenx.order.dto.OrderResponse;
import com.tokenx.order.service.CreatedOrder;
import com.tokenx.order.service.NormalOrderService;
import com.tokenx.order.service.OrderQueryService;
import com.tokenx.order.service.SeckillOrderService;
import com.tokenx.order.support.IdempotencyKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order endpoints: two ways to create, one way to read back.
 *
 * <p>The controller only adapts HTTP: it takes the caller's identity from the verified token, checks
 * the shape of the {@code Idempotency-Key}, and turns "was it created now or replayed" into {@code
 * 201} or {@code 200}. Every business rule lives in a service, so no rule can be bypassed by reaching
 * the service directly.
 *
 * <p>The two create endpoints are deliberately separate. They differ in what the caller has to name
 * (an activity, or a package), in the errors they can produce, and in whether stock is reserved — so
 * keeping them apart keeps two different transaction bodies apart. They share the order table, the
 * response shape and the query path, which is why {@code GET /orders/{orderNo}} is one endpoint for
 * both.
 *
 * <p>Neither create endpoint accepts a price, a credit amount, a package name, a status, a payment
 * deadline or a {@code userId}. {@code POST /activities/{id}/orders} has no body at all; {@code POST
 * /orders} accepts only {@code packageId}, because the package — unlike an activity id — cannot be
 * carried in the path of a collection endpoint and is the one thing the caller must choose.
 */
@RestController
@RequestMapping("/api/v1")
public class OrderController {

  private static final String VALIDATION_FAILED = "VALIDATION_FAILED";

  private final SeckillOrderService seckillOrderService;
  private final NormalOrderService normalOrderService;
  private final OrderQueryService orderQueryService;

  public OrderController(
      SeckillOrderService seckillOrderService,
      NormalOrderService normalOrderService,
      OrderQueryService orderQueryService) {
    this.seckillOrderService = seckillOrderService;
    this.normalOrderService = normalOrderService;
    this.orderQueryService = orderQueryService;
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
  public ResponseEntity<OrderResponse> createSeckillOrder(
      @PathVariable @Positive(message = "activityId must be positive") long activityId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @AuthenticationPrincipal Jwt jwt) {
    CurrentUser user = CurrentUser.from(jwt);
    UUID requestKey = IdempotencyKeys.parse(idempotencyKey);

    CreatedOrder created = seckillOrderService.create(user.id(), activityId, requestKey);
    HttpStatus status = created.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status).body(OrderResponse.from(created.order()));
  }

  /**
   * Buys one on-sale package directly, at its list price.
   *
   * <p>Same status semantics as the seckill endpoint: {@code 201} created the order, {@code 200}
   * replayed the one an earlier request with this key already created. A buyer may call this again
   * with a <em>new</em> key and own a second order for the same package; there is no per-package
   * limit, because there is no finite stock to protect.
   */
  @PostMapping("/orders")
  public ResponseEntity<OrderResponse> createNormalOrder(
      @Valid @RequestBody CreateNormalOrderRequest request,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @AuthenticationPrincipal Jwt jwt) {
    CurrentUser user = CurrentUser.from(jwt);
    UUID requestKey = IdempotencyKeys.parse(idempotencyKey);

    CreatedOrder created = normalOrderService.create(user.id(), request.packageId(), requestKey);
    HttpStatus status = created.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status).body(OrderResponse.from(created.order()));
  }

  /**
   * One of the caller's own orders, whatever channel created it.
   *
   * <p>Unknown and someone else's are the same {@code 404}.
   */
  @GetMapping("/orders/{orderNo}")
  public OrderResponse detail(@PathVariable String orderNo, @AuthenticationPrincipal Jwt jwt) {
    return orderQueryService.findOwnOrder(CurrentUser.from(jwt).id(), orderNo);
  }

  /**
   * A body that cannot be parsed is a bad request, not a server error.
   *
   * <p>Scoped to this controller on purpose. {@code POST /orders} is the only order endpoint with a
   * body, so its only possible parse failure is a client that sent the wrong thing — which the order
   * contract reports as {@code 400 VALIDATION_FAILED}, the same code as a body that parses but fails
   * validation. Handling it here keeps the global handler's {@code MALFORMED_REQUEST} behaviour for
   * the admin endpoints untouched.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleUnreadableBody(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(
            new ApiError(
                VALIDATION_FAILED,
                "request body must be {\"packageId\": <positive integer>}",
                RequestIdFilter.of(request)));
  }
}
