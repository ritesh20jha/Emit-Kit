-- Run results: packaged artifacts, lint findings, approvals and released SDK versions (spec v1 §4).
-- Conventions as in V3__runs.sql. Nullability that v1 §4 does not state is decided here:
--   lint_results.json_path and message are NOT NULL, since the linter always reports both;
--   run_approvals.decided_by, decision, comment and decided_at are NULL, since an approval is
--   created pending and only gets its decision later;
--   sdk_versions.changelog is NOT NULL, stored as an empty string when there is nothing to say.

CREATE TABLE run_artifacts (
    id           varchar(26)  PRIMARY KEY,
    run_id       varchar(26)  NOT NULL UNIQUE REFERENCES generation_runs (id),
    storage_key  varchar(512) NOT NULL,
    size_bytes   bigint       NOT NULL,
    file_count   int          NOT NULL,
    manifest     jsonb        NOT NULL,      -- [{path, sha256, size, status: ADDED|MODIFIED|UNCHANGED|SKIPPED}]
    created_at   timestamptz  NOT NULL
);

-- Retention deletes artifacts older than 30 days, selected by created_at.
CREATE INDEX run_artifacts_created_idx ON run_artifacts (created_at);

CREATE TABLE lint_results (
    id         varchar(26)  PRIMARY KEY,
    run_id     varchar(26)  NOT NULL REFERENCES generation_runs (id),
    rule_id    varchar(64)  NOT NULL,
    severity   varchar(16)  NOT NULL CONSTRAINT lint_results_severity_check
                   CHECK (severity IN ('ERROR', 'WARNING', 'INFO')),
    json_path  text         NOT NULL,
    message    text         NOT NULL
);

-- Findings of a run are read by run_id: for the list and the per-severity count.
CREATE INDEX lint_results_run_idx ON lint_results (run_id);

CREATE TABLE run_approvals (
    id          varchar(26)  PRIMARY KEY,
    run_id      varchar(26)  NOT NULL UNIQUE REFERENCES generation_runs (id),
    decided_by  varchar(26)  REFERENCES users (id),
    decision    varchar(16)  CONSTRAINT run_approvals_decision_check
                    CHECK (decision IN ('APPROVED', 'REJECTED')),
    comment     text,
    created_at  timestamptz  NOT NULL,
    decided_at  timestamptz
);

CREATE TABLE sdk_versions (
    id           varchar(26)  PRIMARY KEY,
    project_id   varchar(26)  NOT NULL REFERENCES projects (id),
    version      varchar(64)  NOT NULL,
    run_id       varchar(26)  NOT NULL REFERENCES generation_runs (id),
    change_type  varchar(16)  NOT NULL CONSTRAINT sdk_versions_change_type_check
                     CHECK (change_type IN ('MAJOR', 'MINOR', 'PATCH')),
    changelog    text         NOT NULL,
    created_at   timestamptz  NOT NULL,
    UNIQUE (project_id, version)
);

-- A project's version history is read newest first.
CREATE INDEX sdk_versions_project_created_idx ON sdk_versions (project_id, created_at DESC);
