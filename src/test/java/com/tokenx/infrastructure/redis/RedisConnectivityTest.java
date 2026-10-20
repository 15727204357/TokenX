package com.tokenx.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * F0-2 scope only: prove the application can reach the local Redis (PING level). No cache is used
 * by any business path yet, so this test deliberately does not write keys.
 */
@SpringBootTest
@ActiveProfiles("test")
class RedisConnectivityTest {

  @Autowired private StringRedisTemplate redis;

  @Test
  void shouldPingRedisOverLettuceConnection() {
    RedisConnection connection = redis.getConnectionFactory().getConnection();
    try {
      assertThat(connection.ping()).isEqualTo("PONG");
    } finally {
      connection.close();
    }
  }
}
