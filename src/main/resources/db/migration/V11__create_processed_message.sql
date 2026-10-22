-- F8: each consumer's committed side effect is recorded once per stable event id.

CREATE TABLE processed_message (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  consumer_name      VARCHAR(100)    NOT NULL,
  event_id           BINARY(16)      NOT NULL,
  event_type         VARCHAR(100)    NOT NULL,
  schema_version     SMALLINT UNSIGNED NOT NULL,
  processed_at       DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_processed_consumer_event (consumer_name, event_id),
  KEY idx_processed_at (processed_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
