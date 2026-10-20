package com.tokenx.infrastructure.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings.
 *
 * <p>The secret is supplied by {@code TOKENX_JWT_SECRET} or the git-ignored {@code
 * application-local.yml}; no default value exists on purpose so a deployment cannot silently start
 * with a well-known key.
 */
@ConfigurationProperties(prefix = "tokenx.auth.jwt")
public class JwtProperties {

  /** HMAC-SHA256 key material, at least 32 characters. */
  private String secret = "";

  /** Lifetime of an issued token. Already-issued tokens stay valid until this point. */
  private Duration ttl = Duration.ofHours(1);

  public String getSecret() {
    return secret;
  }

  public void setSecret(String secret) {
    this.secret = secret;
  }

  public Duration getTtl() {
    return ttl;
  }

  public void setTtl(Duration ttl) {
    this.ttl = ttl;
  }
}
