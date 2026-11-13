import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRootRoute, createRoute, createRouter, Outlet, RouterProvider, useLocation } from "@tanstack/react-router";
import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { createAppRouter } from "@/app/router";
import { resetLoginRedirectForTests } from "@/lib/api/client";
import { qk } from "@/lib/api/queryKeys";
import type { Me } from "@/lib/auth/session";
import { defaultMe } from "@/test/msw/handlers/me";
import { server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { ThemeProvider } from "@/ui";
import { AppShell, ShellError } from "./AppShell";
import { OrgSwitcher } from "./OrgSwitcher";
import { QuotaIndicator } from "./QuotaIndicator";
import { Sidebar } from "./Sidebar";
import { LAST_ORG_KEY } from "./lastOrg";
import { UserMenu } from "./UserMenu";

const personalMe: Me = {
  ...defaultMe,
  orgs: [
    { orgId: "org1", name: "Acme", slug: "acme", isPersonal: false, role: "ADMIN" },
    { orgId: "org2", name: "Globex", slug: "globex", isPersonal: true, role: "ADMIN" },
  ],
};
const RESET_AT = "2026-11-13T15:30:00Z";
const UNAUTHENTICATED = { type: "about:blank", title: "Unauthenticated", status: 401, code: "UNAUTHENTICATED" };

/** jsdom cannot navigate, so window.location becomes a settable stand-in (as in client.test.ts). */
function stubLocationAt(path: string) {
  window.history.pushState({}, "", path);
  const { origin, pathname, search, href } = window.location;
  const location = { origin, pathname, search, href };
  vi.stubGlobal("location", location);
  return location;
}

/** jsdom has no matchMedia. This fake answers max-width queries for a viewport `width` pixels wide. */
function stubViewport(width: number) {
  window.matchMedia = ((query: string) => {
    const max = /max-width:\s*(\d+)px/.exec(query);
    return { matches: max !== null && width <= Number(max[1]), media: query, addEventListener: () => {}, removeEventListener: () => {} };
  }) as unknown as typeof window.matchMedia;
}

/** Mounts the whole app router at `path`. `/me` answers with `me`, or 401 when `me` is null (signed out). */
function renderApp(path: string, me: Me | null = personalMe) {
  server.use(http.get("/api/v1/me", () => (me === null ? HttpResponse.json(UNAUTHENTICATED, { status: 401 }) : HttpResponse.json(me))));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }));
  render(<QueryClientProvider client={queryClient}><ThemeProvider><RouterProvider router={router} /></ThemeProvider></QueryClientProvider>);
  return router;
}

/** The shell at /orgs/org1 with `children` as its page. */
function renderShell(children: ReactNode) {
  return renderWithProviders(<ThemeProvider><AppShell>{children}</AppShell></ThemeProvider>, { route: "/orgs/org1", me: personalMe });
}

/** Shows the current pathname so a test can read where navigation went. */
function Where() {
  return <output data-testid="where">{useLocation().pathname}</output>;
}

beforeEach(() => {
  stubViewport(1280);
  localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  resetLoginRedirectForTests();
  window.history.pushState({}, "", "/");
  localStorage.clear();
  document.cookie = "XSRF-TOKEN=; Max-Age=0; path=/";
  delete document.documentElement.dataset.theme;
});

test("unauthenticated user is sent to login with next", async () => {
  // Only routes under the guarded layout are checked, and the first one is the home page. Org pages join it later.
  const location = stubLocationAt("/?status=FAILED");
  const router = renderApp("/?status=FAILED", null);

  await waitFor(() => expect(router.state.location.pathname).toBe("/login"));
  expect(router.state.location.search).toEqual({ next: "/?status=FAILED" });

  await userEvent.click(await screen.findByRole("button", { name: "Sign in with GitHub" }));
  expect(location.href).toBe("/oauth2/authorization/github?next=%2F%3Fstatus%3DFAILED");
});

test("root redirects to last used org, falls back to personal", async () => {
  localStorage.setItem(LAST_ORG_KEY, "org1");
  const used = renderApp("/");
  await waitFor(() => expect(used.state.location.pathname).toBe("/orgs/org1"));
  cleanup();

  // The remembered org is no longer a membership, so the personal org is used.
  localStorage.setItem(LAST_ORG_KEY, "org-gone");
  const fallback = renderApp("/");
  await waitFor(() => expect(fallback.state.location.pathname).toBe("/orgs/org2"));
});

test("switching org updates the URL and remembers it", async () => {
  const user = userEvent.setup();
  renderWithProviders(<><OrgSwitcher orgs={personalMe.orgs} currentOrgId="org1" /><Where /></>, { route: "/orgs/org1", me: personalMe });

  await user.click(await screen.findByRole("button", { name: "Acme" }));
  await user.click(await screen.findByRole("button", { name: "Globex" }));

  await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/orgs/org2"));
  expect(localStorage.getItem(LAST_ORG_KEY)).toBe("org2");
});

test("visiting an org page remembers it for the next visit to /", async () => {
  // An ad-hoc org route under the shell: the real route tree has no org pages until WEB-04/05.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(qk.me, personalMe);
  const root = createRootRoute({ component: Outlet });
  const orgPage = createRoute({ getParentRoute: () => root, path: "/orgs/$orgId", component: () => <AppShell><Where /></AppShell> });
  const router = createRouter({ routeTree: root.addChildren([orgPage]), history: createMemoryHistory({ initialEntries: ["/orgs/org1"] }) });
  render(<QueryClientProvider client={queryClient}><ThemeProvider><RouterProvider router={router} /></ThemeProvider></QueryClientProvider>);
  await waitFor(() => expect(localStorage.getItem(LAST_ORG_KEY)).toBe("org1"));
  cleanup();

  // Globex (org2) is the personal org, so landing on org1 proves the remembered visit won.
  const home = renderApp("/");
  await waitFor(() => expect(home.state.location.pathname).toBe("/orgs/org1"));
});

describe("quota indicator", () => {
  let requests = 0;
  const quota = (remaining: number) =>
    http.get("/api/v1/me/vcs-quota", () => {
      requests += 1;
      return HttpResponse.json({ limit: 1000, remaining, resetAt: RESET_AT });
    });

  async function renderQuota(remaining: number) {
    server.use(quota(remaining));
    const { queryClient } = renderWithProviders(<QuotaIndicator />);
    await waitFor(() => expect(queryClient.getQueryState(qk.vcsQuota)?.status).toBe("success"));
  }

  afterEach(() => {
    vi.useRealTimers();
    requests = 0;
  });

  test("quota indicator hidden at 50%, shown at 10%", async () => {
    await renderQuota(500);
    expect(screen.queryByText(/GitHub quota low/)).not.toBeInTheDocument();
    cleanup();

    await renderQuota(100);
    const resetAt = new Date(RESET_AT);
    const pad = (n: number) => String(n).padStart(2, "0");
    expect(await screen.findByText(`GitHub quota low: resets at ${pad(resetAt.getHours())}:${pad(resetAt.getMinutes())}`)).toBeInTheDocument();
  });

  test.each([
    [199, true],
    [200, false],
    [201, false],
  ])("with %i of 1000 left, the warning is shown: %s", async (remaining, shown) => {
    await renderQuota(remaining);
    if (shown) expect(await screen.findByText(/GitHub quota low/)).toBeInTheDocument();
    else expect(screen.queryByText(/GitHub quota low/)).not.toBeInTheDocument();
  });

  test("polls the quota again after 60 seconds", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    server.use(quota(900));
    renderWithProviders(<QuotaIndicator />);
    await waitFor(() => expect(requests).toBe(1));

    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    await waitFor(() => expect(requests).toBe(2));
  });
});

test("mobile drawer at 360px opens and traps focus", async () => {
  stubViewport(360);
  const user = userEvent.setup();
  renderShell(<p>Page content</p>);

  await user.click(await screen.findByRole("button", { name: "Open navigation" }));
  const drawer = await screen.findByRole("dialog", { name: "Navigation" });
  expect(within(drawer).getByRole("link", { name: "Members" })).toBeInTheDocument();
  await waitFor(() => expect(drawer.contains(document.activeElement)).toBe(true));

  // Tab keeps focus inside the drawer for more presses than it has tab stops.
  for (let i = 0; i < 10; i++) {
    await user.tab();
    expect(drawer.contains(document.activeElement)).toBe(true);
  }

  await user.keyboard("{Escape}");
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Navigation" })).not.toBeInTheDocument());
  expect(screen.getByRole("button", { name: "Open navigation" })).toHaveFocus();
});

test("picking an org inside the mobile drawer closes it", async () => {
  stubViewport(360);
  localStorage.setItem(LAST_ORG_KEY, "org1");
  const user = userEvent.setup();
  renderShell(<Where />);

  await user.click(await screen.findByRole("button", { name: "Open navigation" }));
  const drawer = await screen.findByRole("dialog", { name: "Navigation" });
  await user.click(within(drawer).getByRole("button", { name: "Acme" }));
  await user.click(await screen.findByRole("button", { name: "Globex" }));

  await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/orgs/org2"));
  expect(localStorage.getItem(LAST_ORG_KEY)).toBe("org2");
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Navigation" })).not.toBeInTheDocument());
});

test("org section links are current only on their own page", async () => {
  renderWithProviders(<Sidebar orgs={personalMe.orgs} currentOrgId="org1" />, { route: "/orgs/org1/members", me: personalMe });

  expect(await screen.findByRole("link", { name: "Members" })).toHaveAttribute("aria-current", "page");
  expect(screen.getByRole("link", { name: "Projects" })).not.toHaveAttribute("aria-current");
});

test.each(["/\\evil.com", "//evil.com", "https://evil.com", "javascript:alert(1)"])("login returns only to same-site paths, not %s", async (next) => {
  const path = `/login?next=${encodeURIComponent(next)}`;
  const location = stubLocationAt(path);
  renderApp(path);

  await userEvent.click(await screen.findByRole("button", { name: "Sign in with GitHub" }));
  expect(location.href).toBe("/oauth2/authorization/github?next=%2F");
});

test("a failed sign-in says so above the button", async () => {
  renderApp("/login?error");

  expect(await screen.findByRole("alert")).toHaveTextContent("Sign-in didn't complete. Try again.");
});

test("signing out posts the logout request with the CSRF header and returns to login", async () => {
  document.cookie = "XSRF-TOKEN=token-123; path=/";
  let csrf: string | null = null;
  server.use(
    http.post("/api/v1/logout", ({ request }) => {
      csrf = request.headers.get("X-XSRF-TOKEN");
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const location = stubLocationAt("/orgs/org1");
  const user = userEvent.setup();
  const { queryClient } = renderWithProviders(<UserMenu user={defaultMe} />, { me: personalMe });

  await user.click(screen.getByRole("button", { name: /Octo Cat/ }));
  await user.click(await screen.findByRole("menuitem", { name: "Sign out" }));

  await waitFor(() => expect(location.href).toBe("/login"));
  expect(csrf).toBe("token-123");
  expect(queryClient.getQueryData(qk.me)).toBeUndefined();
});

test("choosing Dark sets the theme on the document", async () => {
  const user = userEvent.setup();
  renderShell(<p>Page content</p>);

  await user.click(await screen.findByRole("button", { name: "Theme: System" }));
  await user.click(await screen.findByRole("menuitem", { name: "Dark" }));

  await waitFor(() => expect(document.documentElement.dataset.theme).toBe("dark"));
});

test("a network failure at the shell shows a plain message, not the raw error", async () => {
  renderWithProviders(<ShellError error={new TypeError("Failed to fetch")} />, { route: "/" });

  expect(await screen.findByText("EmitKit could not load this page.")).toBeInTheDocument();
  expect(screen.queryByText("Failed to fetch")).not.toBeInTheDocument();
});

describe("org switcher search", () => {
  const orgsOf = (count: number) =>
    Array.from({ length: count }, (_, i) => ({ orgId: `o${i}`, name: `Org ${i}`, slug: `org-${i}`, isPersonal: i === 0, role: "ADMIN" as const }));

  test("shows no search field for seven orgs or fewer", async () => {
    const user = userEvent.setup();
    renderWithProviders(<OrgSwitcher orgs={orgsOf(7)} currentOrgId="o0" />, { route: "/orgs/o0" });

    await user.click(await screen.findByRole("button", { name: "Org 0" }));
    await screen.findByRole("button", { name: "Org 6" });
    expect(screen.queryByRole("searchbox")).not.toBeInTheDocument();
  });

  test("above seven orgs, search filters the list and shows an empty state on no match", async () => {
    const user = userEvent.setup();
    renderWithProviders(<OrgSwitcher orgs={orgsOf(8)} currentOrgId="o0" />, { route: "/orgs/o0" });

    await user.click(await screen.findByRole("button", { name: "Org 0" }));
    const search = await screen.findByRole("searchbox", { name: "Search organizations" });
    await user.type(search, "Org 7");
    expect(screen.getByRole("button", { name: "Org 7" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Org 6" })).not.toBeInTheDocument();

    await user.clear(search);
    await user.type(search, "zzz");
    expect(screen.getByText('No organizations match "zzz"')).toBeInTheDocument();
  });
});
