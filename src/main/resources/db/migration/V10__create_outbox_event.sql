-- F8: durable, versioned facts emitted only after their owning MySQL transaction commits.
-- A publisher may send more than once if Kafka accepted a record but its DB acknowledgement was
-- lost; event_id is stable and consumers deduplicate it in processed_message.

CREATE TABLE outbox_event (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id           BINARY(16)      NOT NULL,
  aggregate_type     VARCHAR(64)     NOT NULL,
  aggregate_id       VARCHAR(64)     NOT NULL,
  event_type         VARCHAR(100)    NOT NULL,
  schema_version     SMALLINT UNSIGNED NOT NULL,
  message_key        VARCHAR(128)    NOT NULL,
  payload            JSON            NOT NULL,
  status             TINYINT         NOT NULL DEFAULT 0 COMMENT '0=PENDING, 1=IN_FLIGHT, 2=PUBLISHED, 3=DEAD_LETTERED',
  attempt_count      TINYINT UNSIGNED NOT NULL DEFAULT 0,
  next_attempt_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  lease_until        DATETIME(3)     NULL,
  published_at       DATETIME(3)     NULL,
  last_error_code    VARCHAR(64)     NULL COMMENT 'exception category only; no message or payload',
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbox_event_id (event_id),
  KEY idx_outbox_due (status, next_attempt_at, id),
  CONSTRAINT ck_outbox_status CHECK (status IN (0, 1, 2, 3)),
  CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count <= 10),
  CONSTRAINT ck_outbox_published_at CHECK ((status = 2 AND published_at IS NOT NULL) OR (status <> 2 AND published_at IS NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
