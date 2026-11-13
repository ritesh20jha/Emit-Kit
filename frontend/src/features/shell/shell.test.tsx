import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider, useLocation } from "@tanstack/react-router";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { createAppRouter } from "@/app/router";
import { resetLoginRedirectForTests } from "@/lib/api/client";
import { qk } from "@/lib/api/queryKeys";
import type { Me } from "@/lib/auth/session";
import { defaultMe } from "@/test/msw/handlers/me";
import { server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { AppShell } from "./AppShell";
import { OrgSwitcher } from "./OrgSwitcher";
import { QuotaIndicator } from "./QuotaIndicator";
import { Sidebar } from "./Sidebar";
import { LAST_ORG_KEY } from "./lastOrg";

const personalMe: Me = {
  ...defaultMe,
  orgs: [
    { orgId: "org1", name: "Acme", slug: "acme", isPersonal: false, role: "ADMIN" },
    { orgId: "org2", name: "Globex", slug: "globex", isPersonal: true, role: "ADMIN" },
  ],
};

const RESET_AT = "2026-11-13T15:30:00Z";

/** jsdom cannot navigate, so window.location becomes a settable stand-in (as in client.test.ts). */
function stubLocationAt(path: string) {
  window.history.pushState({}, "", path);
  const location = { pathname: window.location.pathname, search: window.location.search, href: window.location.href };
  vi.stubGlobal("location", location);
  return location;
}

/** jsdom has no matchMedia. This fake answers max-width queries for a viewport of `width` pixels. */
function stubViewport(width: number) {
  window.matchMedia = ((query: string) => ({
    matches: width <= Number(/max-width:\s*(\d+)px/.exec(query)?.[1] ?? Infinity),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  })) as unknown as typeof window.matchMedia;
}

/** Mounts the whole app router at `path`. `/me` answers with `me`, or 401 when `me` is null (signed out). */
function renderApp(path: string, me: Me | null = personalMe) {
  server.use(
    http.get("/api/v1/me", () =>
      me === null
        ? HttpResponse.json(
            { type: "about:blank", title: "Unauthenticated", status: 401, code: "UNAUTHENTICATED" },
            { status: 401 },
          )
        : HttpResponse.json(me),
    ),
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }));
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

/** Shows the current pathname so a test can read where navigation went. */
function Where() {
  const { pathname } = useLocation();
  return <output data-testid="where">{pathname}</output>;
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
  renderWithProviders(
    <>
      <OrgSwitcher orgs={personalMe.orgs} currentOrgId="org1" />
      <Where />
    </>,
    { route: "/orgs/org1", me: personalMe },
  );

  await user.click(await screen.findByRole("button", { name: "Acme" }));
  await user.click(await screen.findByRole("button", { name: "Globex" }));

  await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/orgs/org2"));
  expect(localStorage.getItem(LAST_ORG_KEY)).toBe("org2");
});

describe("quota indicator", () => {
  async function renderQuota(remaining: number) {
    server.use(
      http.get("/api/v1/me/vcs-quota", () => HttpResponse.json({ limit: 1000, remaining, resetAt: RESET_AT })),
    );
    const { queryClient } = renderWithProviders(<QuotaIndicator />);
    await waitFor(() => expect(queryClient.getQueryState(qk.vcsQuota)?.status).toBe("success"));
  }

  test("quota indicator hidden at 50%, shown at 10%", async () => {
    await renderQuota(500);
    expect(screen.queryByText(/GitHub quota low/)).not.toBeInTheDocument();
    cleanup();

    await renderQuota(100);
    const resetAt = new Date(RESET_AT);
    const pad = (n: number) => String(n).padStart(2, "0");
    const time = `${pad(resetAt.getHours())}:${pad(resetAt.getMinutes())}`;
    expect(await screen.findByText(`GitHub quota low: resets at ${time}`)).toBeInTheDocument();
  });
});

test("mobile drawer at 360px opens and traps focus", async () => {
  stubViewport(360);
  const user = userEvent.setup();
  renderWithProviders(<AppShell><p>Page content</p></AppShell>, { route: "/orgs/org1", me: personalMe });

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
  renderWithProviders(
    <AppShell>
      <Where />
    </AppShell>,
    { route: "/orgs/org1", me: personalMe },
  );

  await user.click(await screen.findByRole("button", { name: "Open navigation" }));
  const drawer = await screen.findByRole("dialog", { name: "Navigation" });
  await user.click(within(drawer).getByRole("button", { name: "Acme" }));
  await user.click(await screen.findByRole("button", { name: "Globex" }));

  await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/orgs/org2"));
  expect(localStorage.getItem(LAST_ORG_KEY)).toBe("org2");
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Navigation" })).not.toBeInTheDocument());
});

test("org section links are current only on their own page", async () => {
  renderWithProviders(<Sidebar orgs={personalMe.orgs} currentOrgId="org1" />, {
    route: "/orgs/org1/members",
    me: personalMe,
  });

  expect(await screen.findByRole("link", { name: "Members" })).toHaveAttribute("aria-current", "page");
  expect(screen.getByRole("link", { name: "Projects" })).not.toHaveAttribute("aria-current");
});

test.each(["/\\evil.com", "//evil.com"])("login returns only to same-site paths, not %s", async (next) => {
  const path = `/login?next=${encodeURIComponent(next)}`;
  const location = stubLocationAt(path);
  renderApp(path);

  await userEvent.click(await screen.findByRole("button", { name: "Sign in with GitHub" }));
  expect(location.href).toBe("/oauth2/authorization/github?next=%2F");
});
