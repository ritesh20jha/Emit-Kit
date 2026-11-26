-- Projects and their append-only configs (spec v1 §4). Conventions as in V1__identity.sql.
-- Soft delete (deleted_at) is added later by V6__project_soft_delete.sql.

CREATE TABLE projects (
    id                    varchar(26)  PRIMARY KEY,
    org_id                varchar(26)  NOT NULL REFERENCES organizations (id),
    repo_full_name        varchar(255) NOT NULL,
    spec_path             varchar(255) NOT NULL,
    default_branch        varchar(255) NOT NULL,
    output_mode           varchar(16)  NOT NULL CONSTRAINT projects_output_mode_check
                              CHECK (output_mode IN ('APPEND', 'SEPARATE')),
    output_repo_full_name varchar(255),
    webhook_id            bigint,
    webhook_secret_enc    text,
    created_by            varchar(26)  NOT NULL REFERENCES users (id),
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    UNIQUE (org_id, repo_full_name)
);

-- Webhook deliveries find projects by repository across all organizations.
CREATE INDEX projects_repo_full_name_idx ON projects (repo_full_name);

CREATE TABLE project_configs (
    id                varchar(26)  PRIMARY KEY,
    project_id        varchar(26)  NOT NULL REFERENCES projects (id),
    version           int          NOT NULL,
    outputs           jsonb        NOT NULL DEFAULT '[]'::jsonb,
    sdk_languages     jsonb        NOT NULL DEFAULT '[]'::jsonb,
    output_dir        varchar(255) NOT NULL DEFAULT '.emitkit/',
    npm_scope         varchar(255),
    pypi_name         varchar(255),
    maven_group_id    varchar(255),
    maven_artifact_id varchar(255),
    version_strategy  varchar(32)  NOT NULL CONSTRAINT project_configs_version_strategy_check
                          CHECK (version_strategy IN ('EMITKIT_MANAGED', 'SPEC_VERSION')),
    breaking_gate     varchar(32)  NOT NULL DEFAULT 'ALLOW'
                          CONSTRAINT project_configs_breaking_gate_check
                          CHECK (breaking_gate IN ('ALLOW', 'REQUIRE_APPROVAL', 'BLOCK')),
    lint_fail_on      varchar(16)  NOT NULL DEFAULT 'NEVER'
                          CONSTRAINT project_configs_lint_fail_on_check
                          CHECK (lint_fail_on IN ('NEVER', 'ERROR', 'WARNING')),
    ai_provider       varchar(32)  NOT NULL DEFAULT 'gemini',
    ai_model          varchar(255),
    ai_api_key_enc    text,
    created_by        varchar(26)  NOT NULL REFERENCES users (id),
    created_at        timestamptz  NOT NULL,
    UNIQUE (project_id, version)
);
