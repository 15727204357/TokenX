package com.tokenx.infrastructure.security;

import com.tokenx.auth.model.UserRole;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authenticated caller, derived from verified token claims.
 *
 * <p>Business code must obtain the user identity from here (i.e. from the Spring Security context)
 * and never from a request parameter, so a client cannot act on behalf of another user.
 *
 * @param id user id taken from the token subject
 * @param username username claim
 * @param role role claim
 */
public record CurrentUser(Long id, String username, UserRole role) {

  public static CurrentUser from(Jwt jwt) {
    return new CurrentUser(
        Long.valueOf(jwt.getSubject()), jwt.getClaimAsString(JwtClaims.USERNAME), roleOf(jwt));
  }

  private static UserRole roleOf(Jwt jwt) {
    String role = jwt.getClaimAsString(JwtClaims.ROLE);
    return role == null ? UserRole.USER : UserRole.valueOf(role);
  }
}
