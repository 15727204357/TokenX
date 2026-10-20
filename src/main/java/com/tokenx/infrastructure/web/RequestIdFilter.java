package com.tokenx.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id.
 *
 * <p>Runs before the security filter chain (highest precedence) so that even a 401 produced by an
 * authentication entry point can report a request id. An inbound id is only accepted when it looks
 * like a plain token, otherwise a fresh UUID is generated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String ATTRIBUTE = "tokenx.requestId";
  public static final String HEADER = "X-Request-Id";
  public static final String MDC_KEY = "requestId";

  private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = resolveRequestId(request.getHeader(HEADER));
    request.setAttribute(ATTRIBUTE, requestId);
    response.setHeader(HEADER, requestId);
    MDC.put(MDC_KEY, requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  static String resolveRequestId(String inbound) {
    return inbound != null && SAFE_ID.matcher(inbound).matches()
        ? inbound
        : UUID.randomUUID().toString();
  }

  /** Returns the id of the current request, or a fresh one when the filter did not run. */
  public static String of(HttpServletRequest request) {
    Object existing = request.getAttribute(ATTRIBUTE);
    return existing instanceof String value ? value : UUID.randomUUID().toString();
  }
}
