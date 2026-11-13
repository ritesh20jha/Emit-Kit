/** Query-key factory. Mutations invalidate these exact keys, so every key comes from here. */
export const qk = {
  me: ["me"],
  vcsQuota: ["me", "vcs-quota"],
  orgs: {
    all: ["orgs"],
    one: (id: string) => ["orgs", id],
    members: (id: string) => ["orgs", id, "members"],
  },
  projects: {
    list: (orgId: string, q: Record<string, unknown> = {}) => ["orgs", orgId, "projects", q],
    one: (id: string) => ["projects", id],
    config: (id: string) => ["projects", id, "config"],
    spec: (id: string) => ["projects", id, "spec"],
  },
  runs: {
    list: (projectId: string, filters: Record<string, unknown> = {}) => ["projects", projectId, "runs", filters],
    one: (id: string) => ["runs", id],
    logs: (id: string) => ["runs", id, "logs"],
    files: (id: string) => ["runs", id, "files"],
  },
  versions: (projectId: string) => ["projects", projectId, "versions"],
};
