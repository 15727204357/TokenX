package com.tokenx.auth.controller;

import com.tokenx.auth.dto.UserResponse;
import com.tokenx.infrastructure.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Current-user endpoint.
 *
 * <p>The answer comes from verified token claims, not from a database read: authenticated requests
 * must not turn into per-request queries once the trading hot paths exist.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

  @GetMapping("/me")
  public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
    CurrentUser currentUser = CurrentUser.from(jwt);
    return new UserResponse(currentUser.id(), currentUser.username(), currentUser.role().name());
  }
}
