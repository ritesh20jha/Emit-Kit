import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse, type RequestHandler } from "msw";
import type { ReactNode } from "react";
import { beforeEach, expect, test, vi } from "vitest";
import type { LogLine } from "@/features/run-logs";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";
import { project, projectHandlers } from "@/test/msw/handlers/projects";
import { server } from "@/test/msw/server";
import { renderRoute, stubViewport } from "@/test/renderRoute";
import { runQuery, useRefreshRunOnStep } from "./api";

type RunDetail = components["schemas"]["RunDetail"];

/** A finished run for the run page. Each test overrides the fields it reads. */
function detail(overrides: Partial<RunDetail> = {}): RunDetail {
  return {
    id: "r1", projectId: "p1", status: "SUCCESS", trigger: "MANUAL", dryRun: false, sdkVersion: "1.4.0",
    createdAt: "2026-11-13T15:00:00Z", startedAt: "2026-11-13T15:00:00Z", finishedAt: "2026-11-13T15:01:12Z",
    currentStep: null, steps: [], lint: { errors: 0, warnings: 0, infos: 0 }, approval: null, artifactAvailable: false,
    branchName: "emitkit/generate", changeType: null, commitSha: "9f1c2ab4e5d6f7a8b9c0d1e2f3a4b5c6d7e8f9a0",
    diffSummary: null, failureCode: null, failureMessage: null, parentRunId: null, prUrl: null,
    ...overrides,
  };
}

/** Serves one run ahead of the fixture runs, plus any extra handlers. */
function serveRun(run: RunDetail, ...extra: RequestHandler[]) {
  server.use(http.get("/api/v1/runs/:runId", () => HttpResponse.json(run)), ...extra, ...projectHandlers);
}

beforeEach(() => stubViewport(1280));

test("clicking a step filters logs", async () => {
  const user = userEvent.setup();
  serveRun(detail({ status: "RUNNING", finishedAt: null, steps: [{ name: "RUN_GENERATORS", status: "RUNNING", durationMs: null }] }));
  const router = renderRoute("/runs/r1");

  await user.click(await screen.findByRole("button", { name: /run generators/i }));

  await waitFor(() => expect(router.state.location.searchStr).toBe("?step=RUN_GENERATORS"));
  expect(router.state.location.pathname).toBe("/runs/r1");
  expect(screen.getByRole("button", { name: /run generators/i })).toHaveAttribute("aria-pressed", "true");
});

test.each(["/runs/r1", "/runs/r1/logs"])("the Logs tab is the current section on %s", async (path) => {
  serveRun(detail());
  renderRoute(path);

  expect(await screen.findByRole("link", { name: "Logs" })).toHaveAttribute("aria-current", "page");
  expect(screen.getByRole("link", { name: "Files" })).not.toHaveAttribute("aria-current");
});

test.each([
  { status: "RUNNING", role: "ADMIN", artifact: false, cancel: true, rerun: false, download: false },
  { status: "QUEUED", role: "VIEWER", artifact: false, cancel: false, rerun: false, download: false },
  { status: "AWAITING_APPROVAL", role: "EDITOR", artifact: false, cancel: true, rerun: false, download: false },
  { status: "SUCCESS", role: "EDITOR", artifact: true, cancel: false, rerun: true, download: true },
  { status: "FAILED", role: "VIEWER", artifact: true, cancel: false, rerun: false, download: true },
] as const)(
  "actions follow status and permission ($role, $status)",
  async ({ status, role, artifact, cancel, rerun, download }) => {
    serveRun(detail({ status, artifactAvailable: artifact, finishedAt: null }));
    renderRoute("/runs/r1", role);

    // The project loads after the run, and its org decides the permissions. Wait for it before reading the buttons.
    await screen.findByText("acme/billing-api");

    expect(screen.queryByRole("button", { name: "Cancel run" }) !== null).toBe(cancel);
    expect(screen.queryByRole("button", { name: "Rerun" }) !== null).toBe(rerun);
    expect(screen.queryByRole("link", { name: "Download zip" }) !== null).toBe(download);
  },
);

test("admin approves with comment", async () => {
  const user = userEvent.setup();
  const decisions: unknown[] = [];
  serveRun(
    detail({ status: "AWAITING_APPROVAL", finishedAt: null, diffSummary: { added: [], modified: [], removed: [{ method: "DELETE", path: "/pets/{id}", operationId: "deletePet" }] } }),
    http.post("/api/v1/runs/:runId/approval", async ({ request }) => { decisions.push(await request.json()); return HttpResponse.json(detail({ status: "SUCCESS" })); }),
  );
  renderRoute("/runs/r1");

  await user.type(await screen.findByLabelText(/comment/i), "Breaking change is intended.");
  await user.click(screen.getByRole("button", { name: "Approve" }));

  await waitFor(() => expect(decisions).toEqual([{ decision: "APPROVED", comment: "Breaking change is intended." }]));
  await waitFor(() => expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument());
});

test("editor sees waiting message, no buttons", async () => {
  serveRun(detail({ status: "AWAITING_APPROVAL", finishedAt: null }));
  renderRoute("/runs/r1", "EDITOR");

  expect(await screen.findByText(/waiting for an admin to approve/i)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /approve|reject/i })).not.toBeInTheDocument();
  expect(screen.queryByLabelText(/comment/i)).not.toBeInTheDocument();
});

test("admin sees Approve, not the waiting message, once the project loads", async () => {
  let releaseProject = () => {};
  const projectLoaded = new Promise<void>((resolve) => (releaseProject = resolve));
  serveRun(
    detail({ status: "AWAITING_APPROVAL", finishedAt: null }),
    http.get("/api/v1/projects/:id", async () => { await projectLoaded; return HttpResponse.json(project); }),
  );
  renderRoute("/runs/r1");

  await screen.findByText(/loading the project/i);
  expect(screen.queryByText(/waiting for an admin/i)).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();

  releaseProject();
  expect(await screen.findByRole("button", { name: "Approve" })).toBeInTheDocument();
  expect(screen.queryByText(/waiting for an admin/i)).not.toBeInTheDocument();
});

test("a failed project query shows an error, not the waiting message", async () => {
  serveRun(detail({ status: "AWAITING_APPROVAL", finishedAt: null }), http.get("/api/v1/projects/:id", () => HttpResponse.json({ code: "INTERNAL", detail: "Project unavailable" }, { status: 500 })));
  renderRoute("/runs/r1");

  expect(await screen.findByText("Project unavailable")).toBeInTheDocument();
  expect(screen.queryByText(/waiting for an admin/i)).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /approve|reject|cancel run/i })).not.toBeInTheDocument();
});

test("files tab selects file from ?path", async () => {
  const requested: { path: string; diff: string | null; pathname: string }[] = [];
  serveRun(
    detail(),
    http.get("/api/v1/runs/:runId/files", () => HttpResponse.json([
      { path: "sdk/typescript/src/client.ts", sha256: "a1", size: 900, status: "MODIFIED" },
      { path: "sdk/typescript/src/index.ts", sha256: "b2", size: 120, status: "UNCHANGED" },
    ])),
    http.get("/api/v1/runs/:runId/files/*", ({ request, params }) => {
      const url = new URL(request.url);
      requested.push({ path: String(params[0]), diff: url.searchParams.get("diff"), pathname: url.pathname });
      return HttpResponse.json({ path: String(params[0]), status: "MODIFIED", content: "export const client = {};\n", unifiedDiff: "@@ -1 +1 @@\n-export const client = {};\n+export const client = { retries: 3 };\n" });
    }),
  );
  renderRoute("/runs/r1/files?path=sdk%2Ftypescript%2Fsrc%2Fclient.ts");

  expect(await screen.findByRole("button", { name: /client\.ts/ })).toHaveAttribute("aria-current", "true");
  expect(await screen.findByRole("region", { name: "Diff of sdk/typescript/src/client.ts" })).toBeInTheDocument();
  // The slashes of the manifest path reach the server unencoded, so the serializer is what this asserts.
  await waitFor(() => expect(requested).toEqual([{ path: "sdk/typescript/src/client.ts", diff: "true", pathname: "/api/v1/runs/r1/files/sdk/typescript/src/client.ts" }]));
});

test("changes tab lists breaking first", async () => {
  serveRun(detail({
    diffSummary: {
      added: [{ method: "POST", path: "/pets", operationId: "createPet" }],
      removed: [{ method: "DELETE", path: "/pets/{id}", operationId: "deletePet" }],
      modified: [{ op: { method: "GET", path: "/invoices", operationId: "listInvoices" }, changes: [
        { kind: "DESCRIPTION_CHANGED", location: "summary", breaking: false },
        { kind: "RESPONSE_PROPERTY_REMOVED", location: "response.200.total", breaking: true },
      ] }],
    },
  }));
  renderRoute("/runs/r1/changes");

  const list = await screen.findByRole("list", { name: "Changes" });
  const items = within(list).getAllByRole("listitem").map((item) => item.textContent ?? "");
  expect(items).toHaveLength(4);
  expect(items[0]).toMatch(/^Removed operation.*DELETE \/pets\/\{id\}/);
  expect(items[1]).toMatch(/^Breaking change.*response\.200\.total/);
  expect(items[2]).toMatch(/^Change.*summary/);
  expect(items[3]).toMatch(/^Added operation.*POST \/pets/);
});

const line = (step: LogLine["step"]): LogLine => ({ seq: 1, ts: "2026-11-13T15:00:00Z", level: "INFO", message: "working", step });

test("reads the run again once per new step in the log", () => {
  const queryClient = new QueryClient();
  const invalidate = vi.spyOn(queryClient, "invalidateQueries");
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  const { rerender } = renderHook(({ lines }: { lines: LogLine[] }) => useRefreshRunOnStep("r1", lines), { wrapper, initialProps: { lines: [line("RUN_GENERATORS")] } });
  expect(invalidate).toHaveBeenCalledTimes(1);
  expect(invalidate).toHaveBeenLastCalledWith({ queryKey: qk.runs.one("r1") });

  rerender({ lines: [line("RUN_GENERATORS"), line("RUN_GENERATORS")] }); // more lines, same step: no read
  expect(invalidate).toHaveBeenCalledTimes(1);
  rerender({ lines: [line("RUN_GENERATORS"), line("COMMIT_OUTPUT")] });
  expect(invalidate).toHaveBeenCalledTimes(2);
});

/** The polling run query, called as TanStack Query calls it with the cached run. */
function intervalFor(status: RunDetail["status"]) {
  const { refetchInterval } = runQuery("r1", true);
  if (typeof refetchInterval !== "function") throw new Error("runQuery should poll with a function");
  return refetchInterval({ state: { data: detail({ status }) } } as never);
}

test("polls every 10 seconds while the run is active", () => {
  for (const status of ["QUEUED", "RUNNING", "AWAITING_APPROVAL"] as const) expect(intervalFor(status)).toBe(10_000);
});

test("stops polling once the run has ended", () => {
  for (const status of ["SUCCESS", "FAILED", "CANCELLED"] as const) expect(intervalFor(status)).toBe(false);
});

test("the run page re-reads an active run every 10 seconds", async () => {
  // Only the interval is faked, so the clock still moves with real time.
  vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["setInterval", "clearInterval"] });
  let reads = 0;
  server.use(http.get("/api/v1/runs/:runId", () => { reads += 1; return HttpResponse.json(detail({ status: "RUNNING", finishedAt: null })); }), ...projectHandlers);
  try {
    renderRoute("/runs/r1");
    await screen.findByText("acme/billing-api");
    const started = reads;
    act(() => { vi.advanceTimersByTime(10_000); });
    await waitFor(() => expect(reads).toBe(started + 1));
  } finally {
    vi.useRealTimers();
  }
});

test("the files tab says the files are not there yet while the run is active", async () => {
  serveRun(detail({ status: "RUNNING", finishedAt: null }), http.get("/api/v1/runs/:runId/files", () => HttpResponse.json([])));
  renderRoute("/runs/r1/files");

  expect(await screen.findByText("Not generated yet")).toBeInTheDocument();
  expect(screen.queryByText("This run did not generate any files.")).not.toBeInTheDocument();
});
