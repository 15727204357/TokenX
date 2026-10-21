-- F2 follow-up: cap the two amount columns so every accepted value is exactly representable by the
-- browser (JavaScript number => 2^53-1). The API validates the same bounds through PackageLimits;
-- these CHECK constraints are the database backstop, matching the existing "> 0" checks.
--
-- Why a 5.1 migration and not an edit of V5: V5 has already been executed by the local test
-- database, and published migrations are append-only (docs/DATABASE_DESIGN.md §11.2). A version
-- after 5 but before F3's V6 keeps the numbering agreed for F3..F9 untouched.
--
-- Limits: credit_amount <= 1_000_000_000, list_price_cent <= 100_000_000_000 (= 10 亿元 in cents).

ALTER TABLE token_package
  ADD CONSTRAINT ck_package_credit_amount_max CHECK (credit_amount <= 1000000000),
  ADD CONSTRAINT ck_package_list_price_cent_max CHECK (list_price_cent <= 100000000000);
