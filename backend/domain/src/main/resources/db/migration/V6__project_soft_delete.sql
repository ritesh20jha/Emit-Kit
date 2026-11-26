-- Soft delete for projects (spec v1 §4). A deleted project keeps its runs, so it is marked rather
-- than removed. A repository can be connected again once its old project is deleted, so the
-- one-repository-per-organization rule applies only to projects that are not deleted.

ALTER TABLE projects ADD COLUMN deleted_at timestamptz NULL;

ALTER TABLE projects DROP CONSTRAINT projects_org_id_repo_full_name_key;

CREATE UNIQUE INDEX projects_org_repo_active_key ON projects (org_id, repo_full_name)
    WHERE deleted_at IS NULL;
