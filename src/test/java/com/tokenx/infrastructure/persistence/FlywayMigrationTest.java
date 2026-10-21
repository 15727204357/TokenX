package com.tokenx.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tokenx.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the versioned migrations really ran on real MySQL and that running them again changes
 * nothing.
 *
 * <p>Runs against {@code tokenx_test} only. A from-scratch bootstrap needs an empty schema; the test
 * account cannot create or drop databases, so that run is documented as a manual step in the README
 * instead of being faked here.
 */
@SpringBootTest
class FlywayMigrationTest extends AbstractIntegrationTest {

  @Autowired private Flyway flyway;
  @Autowired private DataSource dataSource;

  @Test
  void shouldHaveAppliedEveryMigrationWithoutFailure() {
    List<String> applied =
        Arrays.stream(flyway.info().applied())
            .map(info -> info.getVersion().getVersion())
            .toList();

    assertThat(applied).contains("1", "2", "3", "4", "5", "5.1", "6", "7", "8", "9");

    assertThat(flyway.info().all())
        .as("no migration may be in a failed state")
        .noneMatch(info -> info.getState().isFailed());

    assertThat(flyway.info().current().getVersion().getVersion())
        .as("F5 is the last migration: the schema ends at V9")
        .isEqualTo("9");
  }

  @Test
  void shouldApplyNothingWhenMigrationRunsAgain() {
    MigrateResult result = flyway.migrate();

    assertThat(result.migrationsExecuted).as("re-running migrate must not create objects twice").isZero();
    assertThat(result.success).isTrue();
    assertThat(flyway.info().current().getState()).isEqualTo(MigrationState.SUCCESS);
  }

  @Test
  void shouldContainTheAccountCatalogueActivityOrderAndWalletTables() throws SQLException {
    assertThat(existingTables())
        .contains(
            "app_user",
            "token_account",
            "token_package",
            "seckill_activity",
            "activity_inventory",
            "purchase_order",
            "token_ledger")
        .doesNotContain("schema_probe");
  }

  private List<String> existingTables() throws SQLException {
    List<String> tables = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE()")) {
      while (rs.next()) {
        tables.add(rs.getString(1));
      }
    }
    return tables;
  }
}
