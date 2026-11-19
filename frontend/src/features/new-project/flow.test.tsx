import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, expect, test } from "vitest";
import { createAppRouter } from "@/app/router";
import { server } from "@/test/msw/server";
import { ThemeProvider } from "@/ui";

const BASE = "/api/v1/orgs/:orgId";
const FLOW = "/orgs/org1/projects/new";
/** The wizard at step 3 with an existing repo chosen, as a refresh or a shared link would open it. */
const AT_OUTPUT = `${FLOW}?step=3&mode=existing&repo=${encodeURIComponent("acme/billing-api")}&specPath=openapi.yaml&branch=main`;

const REPOS = [
  { fullName: "acme/billing-api", owner: "acme", defaultBranch: "main", isPrivate: false, pushedAt: "2026-11-10T09:00:00Z" },
  { fullName: "acme/payments-sdk", owner: "acme", defaultBranch: "trunk", isPrivate: true, pushedAt: "2026-11-01T09:00:00Z" },
];

const CREATED = {
  id: "p9",
  createdAt: "2026-11-19T10:00:00Z",
  defaultBranch: "main",
  orgId: "org1",
  outputMode: "APPEND",
  outputRepoFullName: null,
  repoFullName: "acme/billing-api",
  specPath: "openapi.yaml",
  webhook: { registered: true, webhookId: 42 },
};

/** Bodies the create endpoint received, in order. */
let posted: unknown[] = [];

/** jsdom has no matchMedia. The shell reads it, so a 1280 px viewport is faked. */
function stubViewport(width: number) {
  window.matchMedia = ((query: string) => {
    const max = /max-width:\s*(\d+)px/.exec(query);
    const min = /min-width:\s*(\d+)px/.exec(query);
    const matches = max ? width <= Number(max[1]) : min ? width >= Number(min[1]) : false;
    return { matches, media: query, addEventListener: () => {}, removeEventListener: () => {} };
  }) as unknown as typeof window.matchMedia;
}

beforeEach(() => {
  stubViewport(1280);
  posted = [];
  // jsdom lacks the pointer-capture and scroll APIs that Radix Select calls when it opens.
  Element.prototype.hasPointerCapture = () => false;
  Element.prototype.releasePointerCapture = () => {};
  Element.prototype.scrollIntoView = () => {};
  server.use(
    http.get(`${BASE}/vcs-repos`, ({ request }) => {
      const q = new URL(request.url).searchParams.get("q")?.toLowerCase() ?? "";
      return HttpResponse.json(REPOS.filter((repo) => repo.fullName.toLowerCase().includes(q)));
    }),
    http.get(`${BASE}/vcs-repos/:owner/:repo/spec-check`, ({ request }) => {
      const path = new URL(request.url).searchParams.get("path");
      const found = path === "api/billing.yaml";
      return HttpResponse.json({
        found,
        valid: found,
        title: found ? "Billing API" : null,
        version: found ? "1.4.0" : null,
        errors: [],
      });
    }),
    http.post(`${BASE}/projects`, async ({ request }) => {
      posted.push(await request.json());
      return HttpResponse.json(CREATED, { status: 201 });
    }),
  );
});

/** Mounts the real app router at `url`, so the route file, the shell and the wizard run together. */
function renderFlow(url: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [url] }));
  render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <RouterProvider router={router} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
  return router;
}

/** Picks an option from a Radix select by its label. */
async function choose(user: UserEvent, field: string, option: string) {
  await user.click(await screen.findByRole("combobox", { name: field }));
  await user.click(await screen.findByRole("option", { name: option }));
}

test("step is kept in the URL; back and refresh keep it", async () => {
  const user = userEvent.setup();
  const router = renderFlow(FLOW);
  await user.click(await screen.findByRole("button", { name: /acme\/billing-api/ }));
  await user.click(screen.getByRole("button", { name: "Next" }));

  expect(await screen.findByRole("heading", { level: 2, name: "Spec" })).toBeInTheDocument();
  expect(router.state.location.search).toMatchObject({ step: 2, repo: "acme/billing-api" });

  await user.click(screen.getByRole("button", { name: "Back" }));
  expect(await screen.findByRole("heading", { level: 2, name: "Repository" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /acme\/billing-api/ })).toHaveAttribute("aria-pressed", "true");
  expect(router.state.location.search).toMatchObject({ step: 1 });

  // A refresh reopens the same URL: the step and the values written when it changed are restored.
  await user.click(screen.getByRole("button", { name: "Next" }));
  await screen.findByRole("heading", { level: 2, name: "Spec" });
  const url = router.state.location.href;
  cleanup();
  renderFlow(url);
  expect(await screen.findByRole("heading", { level: 2, name: "Spec" })).toBeInTheDocument();
  expect(screen.getByLabelText("Spec path")).toHaveValue("openapi.yaml");
  expect(screen.getByLabelText("Default branch")).toHaveValue("main");
});

test("spec-check result shows title and version", async () => {
  const user = userEvent.setup();
  renderFlow(`${FLOW}?step=2&mode=existing&repo=${encodeURIComponent("acme/billing-api")}`);
  const path = await screen.findByLabelText("Spec path");
  await user.clear(path);
  await user.type(path, "api/billing.yaml");

  expect(await screen.findByText(/Billing API/)).toBeInTheDocument();
  expect(screen.getByText(/1\.4\.0/)).toBeInTheDocument();
});

test("taken repo name shows inline error", async () => {
  const user = userEvent.setup();
  renderFlow(FLOW);
  await choose(user, "Repository", "New repository");
  // The match is on the owner-qualified name, case-insensitively: "BILLING-api" is acme/billing-api.
  await user.type(screen.getByLabelText("Repository name"), "BILLING-api");

  expect(await screen.findByText(/acme\/billing-api already exists/)).toBeInTheDocument();
  expect(screen.getByLabelText("Repository name")).toHaveAttribute("aria-invalid", "true");
});

test("separate mode requires output repo", async () => {
  const user = userEvent.setup();
  renderFlow(AT_OUTPUT);
  await choose(user, "Output mode", "Separate repository");
  await user.click(screen.getByRole("button", { name: "Create project" }));

  expect(await screen.findByText("Choose the repository that receives the output.")).toBeInTheDocument();
  expect(posted).toHaveLength(0);
});

test("finish navigates to config with welcome", async () => {
  const user = userEvent.setup();
  const router = renderFlow(AT_OUTPUT);
  await user.click(await screen.findByRole("button", { name: "Create project" }));

  await waitFor(() => expect(router.state.location.pathname).toBe("/projects/p9/config"));
  expect(router.state.location.search).toEqual({ welcome: 1 });
  // The form's values reach the API in its own terms: lowercase in the URL, enums on the wire.
  expect(posted).toEqual([
    {
      repo: "acme/billing-api",
      mode: "EXISTING",
      specPath: "openapi.yaml",
      defaultBranch: "main",
      outputMode: "APPEND",
      outputRepo: null,
      visibility: null,
    },
  ]);
});

test("409 duplicate shows message on step 1", async () => {
  server.use(
    http.post(`${BASE}/projects`, () =>
      HttpResponse.json(
        {
          type: "about:blank",
          title: "Conflict",
          status: 409,
          detail: "acme/billing-api already has an EmitKit project.",
          code: "CONFLICT",
        },
        { status: 409 },
      ),
    ),
  );
  const user = userEvent.setup();
  const router = renderFlow(AT_OUTPUT);
  await user.click(await screen.findByRole("button", { name: "Create project" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("already has an EmitKit project");
  expect(router.state.location.search).toMatchObject({ step: 1 });
  expect(screen.getByRole("heading", { level: 2, name: "Repository" })).toBeInTheDocument();
});
