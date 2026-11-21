import { http, HttpResponse } from "msw";
import type { components } from "@/lib/api/generated/schema";
import { project } from "./projects";

type MemberResponse = components["schemas"]["MemberResponse"];
type RepoOption = components["schemas"]["RepoOption"];

/** The org the signed-in user is an admin of. Its id matches defaultMe.orgs. */
export const org = {
  id: "org1",
  isPersonal: false,
  memberCount: 3,
  name: "Acme",
  role: "ADMIN",
  slug: "acme",
  vcsOrgId: "1001",
} as const;

export const members: MemberResponse[] = [
  { userId: "u1", login: "octocat", name: "Octo Cat", avatarUrl: "https://example.test/octocat.png", role: "ADMIN", roleSource: "SYNC" },
  { userId: "u2", login: "hubot", name: "Hubot", avatarUrl: "https://example.test/hubot.png", role: "EDITOR", roleSource: "MANUAL" },
  { userId: "u3", login: "monalisa", name: null, avatarUrl: "https://example.test/monalisa.png", role: "VIEWER", roleSource: "SYNC" },
];

export const repos: RepoOption[] = [
  { fullName: "acme/billing-api", owner: "acme", defaultBranch: "main", isPrivate: true, pushedAt: "2026-11-18T10:00:00Z" },
  { fullName: "acme/ledger-sdk", owner: "acme", defaultBranch: "main", isPrivate: false, pushedAt: "2026-11-02T09:00:00Z" },
];

/** The org pages: detail, members, repository picker and project list. */
export const orgHandlers = [
  http.get("/api/v1/orgs/:orgId", () => HttpResponse.json(org)),
  http.get("/api/v1/orgs/:orgId/members", () => HttpResponse.json(members)),
  http.get("/api/v1/orgs/:orgId/vcs-repos", () => HttpResponse.json(repos)),
  http.get("/api/v1/orgs/:orgId/vcs-repos/:owner/:repo/spec-check", () =>
    HttpResponse.json({ found: true, valid: true, title: "Billing API", version: "1.4.0", errors: [] }),
  ),
  http.get("/api/v1/orgs/:orgId/projects", () =>
    HttpResponse.json({
      items: [
        { id: project.id, repoFullName: project.repoFullName, currentVersion: "1.4.0", lastRunAt: "2026-11-21T08:00:00Z", lastRunStatus: "SUCCESS" },
        { id: "p2", repoFullName: "acme/ledger-sdk", currentVersion: null, lastRunAt: null, lastRunStatus: null },
      ],
      nextCursor: null,
    }),
  ),
];
