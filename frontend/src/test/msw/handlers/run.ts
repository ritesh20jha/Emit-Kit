import { http, HttpResponse } from "msw";
import { sse } from "msw/sse";
import { runDetail, runs } from "./projects";
import { SPEC_DIFF } from "./spec";

/** The run detail with the spec diff the run produced, so the changes page has content. Listed before the project handlers. */
export const runChangesHandler = http.get("/api/v1/runs/:runId", ({ params }) => {
  const found = runs.find((candidate) => candidate.id === params.runId);
  if (found === undefined) return new HttpResponse(null, { status: 404 });
  return HttpResponse.json({ ...runDetail(found), diffSummary: SPEC_DIFF });
});

/** The files a run produced, and the lint issues it reported. */
export const runFileHandlers = [
  http.get("/api/v1/runs/:runId/files", () =>
    HttpResponse.json([
      { path: "sdk/typescript/src/index.ts", sha256: "3f9a", size: 1280, status: "ADDED" },
      { path: "docs/README.md", sha256: "b71e", size: 904, status: "MODIFIED" },
      { path: "package.json", sha256: "c20d", size: 412, status: "UNCHANGED" },
    ]),
  ),
  http.get("/api/v1/runs/:runId/files/*", ({ params }) =>
    HttpResponse.json({
      path: String(params[0]),
      status: "MODIFIED",
      content: "export const version = '1.4.0';\n",
      unifiedDiff: "@@ -1 +1 @@\n-export const version = '1.3.2';\n+export const version = '1.4.0';\n",
    }),
  ),
  http.get("/api/v1/runs/:runId/lint", () =>
    HttpResponse.json([
      { ruleId: "operation-operationId", severity: "ERROR", message: "Operation has no operationId.", jsonPath: "$.paths./orders.post", line: 14 },
      { ruleId: "info-contact", severity: "WARNING", message: "Info object should contain contact.", jsonPath: "$.info", line: 2 },
      { ruleId: "tag-description", severity: "INFO", message: "Tag has no description.", jsonPath: "$.tags[0]", line: null },
    ]),
  ),
];

/** Log lines for the run's live log view, sent as the named `log` event, then `done`. */
export const runLogHandler = sse<{ log: string; done: string }>("/api/v1/runs/:runId/logs/stream", ({ client }) => {
  const lines = [
    { seq: 1, ts: "2026-11-21T08:00:01Z", level: "INFO", step: "FETCH_SPEC", message: "Fetched openapi/billing.yaml at a1b2c3d" },
    { seq: 2, ts: "2026-11-21T08:00:02Z", level: "INFO", step: "LINT_SPEC", message: "Linted with 1 error and 2 warnings" },
    { seq: 3, ts: "2026-11-21T08:00:04Z", level: "WARN", step: "BREAKING_GATE", message: "Breaking change needs approval" },
    { seq: 4, ts: "2026-11-21T08:00:06Z", level: "INFO", step: "RUN_GENERATORS", message: "Generated 2 SDK packages" },
  ];
  for (const line of lines) client.send({ event: "log", data: JSON.stringify(line) });
  client.send({ event: "done", data: "" });
});
