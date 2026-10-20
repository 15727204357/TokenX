package com.tokenx.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Forges tokens for negative tests.
 *
 * <p>Tests must be able to produce a token that is structurally valid but rejected, so this helper
 * signs arbitrary claims with either the real test key or a different one.
 */
public final class JwtTestFactory {

  private JwtTestFactory() {}

  public static String token(
      String subject, String username, String role, Instant issuedAt, Instant expiresAt) {
    return token(subject, username, role, issuedAt, expiresAt, AbstractIntegrationTest.testJwtSecret());
  }

  public static String token(
      String subject,
      String username,
      String role,
      Instant issuedAt,
      Instant expiresAt,
      String secret) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(subject)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("username", username)
            .claim("role", role)
            .build();
    return new NimbusJwtEncoder(new ImmutableSecret<>(key(secret)))
        .encode(
            JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  public static String randomSecret() {
    return UUID.randomUUID().toString() + UUID.randomUUID();
  }

  private static SecretKey key(String secret) {
    return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }
}
