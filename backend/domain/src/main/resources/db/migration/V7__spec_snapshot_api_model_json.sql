-- spec_snapshots.api_model holds the parsed ApiModel. Property order is visible API (it sets record
-- constructor order in generated code), and jsonb does not keep object key order: it sorts keys by
-- length, then bytewise. json stores the text as written, so the order survives. Rows written
-- before this migration are already reordered and cannot be recovered; re-parse their specs to
-- rebuild them.

ALTER TABLE spec_snapshots ALTER COLUMN api_model TYPE json USING api_model::json;
