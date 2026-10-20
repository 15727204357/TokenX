-- F1: one balance row per user, created in the same transaction as the user itself
-- (docs/DATABASE_DESIGN.md §4.6, Q8). The wallet API stays out of scope until F5.
--
-- available_balance is UNSIGNED so a negative balance is rejected by the column type itself.

CREATE TABLE token_account (
  user_id           BIGINT UNSIGNED NOT NULL,
  available_balance BIGINT UNSIGNED NOT NULL DEFAULT 0,
  updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
