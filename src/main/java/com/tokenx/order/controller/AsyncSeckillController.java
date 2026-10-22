package com.tokenx.order.controller;

import com.tokenx.infrastructure.security.CurrentUser;
import com.tokenx.order.dto.OrderRequestResponse;
import com.tokenx.order.service.AsyncSeckillService;
import com.tokenx.order.support.IdempotencyKeys;
import java.util.UUID;
import jakarta.validation.constraints.Positive;
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

@RestController
@RequestMapping("/api/v1")
public class AsyncSeckillController {
  private final AsyncSeckillService service;
  public AsyncSeckillController(AsyncSeckillService service){this.service=service;}
  @PostMapping("/activities/{activityId}/order-requests")
  public ResponseEntity<OrderRequestResponse> submit(@PathVariable @Positive long activityId,@RequestHeader(name=IdempotencyKeys.HEADER,required=false) String key,@AuthenticationPrincipal Jwt jwt){
    UUID request=IdempotencyKeys.parse(key);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.submit(CurrentUser.from(jwt).id(),activityId,request));
  }
  @GetMapping("/order-requests/{requestId}")
  public OrderRequestResponse query(@PathVariable UUID requestId,@AuthenticationPrincipal Jwt jwt){return service.find(CurrentUser.from(jwt).id(),requestId);}
}
