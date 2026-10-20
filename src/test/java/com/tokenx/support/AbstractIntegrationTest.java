package com.tokenx.support;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need a Spring context.
 *
 * <p>The JWT key is generated for each test JVM instead of being committed, so no signing secret
 * lives in the repository while every context still starts with a valid, >= 32 character key.
 */
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  private static final String JWT_SECRET = randomSecret();

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add("tokenx.auth.jwt.secret", () -> JWT_SECRET);
  }

  /** Key used by tests that need to forge tokens (expired, wrong signature, ...). */
  protected static String testJwtSecret() {
    return JWT_SECRET;
  }

  private static String randomSecret() {
    byte[] key = new byte[48];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}
