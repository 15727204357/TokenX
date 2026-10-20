package com.tokenx.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the real chain MySQL 9.7 -> Flyway-created schema -> MyBatis-Plus mapping works.
 *
 * <p>Runs against the dedicated {@code tokenx_test} database, never the development database.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaProbeMapperTest {

  @Autowired private SchemaProbeMapper mapper;

  private String probeKey;

  @AfterEach
  void deleteProbeRow() {
    if (probeKey != null) {
      mapper.delete(Wrappers.<SchemaProbe>lambdaQuery().eq(SchemaProbe::getProbeKey, probeKey));
    }
  }

  @Test
  void shouldInsertAndReadBackRowFromMigratedTable() {
    probeKey = "probe-" + UUID.randomUUID();
    SchemaProbe probe = new SchemaProbe();
    probe.setProbeKey(probeKey);
    probe.setNote("f0-2 wiring check");

    assertThat(mapper.insert(probe)).isEqualTo(1);
    assertThat(probe.getId()).as("AUTO_INCREMENT key returned to the entity").isNotNull();

    SchemaProbe loaded =
        mapper.selectOne(Wrappers.<SchemaProbe>lambdaQuery().eq(SchemaProbe::getProbeKey, probeKey));

    assertThat(loaded).isNotNull();
    assertThat(loaded.getNote()).isEqualTo("f0-2 wiring check");
    assertThat(loaded.getCreatedAt()).as("DATETIME(6) default applied by MySQL").isNotNull();
  }

  @Test
  void shouldRejectDuplicateProbeKeyAtDatabaseLevel() {
    probeKey = "probe-" + UUID.randomUUID();

    SchemaProbe first = new SchemaProbe();
    first.setProbeKey(probeKey);
    mapper.insert(first);

    SchemaProbe duplicate = new SchemaProbe();
    duplicate.setProbeKey(probeKey);

    assertThatThrownBy(() -> mapper.insert(duplicate))
        .as("unique index must be enforced by MySQL, not only by application code")
        .isInstanceOf(DuplicateKeyException.class);
  }
}
