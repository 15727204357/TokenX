-- F1: the F0-2 connectivity probe is not a business table.
-- See docs/DATABASE_DESIGN.md §3.2 ("schema_probe: F1 拆除").

DROP TABLE IF EXISTS schema_probe;
