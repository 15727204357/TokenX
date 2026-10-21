package com.tokenx.payment.controller;

import com.tokenx.infrastructure.security.CurrentUser;
import com.tokenx.order.dto.OrderResponse;
import com.tokenx.payment.service.MockPaymentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The simulated payment endpoint.
 *
 * <p>It accepts no body at all. A payment needs exactly two facts — whose order, and which one — and
 * both come from the verified token and the path. A client that sends an amount, a credit count, a
 * status or a {@code userId} is ignored by construction: there is no argument for any of them, so
 * there is nothing to forget to validate.
 *
 * <p>{@code 200} covers both "just paid" and "was already paid"; the order's own {@code status} and
 * {@code paidAt} are the answer, and the second case never credits twice.
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {

  private final MockPaymentService mockPaymentService;

  public PaymentController(MockPaymentService mockPaymentService) {
    this.mockPaymentService = mockPaymentService;
  }

  @PostMapping("/orders/{orderNo}/mock-pay")
  public OrderResponse mockPay(@PathVariable String orderNo, @AuthenticationPrincipal Jwt jwt) {
    return mockPaymentService.pay(CurrentUser.from(jwt).id(), orderNo);
  }
}
