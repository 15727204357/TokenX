-- F0-2 compatibility probe.
--
-- Purpose: prove that Flyway 12 (Boot BOM) can migrate a real MySQL 9.7 schema and that
-- MyBatis-Plus maps the resulting table. It is NOT a business table: every business table is
-- introduced by the feature that owns it (see docs/DEVELOPMENT_PLAN.md), and this table is
-- dropped by the migration that introduces the first real one.

CREATE TABLE schema_probe (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  probe_key  VARCHAR(64)     NOT NULL,
  note       VARCHAR(255)        NULL,
  created_at DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_schema_probe_probe_key (probe_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
