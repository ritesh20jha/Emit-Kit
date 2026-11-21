/** Query-key factory. Mutations invalidate these exact keys, so every key comes from here. */
export const qk = {
  me: ["me"],
  vcsQuota: ["me", "vcs-quota"],
  orgs: {
    all: ["orgs"],
    one: (id: string) => ["orgs", id],
    members: (id: string) => ["orgs", id, "members"],
  },
  vcsRepos: (orgId: string, q: string) => ["orgs", orgId, "vcs-repos", q],
  specCheck: (orgId: string, repo: string, path: string, branch: string) => [
    "orgs",
    orgId,
    "vcs-repos",
    repo,
    "spec-check",
    path,
    branch,
  ],
  projects: {
    forOrg: (orgId: string) => ["orgs", orgId, "projects"],
    list: (orgId: string, q: Record<string, unknown> = {}) => ["orgs", orgId, "projects", q],
    one: (id: string) => ["projects", id],
    config: (id: string) => ["projects", id, "config"],
    spec: (id: string) => ["projects", id, "spec"],
    /** The server's spec as of a conflicting sha. Under the spec key, so a save invalidates it too. */
    currentSpec: (id: string, sha: string) => ["projects", id, "spec", "current", sha],
    snapshots: (id: string) => ["projects", id, "spec", "snapshots"],
    specDiff: (id: string, from: string, to: string) => ["projects", id, "spec", "diff", from, to],
  },
  runs: {
    list: (projectId: string, filters: Record<string, unknown> = {}) => ["projects", projectId, "runs", filters],
    one: (id: string) => ["runs", id],
    logs: (id: string) => ["runs", id, "logs"],
    files: (id: string) => ["runs", id, "files"],
  },
  versions: (projectId: string) => ["projects", projectId, "versions"],
  /** Lint issues for one text. The text is the key, so an unchanged text is linted once. */
  lint: (content: string) => ["lint", content],
  /** Browser-side validation findings for one text. */
  validation: (content: string) => ["validation", content],
};
