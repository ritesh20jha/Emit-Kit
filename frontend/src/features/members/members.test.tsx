import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, test } from "vitest";
import { OrgSettings } from "@/features/orgs";
import type { components } from "@/lib/api/generated/schema";
import { useMe } from "@/lib/auth/session";
import { defaultMe } from "@/test/msw/handlers/me";
import { server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { ThemeProvider, Toaster } from "@/ui";
import { MembersPage } from "./MembersPage";

type Member = components["schemas"]["MemberResponse"];
type Me = components["schemas"]["MeResponse"];
type OrgDetail = components["schemas"]["OrgDetail"];
type Role = components["schemas"]["Role"];

const ORG = "org1";
/** defaultMe is an ADMIN of org1. */
const ADMIN_ME: Me = defaultMe;
const VIEWER_ME: Me = {
  ...defaultMe,
  orgs: [{ orgId: ORG, name: "Acme", slug: "acme", isPersonal: false, role: "VIEWER" }],
};

const MEMBERS: Member[] = [
  { userId: "u1", login: "octocat", name: "Octo Cat", avatarUrl: "https://example.test/octo.png", role: "ADMIN", roleSource: "SYNC" },
  { userId: "u2", login: "hubot", name: null, avatarUrl: "https://example.test/hubot.png", role: "VIEWER", roleSource: "MANUAL" },
];

const listMembers = (rows: Member[]) => http.get("/api/v1/orgs/:orgId/members", () => HttpResponse.json(rows));

function orgDetail(memberCount: number): OrgDetail {
  return { id: ORG, name: "Acme", slug: "acme", isPersonal: false, role: "ADMIN", memberCount, vcsOrgId: "12345" };
}

function renderMembers(me: Me = ADMIN_ME) {
  return renderWithProviders(
    <ThemeProvider>
      <Toaster />
      <MembersPage orgId={ORG} />
    </ThemeProvider>,
    { me },
  );
}

/** Observes /me while the page is mounted, so an invalidation of the session refetches it, as in the app. */
function MeProbe() {
  useMe();
  return null;
}

/** Opens one row's role select and picks an option, as a pointer user does. */
async function pickRole(user: ReturnType<typeof userEvent.setup>, login: string, role: string) {
  await user.click(await screen.findByRole("combobox", { name: `Role for ${login}` }));
  await user.click(await screen.findByRole("option", { name: role }));
}

beforeEach(() => {
  // jsdom has no matchMedia. A 1280 px viewport shows the table rather than the card list.
  window.matchMedia = ((query: string) => ({
    matches: /min-width:\s*768px/.test(query),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  })) as unknown as typeof window.matchMedia;
  // Radix Select measures and captures pointers, which jsdom does not implement.
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
  Element.prototype.hasPointerCapture = () => false;
  Element.prototype.releasePointerCapture = () => {};
  Element.prototype.scrollIntoView = () => {};
});

describe("members", () => {
  test("admin changes a role inline, and the new role shows before the server answers", async () => {
    const user = userEvent.setup();
    // A stateful fake: the PATCH changes the row the list returns, so a refetch after the change agrees with it.
    const rows: Member[] = MEMBERS.map((member) => ({ ...member, roleSource: "SYNC" }));
    // The PATCH waits on this gate, so the screen can be read while the change is still in flight.
    let releasePatch: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      releasePatch = resolve;
    });
    let patchBody: unknown;
    server.use(
      http.get("/api/v1/orgs/:orgId/members", () => HttpResponse.json(rows)),
      http.patch("/api/v1/orgs/:orgId/members/:userId", async ({ request, params }) => {
        patchBody = await request.json();
        await gate;
        const row = rows.find((member) => member.userId === params.userId);
        Object.assign(row ?? {}, { role: "EDITOR", roleSource: "MANUAL" });
        return HttpResponse.json(row);
      }),
    );
    renderMembers();

    await pickRole(user, "hubot", "EDITOR");

    // Optimistic: the select shows EDITOR while the PATCH is pending, and the server's source has not arrived.
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Role for hubot" })).toHaveTextContent("EDITOR"));
    await waitFor(() => expect(patchBody).toEqual({ role: "EDITOR" }));
    expect(screen.queryByText("Set manually")).not.toBeInTheDocument();

    // Settled: the refetch carries the server's source, and the role stays EDITOR.
    releasePatch();
    expect(await screen.findByText("Set manually")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Role for hubot" })).toHaveTextContent("EDITOR");
  });

  test("last-admin error rolls back and toasts the message", async () => {
    const user = userEvent.setup();
    let listReads = 0;
    server.use(
      http.get("/api/v1/orgs/:orgId/members", () => {
        listReads += 1;
        // The page's first read succeeds. Any refetch after the failure hangs, so the select can only show
        // ADMIN if the rollback restored the previous list.
        return listReads === 1 ? HttpResponse.json(MEMBERS) : new Promise(() => {});
      }),
      http.patch("/api/v1/orgs/:orgId/members/:userId", () =>
        HttpResponse.json(
          {
            type: "about:blank",
            title: "Bad Request",
            status: 400,
            code: "VALIDATION_FAILED",
            detail: "An organization needs at least one admin",
          },
          { status: 400 },
        ),
      ),
    );
    renderMembers();

    await pickRole(user, "octocat", "VIEWER");

    expect(await screen.findByText("An organization needs at least one admin")).toBeInTheDocument();
    expect(screen.getByText("VALIDATION_FAILED")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Role for octocat" })).toHaveTextContent("ADMIN"));
    await waitFor(() => expect(listReads).toBe(2));
  });

  test("an admin who demotes themselves loses the role controls", async () => {
    const user = userEvent.setup();
    // A second admin keeps the org valid, so the server accepts the self-demotion.
    // Copied rows: the PATCH below mutates them, and MEMBERS is shared with the other tests.
    const rows: Member[] = [
      ...MEMBERS.map((member) => ({ ...member })),
      { userId: "u3", login: "monalisa", name: null, avatarUrl: "https://example.test/ml.png", role: "ADMIN", roleSource: "MANUAL" },
    ];
    // A stateful fake for /me: the PATCH on the signed-in user changes the role /me reports, as the server does.
    let me: Me = ADMIN_ME;
    server.use(
      http.get("/api/v1/me", () => HttpResponse.json(me)),
      http.get("/api/v1/orgs/:orgId/members", () => HttpResponse.json(rows)),
      http.patch("/api/v1/orgs/:orgId/members/:userId", async ({ request, params }) => {
        const { role } = (await request.json()) as { role: Role };
        const row = rows.find((member) => member.userId === params.userId);
        Object.assign(row ?? {}, { role, roleSource: "MANUAL" });
        if (params.userId === ADMIN_ME.id) {
          me = { ...me, orgs: me.orgs.map((org) => (org.orgId === ORG ? { ...org, role } : org)) };
        }
        return HttpResponse.json(row);
      }),
    );
    renderMembers();

    await pickRole(user, "octocat", "VIEWER");

    // The session refetch flips the page to the read-only view: no selects, and the remaining admin is named.
    expect(await screen.findByText("You need admin access to change member roles. Ask an org admin.")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("Org admins: monalisa")).toBeInTheDocument());
  });

  test("non-admin sees roles as text, no selects", async () => {
    server.use(listMembers(MEMBERS));
    renderMembers(VIEWER_ME);

    expect(await screen.findByText("octocat")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.getByText("ADMIN")).toBeInTheDocument();
    expect(screen.getByText("GitHub sync")).toBeInTheDocument();
    expect(screen.getByText("Set manually")).toBeInTheDocument();
  });

  test("four states render", async () => {
    // Loading: a busy, named skeleton region while the list is in flight.
    server.use(http.get("/api/v1/orgs/:orgId/members", () => new Promise(() => {})));
    renderMembers();
    expect(await screen.findByRole("status", { name: "Loading members" })).toHaveAttribute("aria-busy", "true");
    cleanup();

    // Empty: says what the view is for.
    server.use(listMembers([]));
    renderMembers();
    expect(await screen.findByText("No members yet")).toBeInTheDocument();
    cleanup();

    // Error: plain-language message, the code in mono, and a Retry that reloads.
    const user = userEvent.setup();
    server.use(
      http.get("/api/v1/orgs/:orgId/members", () =>
        HttpResponse.json({ type: "about:blank", title: "Internal", status: 500, code: "INTERNAL" }, { status: 500 }),
      ),
    );
    renderMembers();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Something went wrong on our side");
    expect(alert).toHaveTextContent("INTERNAL");
    server.use(listMembers(MEMBERS));
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("octocat")).toBeInTheDocument();
    cleanup();

    // No permission: a viewer is told what is blocked and who can unblock it.
    server.use(listMembers(MEMBERS));
    renderMembers(VIEWER_ME);
    expect(await screen.findByText("You need admin access to change member roles. Ask an org admin.")).toBeInTheDocument();
    expect(screen.getByText("Org admins: octocat")).toBeInTheDocument();
  });
});

describe("org settings", () => {
  test("re-sync shows toast and refetches", async () => {
    const user = userEvent.setup();
    let orgReads = 0;
    let meReads = 0;
    let syncs = 0;
    server.use(
      http.get("/api/v1/orgs/:orgId", () => {
        orgReads += 1;
        return HttpResponse.json(orgDetail(orgReads === 1 ? 2 : 3));
      }),
      http.get("/api/v1/me", () => {
        meReads += 1;
        return HttpResponse.json(ADMIN_ME);
      }),
      http.post("/api/v1/me/sync-orgs", () => {
        syncs += 1;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithProviders(
      <ThemeProvider>
        <Toaster />
        <MeProbe />
        <OrgSettings orgId={ORG} />
      </ThemeProvider>,
      { me: ADMIN_ME },
    );
    const memberCount = () => screen.getByText("Members").nextElementSibling;
    await waitFor(() => expect(memberCount()).toHaveTextContent("2"));
    // The seeded session is fresh, so nothing has read /me yet. Any read below comes from the re-sync.
    expect(meReads).toBe(0);

    await user.click(screen.getByRole("button", { name: "Re-sync from GitHub" }));

    expect(await screen.findByText("Synced")).toBeInTheDocument();
    expect(syncs).toBe(1);
    await waitFor(() => expect(memberCount()).toHaveTextContent("3"));
    expect(orgReads).toBe(2);
    await waitFor(() => expect(meReads).toBe(1));
  });

  test("loading and error states render", async () => {
    server.use(http.get("/api/v1/orgs/:orgId", () => new Promise(() => {})));
    renderWithProviders(<ThemeProvider><OrgSettings orgId={ORG} /></ThemeProvider>, { me: ADMIN_ME });
    expect(await screen.findByRole("status", { name: "Loading settings" })).toHaveAttribute("aria-busy", "true");
    cleanup();

    server.use(
      http.get("/api/v1/orgs/:orgId", () =>
        HttpResponse.json({ type: "about:blank", title: "Internal", status: 500, code: "INTERNAL" }, { status: 500 }),
      ),
    );
    renderWithProviders(<ThemeProvider><OrgSettings orgId={ORG} /></ThemeProvider>, { me: ADMIN_ME });
    expect(await screen.findByRole("alert")).toHaveTextContent("INTERNAL");
  });
});
