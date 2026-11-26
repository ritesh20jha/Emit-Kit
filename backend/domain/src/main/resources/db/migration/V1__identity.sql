-- Identity: users, their OAuth accounts, organizations and org membership (spec v1 §4).
-- IDs are ULIDs (varchar(26)) generated in Java. Enums are varchar with a CHECK constraint.

CREATE TABLE users (
    id          varchar(26)  PRIMARY KEY,
    github_id   bigint       NOT NULL UNIQUE,
    login       varchar(255) NOT NULL,
    name        varchar(255),
    email       varchar(255),
    avatar_url  varchar(255) NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);

CREATE TABLE oauth_accounts (
    id                  varchar(26)  PRIMARY KEY,
    user_id             varchar(26)  NOT NULL REFERENCES users (id),
    provider            varchar(32)  NOT NULL,
    provider_account_id varchar(255) NOT NULL,
    access_token_enc    text         NOT NULL,
    scopes              text         NOT NULL,
    updated_at          timestamptz  NOT NULL,
    UNIQUE (provider, provider_account_id)
);

CREATE TABLE organizations (
    id            varchar(26)  PRIMARY KEY,
    vcs_provider  varchar(32)  NOT NULL DEFAULT 'github',
    vcs_org_id    varchar(255) NOT NULL,
    slug          varchar(255) NOT NULL,
    name          varchar(255) NOT NULL,
    is_personal   boolean      NOT NULL,
    owner_user_id varchar(26)  REFERENCES users (id),
    created_at    timestamptz  NOT NULL,
    UNIQUE (vcs_provider, vcs_org_id)
);

CREATE TABLE org_members (
    org_id      varchar(26) NOT NULL REFERENCES organizations (id),
    user_id     varchar(26) NOT NULL REFERENCES users (id),
    role        varchar(16) NOT NULL CONSTRAINT org_members_role_check
                    CHECK (role IN ('ADMIN', 'EDITOR', 'VIEWER')),
    role_source varchar(16) NOT NULL CONSTRAINT org_members_role_source_check
                    CHECK (role_source IN ('SYNC', 'MANUAL')),
    PRIMARY KEY (org_id, user_id)
);

-- The composite primary key serves lookups by org; this serves lookups by user.
CREATE INDEX org_members_user_id_idx ON org_members (user_id);
