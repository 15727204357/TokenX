-- F5: the wallet ledger. Append-only evidence that a balance is what it says it is.
--
-- The balance in token_account is the *result*; this table is the *proof*. Without it a duplicated
-- credit can only be detected by recomputing history, and a reconciliation after a concurrency test
-- has nothing to compare the account row against.
--
-- Rows are never updated or deleted. A mistake is corrected by a new posting with the opposite
-- effect, which is why effect_type carries a direction and delta is signed — the pair (direction,
-- sign) is enforced by a CHECK so a "credit" that lowers the balance cannot be stored at all.
--
-- uk_ledger_biz (biz_type, biz_id, effect_type) is the second line of defence against double
-- posting: the first is the conditional UPDATE that moves an order from UNPAID to PAID. This key
-- catches the case the first cannot — a code path that posts without going through the order gate.
--
-- idx_ledger_user (user_id): the wallet walk is "this user's postings, newest first, after a
-- cursor". InnoDB appends the primary key to a secondary index, so this index is effectively
-- (user_id, id) and the query is a reverse range scan with no filesort and no offset.
--
-- balance_after is a derived value that is nonetheless stored: it freezes what the balance was at
-- that moment, which cannot be reconstructed once later postings exist.
--
-- No physical foreign key to purchase_order: orders are the hottest insert path, and the ledger is
-- written inside the payment transaction that already holds the order row. Referential integrity is
-- guaranteed by the service and checked by reconciliation, exactly like the order table itself.

CREATE TABLE token_ledger (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id       BIGINT UNSIGNED NOT NULL COMMENT 'account the posting belongs to',
  biz_type      TINYINT         NOT NULL COMMENT '1=ORDER',
  biz_id        BIGINT UNSIGNED NOT NULL COMMENT 'business primary key: purchase_order.id',
  effect_type   TINYINT         NOT NULL COMMENT '1=CREDIT (in), 2=DEBIT (out, reserved)',
  delta         BIGINT          NOT NULL COMMENT 'signed change to the balance',
  balance_after BIGINT UNSIGNED NOT NULL COMMENT 'balance after this posting',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ledger_biz (biz_type, biz_id, effect_type),
  KEY idx_ledger_user (user_id),
  -- A new business type needs a new migration, so the stored vocabulary cannot drift silently.
  CONSTRAINT ck_ledger_biz_type CHECK (biz_type IN (1)),
  CONSTRAINT ck_ledger_effect_type CHECK (effect_type IN (1, 2)),
  -- Direction and sign must agree: a credit increases, a debit decreases, and neither may be zero.
  CONSTRAINT ck_ledger_delta_sign CHECK (
      (effect_type = 1 AND delta > 0)
      OR (effect_type = 2 AND delta < 0))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
