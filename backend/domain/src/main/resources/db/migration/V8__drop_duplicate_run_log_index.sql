-- generation_run_logs_run_seq_idx duplicates the index behind UNIQUE (run_id, seq), so every log
-- line was written to two identical indexes. Queries by run and seq use the unique index.
DROP INDEX IF EXISTS generation_run_logs_run_seq_idx;
