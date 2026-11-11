-- Spec snapshots, generation runs and their logs (spec v1 §4). Conventions as in V2__projects.sql.
-- generation_runs.step_outputs is an addition to v1 §4: step name -> saved output, for idempotent
-- resume (§5.1). commit_sha, current_step and generation_run_logs.step are nullable because a run
-- has no commit, and no step yet, while it is still QUEUED.

CREATE TABLE spec_snapshots (
    id            varchar(26)  PRIMARY KEY,
    project_id    varchar(26)  NOT NULL REFERENCES projects (id),
    content_hash  varchar(64)  NOT NULL,
    raw_content   text         NOT NULL,
    api_model     jsonb        NOT NULL,
    info_version  varchar(255) NOT NULL,
    created_at    timestamptz  NOT NULL,
    UNIQUE (project_id, content_hash)
);

CREATE TABLE generation_runs (
    id                   varchar(26)  PRIMARY KEY,
    project_id           varchar(26)  NOT NULL REFERENCES projects (id),
    config_id            varchar(26)  NOT NULL REFERENCES project_configs (id),
    spec_snapshot_id     varchar(26)  REFERENCES spec_snapshots (id),
    trigger              varchar(16)  NOT NULL CONSTRAINT generation_runs_trigger_check
                             CHECK (trigger IN ('MANUAL', 'WEBHOOK', 'RERUN')),
    triggered_by_user_id varchar(26)  REFERENCES users (id),
    parent_run_id        varchar(26)  REFERENCES generation_runs (id),
    dry_run              boolean      NOT NULL DEFAULT false,
    status               varchar(32)  NOT NULL CONSTRAINT generation_runs_status_check
                             CHECK (status IN ('QUEUED', 'RUNNING', 'AWAITING_APPROVAL',
                                               'SUCCESS', 'FAILED', 'CANCELLED')),
    current_step         varchar(32)  CONSTRAINT generation_runs_current_step_check
                             CHECK (current_step IN ('FETCH_SPEC', 'PARSE_SPEC', 'LINT_SPEC',
                                 'DIFF_SPEC', 'CALC_VERSION', 'BREAKING_GATE', 'RUN_GENERATORS',
                                 'SYNTAX_CHECK', 'PACKAGE_ARTIFACT', 'COMMIT_OUTPUT', 'FINALIZE')),
    failure_code         varchar(64),
    failure_message      text,
    cancel_requested     boolean      NOT NULL DEFAULT false,
    attempt              int          NOT NULL DEFAULT 0,
    step_outputs         jsonb        NOT NULL DEFAULT '{}'::jsonb,
    commit_sha           varchar(64),
    sdk_version          varchar(64),
    change_type          varchar(16)  CONSTRAINT generation_runs_change_type_check
                             CHECK (change_type IN ('MAJOR', 'MINOR', 'PATCH')),
    diff_summary         jsonb,
    branch_name          varchar(255),
    pr_url               varchar(512),
    pr_number            int,
    created_at           timestamptz  NOT NULL,
    started_at           timestamptz,
    finished_at          timestamptz
);

CREATE INDEX generation_runs_project_created_idx ON generation_runs (project_id, created_at DESC);
CREATE INDEX generation_runs_status_idx ON generation_runs (status);

CREATE TABLE generation_run_logs (
    id       bigserial    PRIMARY KEY,
    run_id   varchar(26)  NOT NULL REFERENCES generation_runs (id),
    seq      int          NOT NULL,
    ts       timestamptz  NOT NULL,
    level    varchar(16)  NOT NULL,
    step     varchar(32)  CONSTRAINT generation_run_logs_step_check
                 CHECK (step IN ('FETCH_SPEC', 'PARSE_SPEC', 'LINT_SPEC', 'DIFF_SPEC',
                                 'CALC_VERSION', 'BREAKING_GATE', 'RUN_GENERATORS',
                                 'SYNTAX_CHECK', 'PACKAGE_ARTIFACT', 'COMMIT_OUTPUT', 'FINALIZE')),
    message  text         NOT NULL,
    UNIQUE (run_id, seq)
);

CREATE INDEX generation_run_logs_run_seq_idx ON generation_run_logs (run_id, seq);
