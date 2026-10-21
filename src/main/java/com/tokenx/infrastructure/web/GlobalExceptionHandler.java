package com.tokenx.infrastructure.web;

import com.tokenx.infrastructure.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every failure with the same {@link ApiError} shape.
 *
 * <p>Unexpected failures are logged with their exception type only: request bodies (which contain
 * passwords) and headers must never reach the log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiError> handleBusiness(
      BusinessException ex, HttpServletRequest request) {
    return ResponseEntity.status(ex.getStatus())
        .body(new ApiError(ex.getCode(), ex.getMessage(), RequestIdFilter.of(request)));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(FieldError::getDefaultMessage)
            .orElse("request is invalid");
    return ResponseEntity.badRequest()
        .body(new ApiError("VALIDATION_FAILED", message, RequestIdFilter.of(request)));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleUnreadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(new ApiError("MALFORMED_REQUEST", "request body cannot be parsed", RequestIdFilter.of(request)));
  }

  /**
   * Constraints on query parameters and path variables (for example {@code limit}) are validated by
   * Spring MVC itself and raise this exception rather than the bean-validation one above. Without
   * this handler they would fall through to the catch-all and be reported as a server error.
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ApiError> handleMethodValidation(
      HandlerMethodValidationException ex, HttpServletRequest request) {
    String message =
        ex.getParameterValidationResults().stream()
            .flatMap(result -> result.getResolvableErrors().stream())
            .map(MessageSourceResolvable::getDefaultMessage)
            .findFirst()
            .orElse("request is invalid");
    return ResponseEntity.badRequest()
        .body(new ApiError("VALIDATION_FAILED", message, RequestIdFilter.of(request)));
  }

  /** A path variable or query parameter with the wrong type, e.g. {@code /products/abc}. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiError> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(
            new ApiError(
                "VALIDATION_FAILED",
                "parameter '" + ex.getName() + "' has an invalid value",
                RequestIdFilter.of(request)));
  }

  /** Unknown path must stay a 404 instead of being turned into a 500 by the catch-all below. */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiError> handleNoResource(
      NoResourceFoundException ex, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ApiError("NOT_FOUND", "resource not found", RequestIdFilter.of(request)));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiError> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ApiError("FORBIDDEN", "access is denied", RequestIdFilter.of(request)));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
    String requestId = RequestIdFilter.of(request);
    log.error(
        "unexpected failure requestId={} type={} path={}",
        requestId,
        ex.getClass().getName(),
        request.getRequestURI());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiError("INTERNAL_ERROR", "unexpected server error", requestId));
  }
}
