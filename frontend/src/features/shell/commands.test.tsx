import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRootRoute, createRoute, createRouter, Outlet, RouterProvider, useLocation } from "@tanstack/react-router";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import type { MockInstance } from "vitest";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";
import type { Me } from "@/lib/auth/session";
import { defaultMe } from "@/test/msw/handlers/me";
import { projectHandlers } from "@/test/msw/handlers/projects";
import { server } from "@/test/msw/server";
import { renderRoute } from "@/test/renderRoute";
import { ThemeProvider, Toaster } from "@/ui";
import { AppShell } from "./AppShell";
import { LAST_ORG_KEY } from "./lastOrg";

type Role = components["schemas"]["OrgMembership"]["role"];
type ProjectDetail = components["schemas"]["ProjectDetail"];
type ProjectSummary = components["schemas"]["ProjectSummary"];

// cmdk measures its list with ResizeObserver and scrolls the selected row into view. jsdom has neither.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver;
Element.prototype.scrollIntoView = () => {};

// Any console output is a failure: React and library warnings must not reach the test log.
let consoleSpies: MockInstance[] = [];
beforeEach(() => {
  consoleSpies = [vi.spyOn(console, "error"), vi.spyOn(console, "warn")];
  // jsdom has no matchMedia. The shell asks for the mobile breakpoint, so answer it as a desktop.
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  })) as unknown as typeof window.matchMedia;
});
afterEach(() => {
  // Capture the output, then restore every spy before asserting, so a failing assertion cannot leak a spy.
  const output = consoleSpies.flatMap((spy) => spy.mock.calls);
  for (const spy of consoleSpies) spy.mockRestore();
  consoleSpies = [];
  localStorage.removeItem(LAST_ORG_KEY);
  expect(output).toEqual([]);
});

/** Acme (org1) holds the project with `role`. Globex (org2) is a second org the user may switch to. */
function meWithRole(role: Role): Me {
  return {
    ...defaultMe,
    orgs: [
      { orgId: "org1", name: "Acme", slug: "acme", isPersonal: false, role },
      { orgId: "org2", name: "Globex", slug: "globex", isPersonal: true, role: "ADMIN" },
    ],
  };
}

function projectSummary(id: string, repoFullName: string): ProjectSummary {
  return { id, repoFullName, currentVersion: null, lastRunAt: null, lastRunStatus: null };
}

/** Project p1 is in org1, as the detail query would have it after the project page loads. */
function seedProjectDetail(queryClient: QueryClient) {
  const detail: ProjectDetail = {
    id: "p1",
    orgId: "org1",
    repoFullName: "acme/api",
    createdAt: "2026-11-01T10:00:00Z",
    defaultBranch: "main",
    outputMode: "APPEND",
    outputRepoFullName: null,
    specPath: "openapi.yaml",
    webhook: { registered: true, webhookId: 1 },
  };
  queryClient.setQueryData(qk.projects.one("p1"), detail);
}

const PAGE_PATHS = [
  "/orgs/$orgId",
  "/orgs/$orgId/members",
  "/orgs/$orgId/settings",
  "/projects/$id",
  "/projects/$id/runs",
  "/projects/$id/editor",
  "/runs/$runId",
];

/** Shows the pathname so a test can read where a command navigated. */
function Page() {
  return (
    <ThemeProvider>
      <AppShell>
        <input aria-label="Page search" data-search="" />
        <Outlet />
        <output data-testid="where">{useLocation().pathname}</output>
      </AppShell>
      <Toaster />
    </ThemeProvider>
  );
}

/** The app shell over real routes at `path`, so the palette reads route params the way the app does. */
function renderAt(path: string, me: Me, seed: (queryClient: QueryClient) => void = () => {}) {
  // staleTime Infinity keeps the seeded session from being refetched over the stub.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  queryClient.setQueryData(qk.me, me);
  seed(queryClient);
  const root = createRootRoute({ component: Page });
  const routeTree = root.addChildren(PAGE_PATHS.map((path) => createRoute({ getParentRoute: () => root, path })));
  const router = createRouter({ routeTree, history: createMemoryHistory({ initialEntries: [path] }) });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

const where = () => screen.getByTestId("where");

test("palette lists cached projects", async () => {
  const user = userEvent.setup();
  renderAt("/orgs/org1", meWithRole("ADMIN"), (queryClient) => {
    queryClient.setQueryData(qk.projects.list("org1", {}), {
      items: [projectSummary("p1", "acme/api"), projectSummary("p2", "acme/web")],
      nextCursor: null,
    });
  });

  await user.keyboard("{Control>}k{/Control}");
  expect(await screen.findByRole("option", { name: "acme/api" })).toBeInTheDocument();
  await user.click(screen.getByRole("option", { name: "acme/web" }));

  await waitFor(() => expect(where()).toHaveTextContent("/projects/p2"));
});

test("Trigger run hidden for viewers", async () => {
  const user = userEvent.setup();
  renderAt("/projects/p1", meWithRole("VIEWER"), seedProjectDetail);

  await user.keyboard("{Control>}k{/Control}");
  // Open editor needs only SPEC_VIEW, which viewers have, so it marks the palette as loaded.
  expect(await screen.findByRole("option", { name: "Open editor" })).toBeInTheDocument();
  expect(screen.queryByRole("option", { name: "Trigger run" })).not.toBeInTheDocument();
  expect(screen.queryByRole("option", { name: "Dry run" })).not.toBeInTheDocument();
  cleanup();

  renderAt("/projects/p1", meWithRole("ADMIN"), seedProjectDetail);
  await user.keyboard("{Control>}k{/Control}");
  expect(await screen.findByRole("option", { name: "Trigger run" })).toBeInTheDocument();
  expect(screen.getByRole("option", { name: "Dry run" })).toBeInTheDocument();
});

test("g r on a project page goes to its runs", async () => {
  const user = userEvent.setup();
  renderAt("/projects/p1", meWithRole("ADMIN"), seedProjectDetail);

  await user.keyboard("gr");

  await waitFor(() => expect(where()).toHaveTextContent("/projects/p1/runs"));
});

test("/ focuses the first [data-search] element on the page", async () => {
  const user = userEvent.setup();
  renderAt("/orgs/org1", meWithRole("ADMIN"));

  await user.keyboard("/");

  expect(screen.getByRole("textbox", { name: "Page search" })).toHaveFocus();
});

test("switch org lists the other orgs, not the current one", async () => {
  const user = userEvent.setup();
  renderAt("/orgs/org1", meWithRole("ADMIN"));

  await user.keyboard("{Control>}k{/Control}");
  expect(await screen.findByRole("option", { name: "Switch to Globex" })).toBeInTheDocument();
  expect(screen.queryByRole("option", { name: "Switch to Acme" })).not.toBeInTheDocument();
  await user.click(screen.getByRole("option", { name: "Switch to Globex" }));

  await waitFor(() => expect(where()).toHaveTextContent("/orgs/org2"));
});

/** Answers the start-run POST for p1 with `status` and `body`, and returns the request bodies it received. */
function stubStartRun(status: number, body: Record<string, unknown>) {
  const bodies: unknown[] = [];
  server.use(
    http.post("/api/v1/projects/p1/runs", async ({ request }) => {
      bodies.push(await request.json());
      return HttpResponse.json(body, { status });
    }),
  );
  return bodies;
}

async function chooseFromPalette(user: ReturnType<typeof userEvent.setup>, label: string) {
  await user.keyboard("{Control>}k{/Control}");
  await user.click(await screen.findByRole("option", { name: label }));
}

test("Trigger run posts a live run and opens it", async () => {
  const user = userEvent.setup();
  const bodies = stubStartRun(201, { id: "r1" });
  renderAt("/projects/p1", meWithRole("ADMIN"), seedProjectDetail);

  await chooseFromPalette(user, "Trigger run");

  await waitFor(() => expect(where()).toHaveTextContent("/runs/r1"));
  expect(bodies).toEqual([{ dryRun: false }]);
});

test("Dry run posts a dry run and opens it", async () => {
  const user = userEvent.setup();
  const bodies = stubStartRun(201, { id: "r2" });
  renderAt("/projects/p1", meWithRole("ADMIN"), seedProjectDetail);

  await chooseFromPalette(user, "Dry run");

  await waitFor(() => expect(where()).toHaveTextContent("/runs/r2"));
  expect(bodies).toEqual([{ dryRun: true }]);
});

test("a failed start shows the API error and keeps the user on the page", async () => {
  const user = userEvent.setup();
  stubStartRun(403, { type: "about:blank", title: "Forbidden", status: 403, code: "FORBIDDEN" });
  renderAt("/projects/p1", meWithRole("ADMIN"), seedProjectDetail);

  await chooseFromPalette(user, "Trigger run");

  expect(await screen.findByText("You don't have permission to do this.")).toBeInTheDocument();
  expect(where()).toHaveTextContent(/^\/projects\/p1$/);
});

test("? opens the shortcut help, which lists g p for Projects", async () => {
  const user = userEvent.setup();
  renderAt("/orgs/org1", meWithRole("ADMIN"));

  await user.keyboard("?");

  const help = await screen.findByRole("dialog", { name: "Keyboard shortcuts" });
  const row = within(help).getByText("Projects").closest("li") as HTMLElement;
  expect(within(row).getByText("g")).toBeInTheDocument();
  expect(within(row).getByText("p")).toBeInTheDocument();
});

test("project actions appear on a real project page", async () => {
  const user = userEvent.setup();
  server.use(...projectHandlers);
  renderRoute("/projects/p1");
  // The layout's heading shows once the project is loaded, which also puts its org in the cache for the palette.
  await screen.findByRole("heading", { name: "acme/billing-api" });

  await user.keyboard("{Control>}k{/Control}");

  for (const label of ["Runs", "Trigger run", "Dry run", "Open editor"]) {
    // A command with a shortcut hint reads as its label followed by the keys ("Runsgr"), so the label is a prefix.
    expect(await screen.findByRole("option", { name: new RegExp(`^${label}`) })).toBeInTheDocument();
  }
});
