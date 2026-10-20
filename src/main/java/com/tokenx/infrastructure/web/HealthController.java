package com.tokenx.infrastructure.web;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness probe used by the front end and by manual smoke checks. */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

  @GetMapping
  public HealthResponse health() {
    return HealthResponse.up(Instant.now());
  }
}
