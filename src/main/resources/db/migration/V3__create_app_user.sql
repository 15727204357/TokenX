-- F1: account identity (docs/DATABASE_DESIGN.md §4.1).
--
-- username uniqueness is case-insensitive because the column inherits the table collation
-- utf8mb4_0900_ai_ci: "Alice" and "alice" collide on uk_user_username. That index, not an
-- application-level "select then insert" check, is what makes duplicate registration impossible
-- under concurrency.

CREATE TABLE app_user (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username      VARCHAR(64)     NOT NULL COMMENT '3-64 chars, letters/digits/underscore only',
  password_hash VARCHAR(255)    NOT NULL COMMENT 'BCrypt digest; plaintext is never stored',
  role          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=USER, 1=ADMIN',
  status        TINYINT         NOT NULL DEFAULT 0 COMMENT '0=ACTIVE, 1=DISABLED',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_username (username),
  CONSTRAINT ck_user_role CHECK (role IN (0, 1)),
  CONSTRAINT ck_user_status CHECK (status IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
