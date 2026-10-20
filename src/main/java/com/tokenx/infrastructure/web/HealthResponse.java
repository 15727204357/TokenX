package com.tokenx.infrastructure.web;

import java.time.Instant;

/**
 * Response body of the public health probe.
 *
 * @param status always {@code UP} while the application can serve requests
 * @param service application name, fixed for a running build
 * @param time server-side timestamp in ISO-8601 (UTC)
 */
public record HealthResponse(String status, String service, Instant time) {

  public static final String SERVICE_NAME = "tokenx";

  public static HealthResponse up(Instant now) {
    return new HealthResponse("UP", SERVICE_NAME, now);
  }
}
