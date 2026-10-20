package com.tokenx.infrastructure.error;

import org.springframework.http.HttpStatus;

/**
 * Expected, client-visible business failure.
 *
 * <p>Carries a stable machine-readable {@code code} plus the HTTP status it maps to, so controllers
 * stay free of error translation and the global handler can render one shape everywhere.
 */
public class BusinessException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public BusinessException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }
}
