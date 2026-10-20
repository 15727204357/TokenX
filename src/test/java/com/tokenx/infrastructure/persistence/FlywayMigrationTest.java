package com.tokenx.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the versioned migration really ran and that running it again changes nothing.
 *
 * <p>Run against {@code tokenx_test} only. A clean-database run is done manually (see the F0-2
 * report) because it requires dropping the schema, which no test is allowed to do.
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTest {

  @Autowired private Flyway flyway;
  @Autowired private SchemaProbeMapper mapper;

  @Test
  void shouldHaveAppliedV1WithoutFailure() {
    assertThat(flyway.info().applied())
        .as("V1 migration must be recorded in flyway_schema_history")
        .anySatisfy(info -> assertThat(info.getVersion().getVersion()).isEqualTo("1"));

    assertThat(flyway.info().all())
        .as("no migration may be in a failed state")
        .noneMatch(info -> info.getState().isFailed());
  }

  @Test
  void shouldApplyNothingWhenMigrationRunsAgain() {
    MigrateResult result = flyway.migrate();

    assertThat(result.migrationsExecuted).as("re-running migrate must not create objects twice").isZero();
    assertThat(result.success).isTrue();
    assertThat(flyway.info().current().getState()).isEqualTo(MigrationState.SUCCESS);
  }

  @Test
  void shouldExposeMigratedTableThroughMapper() {
    assertThat(mapper.selectCount(Wrappers.<SchemaProbe>lambdaQuery()))
        .as("migrated table must be queryable")
        .isNotNegative();
  }
}
