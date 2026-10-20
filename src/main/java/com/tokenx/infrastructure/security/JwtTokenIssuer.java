package com.tokenx.infrastructure.security;

import com.tokenx.auth.dto.TokenResponse;
import com.tokenx.auth.model.AppUser;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues access tokens.
 *
 * <p>The token carries exactly what the request path needs ({@code sub}, {@code username}, {@code
 * role}) so authenticated requests never have to read {@code app_user}. Consequently a disabled
 * account keeps working with tokens issued earlier until they expire, which is the documented
 * boundary in docs/DATABASE_DESIGN.md §4.1.
 */
@Component
public class JwtTokenIssuer {

  private final JwtEncoder encoder;
  private final JwtProperties properties;

  public JwtTokenIssuer(JwtEncoder encoder, JwtProperties properties) {
    this.encoder = encoder;
    this.properties = properties;
  }

  public TokenResponse issue(AppUser user) {
    Instant issuedAt = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(String.valueOf(user.getId()))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(properties.getTtl()))
            .claim(JwtClaims.USERNAME, user.getUsername())
            .claim(JwtClaims.ROLE, user.getRole().name())
            .build();

    String token =
        encoder
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();

    return new TokenResponse(token, "Bearer", properties.getTtl().toSeconds());
  }
}
