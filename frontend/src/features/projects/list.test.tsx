import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { createAppRouter } from "@/app/router";
import type { components } from "@/lib/api/generated/schema";
import { defaultMe } from "@/test/msw/handlers/me";
import { server } from "@/test/msw/server";
import { ThemeProvider } from "@/ui";

type ProjectSummary = components["schemas"]["ProjectSummary"];
type PageProjectSummary = components["schemas"]["PageProjectSummary"];
type Role = components["schemas"]["Role"];

const PROJECTS_PATH = "/api/v1/orgs/:orgId/projects";
const hoursAgo = (hours: number) => new Date(Date.now() - hours * 3_600_000).toISOString();

const billing: ProjectSummary = { id: "p1", repoFullName: "acme/billing-api", currentVersion: "1.4.0", lastRunAt: hoursAgo(2), lastRunStatus: "SUCCESS" };
const payments: ProjectSummary = { id: "p2", repoFullName: "acme/payments-sdk", currentVersion: null, lastRunAt: hoursAgo(5), lastRunStatus: "FAILED" };
const webKit: ProjectSummary = { id: "p3", repoFullName: "acme/web-kit", currentVersion: "0.2.1", lastRunAt: null, lastRunStatus: null };
const projects = [billing, payments, webKit];

/** The API's own filtering, so the list behaves as the server does for q, status and cursor. */
function filterProjects(params: URLSearchParams): PageProjectSummary {
  const q = params.get("q");
  const status = params.get("status");
  const items = projects.filter(
    (project) => (!q || project.repoFullName.includes(q)) && (!status || project.lastRunStatus === status),
  );
  return { items, nextCursor: null };
}

// jsdom lacks the pointer-capture and scroll APIs that Radix Select calls when it opens.
Element.prototype.hasPointerCapture = () => false;
Element.prototype.releasePointerCapture = () => {};
Element.prototype.scrollIntoView = () => {};

/** Every request the list makes, so a test can check what went over the wire. */
const requests: URLSearchParams[] = [];

/** Answers the projects request with `handler`, recording the query string of each call. */
function serve(handler: (params: URLSearchParams) => PageProjectSummary) {
  server.use(
    http.get(PROJECTS_PATH, ({ request }) => {
      const params = new URL(request.url).searchParams;
      requests.push(params);
      return HttpResponse.json(handler(params));
    }),
  );
}

/** jsdom has no matchMedia. The shell and the list's table both read it, so a 1280 px viewport is faked. */
function stubViewport(width: number) {
  window.matchMedia = ((query: string) => {
    const max = /max-width:\s*(\d+)px/.exec(query);
    const min = /min-width:\s*(\d+)px/.exec(query);
    const matches = max ? width <= Number(max[1]) : min ? width >= Number(min[1]) : false;
    return { matches, media: query, addEventListener: () => {}, removeEventListener: () => {} };
  }) as unknown as typeof window.matchMedia;
}

/** Mounts the real app router at `path`, so the route file, the shell and the list all run together. */
function renderProjects(path: string, role: Role = "ADMIN") {
  if (role !== "ADMIN") {
    const membership = { orgId: "org1", name: "Acme", slug: "acme", isPersonal: false, role };
    server.use(http.get("/api/v1/me", () => HttpResponse.json({ ...defaultMe, orgs: [membership] })));
  }
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }));
  render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <RouterProvider router={router} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
  return router;
}

beforeEach(() => {
  stubViewport(1280);
  requests.length = 0;
  serve(filterProjects);
});

test("search writes q to the URL and filters", async () => {
  const user = userEvent.setup();
  const router = renderProjects("/orgs/org1");
  await screen.findByRole("link", { name: "acme/billing-api" });
  const navigate = vi.spyOn(router, "navigate");

  await user.type(screen.getByRole("searchbox", { name: "Search projects" }), "billing");

  await waitFor(() => expect(router.state.location.search).toEqual({ q: "billing" }));
  expect(navigate).toHaveBeenCalledWith(expect.objectContaining({ replace: true }));
  await waitFor(() => expect(screen.queryByRole("link", { name: "acme/payments-sdk" })).not.toBeInTheDocument());
  expect(screen.getByRole("link", { name: "acme/billing-api" })).toBeInTheDocument();
  expect(requests.at(-1)?.get("q")).toBe("billing");
});

test("status filter survives reload", async () => {
  const user = userEvent.setup();
  const router = renderProjects("/orgs/org1");
  await screen.findByRole("link", { name: "acme/billing-api" });

  await user.click(screen.getByRole("combobox", { name: "Last run status" }));
  await user.click(await screen.findByRole("option", { name: "Failed" }));
  await waitFor(() => expect(router.state.location.search).toEqual({ status: "FAILED" }));
  const urlAfterChoice = router.state.location.href;
  cleanup();

  // A reload opens the same URL again, with nothing carried over in memory.
  renderProjects(urlAfterChoice);
  expect(await screen.findByRole("link", { name: "acme/payments-sdk" })).toBeInTheDocument();
  expect(screen.queryByRole("link", { name: "acme/billing-api" })).not.toBeInTheDocument();
  expect(screen.getByRole("combobox", { name: "Last run status" })).toHaveTextContent("Failed");
  expect(requests.at(-1)?.get("status")).toBe("FAILED");
});

test("empty state links to new project", async () => {
  serve(() => ({ items: [], nextCursor: null }));
  renderProjects("/orgs/org1");

  const heading = await screen.findByRole("heading", { name: "No projects yet." });
  expect(screen.getByText("Connect a repository to start.")).toBeInTheDocument();
  const link = within(heading.parentElement as HTMLElement).getByRole("link", { name: "New project" });
  expect(link).toHaveAttribute("href", "/orgs/org1/projects/new");
});

test("viewer does not see New project", async () => {
  serve(() => ({ items: [], nextCursor: null }));
  renderProjects("/orgs/org1", "VIEWER");

  await screen.findByRole("heading", { name: "No projects yet." });
  expect(screen.getByText(/Ask an admin or editor/)).toBeInTheDocument();
  expect(screen.queryByRole("link", { name: "New project" })).not.toBeInTheDocument();
});

test("pagination loads next cursor", async () => {
  const user = userEvent.setup();
  serve((params) =>
    params.get("cursor") === "c2" ? { items: [webKit], nextCursor: null } : { items: [billing, payments], nextCursor: "c2" },
  );
  const router = renderProjects("/orgs/org1");
  await screen.findByRole("link", { name: "acme/billing-api" });

  await user.click(screen.getByRole("button", { name: "Next page" }));

  expect(await screen.findByRole("link", { name: "acme/web-kit" })).toBeInTheDocument();
  expect(screen.queryByRole("link", { name: "acme/billing-api" })).not.toBeInTheDocument();
  expect(router.state.location.search).toEqual({ cursor: "c2" });
  expect(requests.at(-1)?.get("cursor")).toBe("c2");
  expect(screen.queryByRole("button", { name: "Next page" })).not.toBeInTheDocument();
});

test("a failed load shows the error code, and Retry loads the list again", async () => {
  const user = userEvent.setup();
  server.use(
    http.get(PROJECTS_PATH, () =>
      HttpResponse.json({ type: "about:blank", title: "Storage", status: 500, code: "STORAGE_ERROR" }, { status: 500 }),
    ),
  );
  renderProjects("/orgs/org1");

  expect(await screen.findByRole("alert")).toHaveTextContent("We could not save or read stored files. Try again shortly.");
  expect(screen.getByText("STORAGE_ERROR")).toBeInTheDocument();

  serve(filterProjects);
  await user.click(screen.getByRole("button", { name: "Retry" }));
  expect(await screen.findByRole("link", { name: "acme/billing-api" })).toBeInTheDocument();
});

describe("no permission", () => {
  test("a forbidden list shows the no-access state, not an error", async () => {
    server.use(
      http.get(PROJECTS_PATH, () =>
        HttpResponse.json({ type: "about:blank", title: "Forbidden", status: 403, code: "FORBIDDEN" }, { status: 403 }),
      ),
    );
    renderProjects("/orgs/org1");

    expect(await screen.findByText(/Ask an org admin\./)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
