-- F3: seckill activity configuration, i.e. "this package is sold in this time window at this price".
--
-- Configuration state and runtime state are deliberately different things:
--   * status is the *configuration* state only: 0=DRAFT, 1=PUBLISHED, 2=OFFLINE.
--   * "upcoming / active / ended" is derived from start_at, end_at and the server clock at read
--     time, and is never stored. Storing it would need a scheduler that rewrites rows and would
--     drift away from the real time.
--
-- Price and credit amount are snapshots taken from the package: editing the package later must not
-- change what an already published activity sells. Once published, price, credit snapshot, time
-- window and total stock are immutable (there is no endpoint that can change them).
--
-- Deliberately absent columns: version (this row is not updated while users buy), sold_stock
-- (derived as total - available - reserved), participant counters, Redis/Kafka/order columns and a
-- per-user purchase limit (one order per user per activity is a project-wide rule enforced by the
-- unique key on purchase_order in F4).
--
-- No physical foreign key to token_package: referential integrity is checked inside the creating
-- transaction and by tests, matching the rest of the schema (docs/DATABASE_DESIGN.md §4.3).

CREATE TABLE seckill_activity (
  id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  package_id             BIGINT UNSIGNED NOT NULL COMMENT 'token_package.id, validated by the service',
  activity_price_cent    BIGINT UNSIGNED NOT NULL COMMENT 'activity price snapshot in cents',
  credit_amount_snapshot BIGINT UNSIGNED NOT NULL COMMENT 'credits granted, snapshotted from the package',
  start_at               DATETIME(3)     NOT NULL COMMENT 'window start, UTC',
  end_at                 DATETIME(3)     NOT NULL COMMENT 'window end (exclusive), UTC',
  status                 TINYINT         NOT NULL DEFAULT 0 COMMENT '0=DRAFT, 1=PUBLISHED, 2=OFFLINE',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- "published activities ordered by start" reads status by equality, then start_at in order.
  KEY idx_activity_list (status, start_at),
  CONSTRAINT ck_activity_status CHECK (status IN (0, 1, 2)),
  CONSTRAINT ck_activity_window CHECK (start_at < end_at),
  CONSTRAINT ck_activity_price_cent CHECK (activity_price_cent > 0),
  CONSTRAINT ck_activity_price_cent_max CHECK (activity_price_cent <= 100000000000),
  CONSTRAINT ck_activity_credit_snapshot CHECK (credit_amount_snapshot > 0),
  CONSTRAINT ck_activity_credit_snapshot_max CHECK (credit_amount_snapshot <= 1000000000)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
