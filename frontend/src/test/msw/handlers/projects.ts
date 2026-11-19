import { http, HttpResponse } from "msw";
import type { components } from "@/lib/api/generated/schema";

type ProjectDetail = components["schemas"]["ProjectDetail"];
type RunSummary = components["schemas"]["RunSummary"];
type RunDetail = components["schemas"]["RunDetail"];
type RunStatus = components["schemas"]["RunStatus"];
type RunTrigger = components["schemas"]["RunTrigger"];
type LintCounts = components["schemas"]["LintCounts"];
type VersionResponse = components["schemas"]["VersionResponse"];
type ConfigResponse = components["schemas"]["ConfigResponse"];

/** The project every project-page test opens. It belongs to org1, where defaultMe is an admin. */
export const project: ProjectDetail = {
  id: "p1",
  orgId: "org1",
  repoFullName: "acme/billing-api",
  defaultBranch: "main",
  specPath: "openapi/billing.yaml",
  outputMode: "APPEND",
  outputRepoFullName: null,
  webhook: { registered: true, webhookId: 42 },
  createdAt: "2026-11-01T10:00:00Z",
};

/** A run that started `hours` ago. A `pull` number gives it a pull request. */
export function run(
  id: string,
  status: RunStatus,
  trigger: RunTrigger,
  hours: number,
  durationMs: number | null,
  pull: number | null = null,
): RunSummary {
  const createdAt = new Date(Date.now() - hours * 3_600_000).toISOString();
  const prUrl = pull === null ? null : `https://github.com/acme/billing-api/pull/${pull}`;
  return { id, status, trigger, createdAt, durationMs, prNumber: pull, prUrl, sdkVersion: null, dryRun: false };
}

/** Six runs, newest first. The overview lists five, so the sixth shows that the limit applies. */
export const runs: RunSummary[] = [
  run("r1", "SUCCESS", "MANUAL", 2, 72_000, 18),
  run("r2", "FAILED", "WEBHOOK", 5, 9_000),
  run("r3", "SUCCESS", "WEBHOOK", 26, 65_000, 17),
  run("r4", "CANCELLED", "MANUAL", 48, null),
  run("r5", "SUCCESS", "RERUN", 72, 80_000, 16),
  run("r6", "SUCCESS", "WEBHOOK", 96, 61_000, 15),
];

const LINT: Record<string, LintCounts> = { r1: { errors: 1, warnings: 2, infos: 0 } };

/** The run detail the run's own endpoint returns, with the fields the project pages read. */
export function runDetail(summary: RunSummary): RunDetail {
  return {
    id: summary.id,
    projectId: project.id,
    status: summary.status,
    trigger: summary.trigger,
    createdAt: summary.createdAt,
    startedAt: summary.createdAt,
    finishedAt: null,
    currentStep: null,
    steps: LINT[summary.id] ? [{ name: "LINT_SPEC", status: "DONE", durationMs: 840 }] : [],
    lint: LINT[summary.id] ?? { errors: 0, warnings: 0, infos: 0 },
    approval: null,
    artifactAvailable: false,
    branchName: "emitkit/generate",
    changeType: null,
    commitSha: null,
    diffSummary: null,
    dryRun: summary.dryRun,
    failureCode: null,
    failureMessage: null,
    parentRunId: null,
    prUrl: summary.prUrl,
    sdkVersion: summary.sdkVersion,
  };
}

/** Published versions, newest first. The changelog of 1.4.0 carries markup that must show as text. */
export const versions: VersionResponse[] = [
  {
    version: "1.4.0",
    changeType: "MINOR",
    changelog: 'Added the invoice endpoints.\n\n<img src=x onerror="alert(1)">',
    createdAt: "2026-11-10T09:00:00Z",
    runId: "r1",
  },
  { version: "1.3.2", changeType: "PATCH", changelog: "Fixed a typo in the Invoice schema.", createdAt: "2026-11-02T09:00:00Z", runId: "r3" },
];

const config: ConfigResponse = {
  aiKeySet: true,
  aiModel: "gemini-2.5-flash",
  aiProvider: "GEMINI",
  breakingGate: "REQUIRE_APPROVAL",
  lintFailOn: "ERROR",
  mavenArtifactId: null,
  mavenGroupId: null,
  npmScope: "@acme",
  outputDir: "generated",
  outputs: ["SDK", "DOCS"],
  pypiName: null,
  sdkLanguages: ["TYPESCRIPT"],
  version: 3,
  versionStrategy: "EMITKIT_MANAGED",
};

/** The answers every project page needs. A test that needs another answer overrides it with server.use. */
export const projectHandlers = [
  http.get("/api/v1/projects/:id", () => HttpResponse.json(project)),
  http.get("/api/v1/projects/:id/runs", ({ request }) => {
    const limit = Number(new URL(request.url).searchParams.get("limit") ?? runs.length);
    return HttpResponse.json({ items: runs.slice(0, limit), nextCursor: null });
  }),
  http.get("/api/v1/runs/:runId", ({ params }) => {
    const found = runs.find((candidate) => candidate.id === params.runId);
    return found ? HttpResponse.json(runDetail(found)) : new HttpResponse(null, { status: 404 });
  }),
  http.get("/api/v1/projects/:id/versions", () => HttpResponse.json(versions)),
  http.get("/api/v1/projects/:id/config", () => HttpResponse.json(config)),
];
