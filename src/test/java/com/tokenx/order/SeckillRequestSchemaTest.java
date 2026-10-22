package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tokenx.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class SeckillRequestSchemaTest extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbc;

  @Test
  void shouldPersistAsyncRequestOutcomeAndEnforceRequestIdempotency() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name = 'seckill_request'",
                Integer.class))
        .isEqualTo(1);

    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema = DATABASE() AND table_name = 'seckill_request'",
                String.class))
        .contains("request_id", "user_id", "activity_id", "status", "order_id", "error_code");

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                    + "WHERE table_schema = DATABASE() AND table_name = 'seckill_request' "
                    + "AND index_name = 'uk_user_request' AND non_unique = 0",
                Integer.class))
        .isGreaterThan(0);

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.check_constraints "
                    + "WHERE constraint_schema = DATABASE() "
                    + "AND constraint_name = 'chk_seckill_request_status' "
                    + "AND check_clause LIKE '%6%'",
                Integer.class))
        .isEqualTo(1);
  }
}
