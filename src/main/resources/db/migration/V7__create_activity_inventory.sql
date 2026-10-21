-- F3: stock for one activity, one row per activity (1:1 with seckill_activity).
--
-- Separate table on purpose: this row is updated on every purchase while the activity configuration
-- is read-only, so keeping them apart isolates the hot row lock from the configuration read path.
--
-- Only two quantities are stored. The sold quantity is derived as
-- total_stock - available_stock - reserved_stock, so there is no sold_stock column that could
-- disagree with the orders.
--
-- Indexes: primary key only. There is no query that filters on a stock column, and every secondary
-- index would add random IO to each purchase update for zero benefit.
--
-- No physical foreign key to seckill_activity: an activity and its stock row are written in the same
-- transaction, and the test suite proves that a failed second insert leaves no orphan row.

CREATE TABLE activity_inventory (
  activity_id     BIGINT UNSIGNED NOT NULL,
  total_stock     INT UNSIGNED    NOT NULL COMMENT 'fixed at creation; changing it would break reconciliation',
  available_stock INT UNSIGNED    NOT NULL COMMENT 'still reservable',
  reserved_stock  INT UNSIGNED    NOT NULL COMMENT 'held by unpaid orders',
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (activity_id),
  CONSTRAINT ck_inventory_balance CHECK (available_stock + reserved_stock <= total_stock)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
