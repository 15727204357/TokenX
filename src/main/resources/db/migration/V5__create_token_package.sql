-- F2: package catalogue, i.e. "what we sell" (credit amount + list price).
-- Activities are a separate object: they sell a package for a time window, so none of the
-- activity price/stock columns belong here (docs/DATABASE_DESIGN.md §4.2).
--
-- Status is 0=OFF_SHELF, 1=ON_SALE. A new package starts off-shelf so a half-configured row is
-- never visible to visitors. Amounts are unsigned integers in cents; float/double is forbidden.
-- Names are deliberately not unique: two packages may legitimately share a display name, so a
-- database conflict would be the wrong answer.

CREATE TABLE token_package (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name            VARCHAR(96)     NOT NULL COMMENT 'display name, trimmed, 1-96 chars',
  credit_amount   BIGINT UNSIGNED NOT NULL COMMENT 'credits granted on purchase',
  list_price_cent BIGINT UNSIGNED NOT NULL COMMENT 'list price in cents',
  status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=OFF_SHELF, 1=ON_SALE',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_package_status (status),
  CONSTRAINT ck_package_status CHECK (status IN (0, 1)),
  CONSTRAINT ck_package_credit_amount CHECK (credit_amount > 0),
  CONSTRAINT ck_package_list_price_cent CHECK (list_price_cent > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
