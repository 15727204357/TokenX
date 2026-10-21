-- F4: the order table. One table for both sale channels: seckill (F4) and normal purchase (F4.5)
-- share the same state machine, payment, wallet posting and query path, so splitting them would
-- duplicate four code paths to separate one step (whether stock is reserved).
--
-- Everything that costs money is a snapshot, not a join: changing a package or activity price later
-- must never change an existing order, and the client never supplies any of these values.
--
-- order_no is the only identifier that leaves the server. It is CHAR(26) ascii_bin (Crockford
-- Base32, generated in the application): fixed width, case-sensitive, and impossible to enumerate
-- because of the random tail. The auto-increment id stays internal.
--
-- request_id stores the canonical UUID Idempotency-Key header as BINARY(16); the unique key is
-- scoped to the authenticated user, so one client's key cannot collide with another's.
--
-- No physical foreign keys: orders are the hottest insert path and a foreign key would take shared
-- locks on parent rows. Referential integrity is enforced by the service, tests and reconciliation.
-- No version column (F4 never updates an existing order) and no Redis/Kafka columns (V1.5).

CREATE TABLE purchase_order (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_no              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'public, non-enumerable Crockford Base32',
  request_id            BINARY(16)      NOT NULL COMMENT 'Idempotency-Key UUID, canonical form',
  user_id               BIGINT UNSIGNED NOT NULL,
  package_id            BIGINT UNSIGNED NOT NULL,
  activity_id           BIGINT UNSIGNED NULL COMMENT 'set for SECKILL, NULL for NORMAL',
  order_type            TINYINT         NOT NULL COMMENT '1=SECKILL, 2=NORMAL',
  package_name_snapshot VARCHAR(96)     NOT NULL COMMENT 'package name at order time',
  credit_amount         BIGINT UNSIGNED NOT NULL COMMENT 'credits granted, snapshot',
  pay_amount_cent       BIGINT UNSIGNED NOT NULL COMMENT 'amount due in cents, snapshot',
  status                TINYINT         NOT NULL DEFAULT 0 COMMENT '0=UNPAID, 1=PAID, 2=CLOSED',
  expires_at            DATETIME(3)     NOT NULL COMMENT 'payment deadline, UTC',
  paid_at               DATETIME(3)     NULL,
  closed_at             DATETIME(3)     NULL,
  created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  UNIQUE KEY uk_user_request (user_id, request_id),
  -- Final defence for "one order per user per activity". NULL activity_id means NORMAL orders are
  -- not constrained by this key at all, which is exactly what F4.5 needs.
  UNIQUE KEY uk_activity_user (activity_id, user_id),
  KEY idx_order_user (user_id),
  KEY idx_order_timeout (status, expires_at),
  CONSTRAINT ck_order_type CHECK (order_type IN (1, 2)),
  CONSTRAINT ck_order_status CHECK (status IN (0, 1, 2)),
  -- A seckill order must belong to an activity; a normal order must not pretend to.
  CONSTRAINT ck_order_activity CHECK (
      (order_type = 1 AND activity_id IS NOT NULL)
      OR (order_type = 2 AND activity_id IS NULL)),
  CONSTRAINT ck_order_credit_amount CHECK (credit_amount > 0 AND credit_amount <= 1000000000),
  CONSTRAINT ck_order_pay_amount CHECK (pay_amount_cent > 0 AND pay_amount_cent <= 100000000000),
  -- Terminal timestamps must match the status, so a paid order cannot also look closed.
  CONSTRAINT ck_order_unpaid_times CHECK (status <> 0 OR (paid_at IS NULL AND closed_at IS NULL)),
  CONSTRAINT ck_order_paid_times CHECK (status <> 1 OR (paid_at IS NOT NULL AND closed_at IS NULL)),
  CONSTRAINT ck_order_closed_times CHECK (status <> 2 OR (closed_at IS NOT NULL AND paid_at IS NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
