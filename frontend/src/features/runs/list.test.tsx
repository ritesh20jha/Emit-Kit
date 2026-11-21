import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import type { components } from "@/lib/api/generated/schema";
import { projectHandlers, run, runDetail, runs } from "@/test/msw/handlers/projects";
import { server } from "@/test/msw/server";
import { renderRoute } from "@/test/renderRoute";

type RunSummary = components["schemas"]["RunSummary"];
type PageRunSummary = components["schemas"]["PageRunSummary"];

const RUNS_PATH = "/api/v1/projects/:id/runs";

// jsdom lacks the pointer-capture and scroll APIs that Radix Select calls when it opens.
Element.prototype.hasPointerCapture = () => false;
Element.prototype.releasePointerCapture = () => {};
Element.prototype.scrollIntoView = () => {};

/** Every list request, so a test can check the query string that went over the wire. */
let requests: URLSearchParams[] = [];

/** Answers the list request with `handler`, recording each query string. */
function serveRuns(handler: (params: URLSearchParams) => PageRunSummary) {
  server.use(
    http.get(RUNS_PATH, ({ request }) => {
      const params = new URL(request.url).searchParams;
      requests.push(params);
      return HttpResponse.json(handler(params));
    }),
  );
}

/** The API's own filtering of the six fixture runs by status and trigger. */
function filterRuns(params: URLSearchParams): PageRunSummary {
  const status = params.get("status");
  const trigger = params.get("trigger");
  const items = runs.filter((item) => (!status || item.status === status) && (!trigger || item.trigger === trigger));
  return { items, nextCursor: null };
}

/** jsdom has no matchMedia. The table shows from 768 px up, so a 1280 px desktop is faked, with min-width as well as max-width. */
function stubDesktop() {
  window.matchMedia = ((query: string) => {
    const min = /min-width:\s*(\d+)px/.exec(query);
    const matches = min ? 1280 >= Number(min[1]) : false;
    return { matches, media: query, addEventListener: () => {}, removeEventListener: () => {} };
  }) as unknown as typeof window.matchMedia;
}

const lastRequest = () => requests.at(-1);

beforeEach(() => {
  stubDesktop();
  requests = [];
  server.use(...projectHandlers);
  serveRuns(filterRuns);
});

afterEach(() => {
  vi.useRealTimers();
});

test("filters live in the URL", async () => {
  const user = userEvent.setup();
  const router = renderRoute("/projects/p1/runs?cursor=page-2");
  await screen.findByRole("table", { name: "Runs" });
  // The cursor from the URL reaches the request.
  expect(lastRequest()?.get("cursor")).toBe("page-2");

  await user.click(screen.getByRole("combobox", { name: "Run status" }));
  await user.click(await screen.findByRole("option", { name: "Failed" }));

  // Choosing a filter writes it to the URL and drops the cursor, since a cursor only belongs to the list it came from.
  await waitFor(() => expect(router.state.location.search).toEqual({ status: "FAILED" }));
  expect(lastRequest()?.get("status")).toBe("FAILED");
  expect(lastRequest()?.has("cursor")).toBe(false);
  expect(await within(await screen.findByRole("table", { name: "Runs" })).findByText("Failed")).toBeInTheDocument();

  await user.click(screen.getByRole("combobox", { name: "Run trigger" }));
  await user.click(await screen.findByRole("option", { name: "Webhook" }));
  await waitFor(() => expect(router.state.location.search).toEqual({ status: "FAILED", trigger: "WEBHOOK" }));
  expect(lastRequest()?.get("trigger")).toBe("WEBHOOK");
});

test("unknown filter values are dropped from the URL", async () => {
  const router = renderRoute("/projects/p1/runs?status=BOGUS&trigger=NOPE");
  await screen.findByRole("table", { name: "Runs" });
  expect(router.state.location.search).toEqual({});
  expect(lastRequest()?.has("status")).toBe(false);
  expect(lastRequest()?.has("trigger")).toBe(false);
});

test("trigger buttons hidden for viewers", async () => {
  renderRoute("/projects/p1/runs", "VIEWER");
  // Wait for the list, so the buttons have had their chance to render.
  await screen.findByRole("table", { name: "Runs" });
  expect(screen.queryByRole("button", { name: "Trigger run" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Dry run" })).not.toBeInTheDocument();
});

test("dry run posts dryRun true", async () => {
  const user = userEvent.setup();
  const bodies: unknown[] = [];
  server.use(
    http.post(RUNS_PATH, async ({ request }) => {
      bodies.push(await request.json());
      return HttpResponse.json(runDetail(run("r9", "QUEUED", "MANUAL", 0, null)), { status: 201 });
    }),
  );
  const router = renderRoute("/projects/p1/runs");

  await user.click(await screen.findByRole("button", { name: "Dry run" }));

  await waitFor(() => expect(router.state.location.pathname).toBe("/runs/r9"));
  expect(bodies).toEqual([{ dryRun: true }]);
});

test("polls while a run is active, stops after", async () => {
  // Only the interval is faked. The clock still moves with real time, so MSW and the router keep running.
  vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["setInterval", "clearInterval"] });
  let current: RunSummary[] = [run("r1", "RUNNING", "MANUAL", 0, null)];
  serveRuns(() => ({ items: current, nextCursor: null }));
  renderRoute("/projects/p1/runs");
  await screen.findByText("Running");
  const started = requests.length;

  // Still running, so one 5 s tick fetches the list again.
  act(() => {
    vi.advanceTimersByTime(5000);
  });
  await waitFor(() => expect(requests.length).toBe(started + 1));

  // The run has finished. The next tick fetches it, and the list then stops polling.
  current = [run("r1", "SUCCESS", "MANUAL", 0, 72_000)];
  act(() => {
    vi.advanceTimersByTime(5000);
  });
  expect(await screen.findByText("Succeeded")).toBeInTheDocument();
  const settled = requests.length;

  await act(async () => {
    vi.advanceTimersByTime(60_000);
    await new Promise((resolve) => setTimeout(resolve, 20));
  });
  expect(requests.length).toBe(settled);
});

test("rows show the dry-run badge, the pull request and a link to the run", async () => {
  const dry: RunSummary = { ...run("r7", "SUCCESS", "WEBHOOK", 1, 30_000, 21), dryRun: true };
  serveRuns(() => ({ items: [dry], nextCursor: null }));
  renderRoute("/projects/p1/runs");

  const table = await screen.findByRole("table", { name: "Runs" });
  expect(within(table).getByText("Dry run")).toBeInTheDocument();
  expect(within(table).getByRole("link", { name: "Pull request #21" })).toHaveAttribute(
    "href",
    "https://github.com/acme/billing-api/pull/21",
  );
  expect(within(table).getByText("1 hour ago").closest("a")).toHaveAttribute("href", "/runs/r7");
});

test("next page writes the cursor to the URL", async () => {
  const user = userEvent.setup();
  serveRuns((params) =>
    params.get("cursor") === "page-2"
      ? { items: runs.slice(5), nextCursor: null }
      : { items: runs.slice(0, 5), nextCursor: "page-2" },
  );
  const router = renderRoute("/projects/p1/runs");

  await user.click(await screen.findByRole("button", { name: "Next page" }));

  await waitFor(() => expect(router.state.location.search).toEqual({ cursor: "page-2" }));
  expect(lastRequest()?.get("cursor")).toBe("page-2");
});

test("an empty list says there are no runs yet", async () => {
  serveRuns(() => ({ items: [], nextCursor: null }));
  renderRoute("/projects/p1/runs");
  expect(await screen.findByRole("heading", { name: "No runs yet" })).toBeInTheDocument();
});

test("a failed load shows the error state with a retry", async () => {
  server.use(http.get(RUNS_PATH, () => HttpResponse.json({ code: "STORAGE_ERROR", detail: "down" }, { status: 500 })));
  renderRoute("/projects/p1/runs");
  expect(await screen.findByRole("alert")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
});

test("a forbidden list shows the no-access state", async () => {
  server.use(http.get(RUNS_PATH, () => HttpResponse.json({ code: "FORBIDDEN" }, { status: 403 })));
  renderRoute("/projects/p1/runs");
  expect(await screen.findByText(/You need admin access to view runs/)).toBeInTheDocument();
});
