# WEB-01 · API & auth layer

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The typed API client, query keys, error mapping, session and permissions every page uses.

**Lane:** WEB · **Depends on:** [T04](T04-frontend-scaffold.md), [CON-01](CON-01-contract-me-orgs.md) · **Size budget:** ~550 changed lines (hard limit 1000) · **Branch:** `ticket/web-01`

**Spec:** ov §4.5 (lib/api, lib/auth, errors, permissions)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/lib/api/client.ts`, `queryKeys.ts`, `appError.ts`, `errorMessages.ts`, `frontend/src/lib/auth/session.ts` (`meQuery`, `useMe`), `permissions.ts` (`usePermission`, `Can`), `frontend/src/test/msw/handlers/me.ts`, `frontend/src/test/render.tsx`
- Test: `frontend/src/lib/api/client.test.ts`, `frontend/src/lib/auth/permissions.test.tsx`

## Interfaces

- **Produces:**
```ts
export const api = createClient<paths>({ baseUrl: "/api/v1", credentials: "include" });  // + middleware: X-XSRF-TOKEN from cookie on non-GET; 401 → redirect
export class AppError extends Error { code: ErrorCode; status: number; detail?: string; fieldErrors?: Record<string, string>; extra: Record<string, unknown> }
export function toAppError(res: Response, body: unknown): AppError;
export const errorMessages: Record<ErrorCode, string>;   // plain language, e.g. SPEC_CONFLICT: "Someone else changed this file. Reload to see their version."
export const qk = { me: ["me"], orgs: { all: ["orgs"], one: (id) => ["orgs", id], members: (id) => ["orgs", id, "members"] },
  projects: { list: (orgId, q) => [...], one: (id) => [...], config: (id) => [...], spec: (id) => [...] },
  runs: { list: (projectId, filters) => [...], one: (id) => [...], logs: (id) => [...], files: (id) => [...] }, versions: (projectId) => [...] };
export function usePermission(p: Permission, orgId: string): boolean;   // mirrors backend RolePermissions table (copied as a const map; test checks it)
export function Can(props: { permission: Permission; orgId: string; children: ReactNode; fallback?: ReactNode }): ReactNode;
export function renderWithProviders(ui, { route?, me? }): RenderResult;   // test helper
```

## Tasks

### Task 1: API & auth layer

- [ ] **Step 1: Write the failing test**

```tsx
test("401 redirects to /login with next", async () => {
  server.use(http.get("/api/v1/me", () => new HttpResponse(null, { status: 401 })));
  window.history.pushState({}, "", "/projects/p1/runs?status=FAILED");
  await expect(api.GET("/me")).rejects.toThrow();
  expect(window.location.href).toContain("/login?next=%2Fprojects%2Fp1%2Fruns%3Fstatus%3DFAILED");
});
test("problem details become AppError with plain message", async () => { /* 409 {code: SPEC_CONFLICT} → err.code === "SPEC_CONFLICT", errorMessages[err.code] used */ });
test("CSRF header sent on POST", async () => { });
test.each([["ADMIN","RUN_APPROVE",true],["EDITOR","RUN_APPROVE",false],["EDITOR","SPEC_EDIT",true],["VIEWER","RUN_TRIGGER",false]])("%s %s → %s", (role, perm, ok) => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/lib`

- [ ] **Step 3: Implement** — as listed; every `ErrorCode` must have a message (type-checked by `Record<ErrorCode, string>`).

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/lib`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): api & auth layer"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
