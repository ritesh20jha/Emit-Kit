-- spec_snapshots.api_model holds the parsed ApiModel. Property order is visible API (it sets record
-- constructor order in generated code), and jsonb does not keep object key order: it sorts keys by
-- length, then bytewise. json stores the text as written, so the order survives.
--
-- Rows written before this migration are already reordered, and the reordering cannot be undone
-- from the row. Each one is repaired on its project's next run: ParseSpecStep parses the stored
-- content again and rewrites api_model and info_version when they differ. A run that resumes after
-- its content was dropped reuses the stored row without parsing, so that row is repaired on the
-- next run that fetches the spec.
--
-- ALTER COLUMN ... TYPE rewrites the whole table and holds an ACCESS EXCLUSIVE lock for the
-- duration, which blocks reads and writes of spec_snapshots. Run it in a quiet window.

ALTER TABLE spec_snapshots ALTER COLUMN api_model TYPE json USING api_model::json;
