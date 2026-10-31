# WEB-12 · Live logs (useEventStream + LogView)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A reconnecting SSE hook and a virtualized log viewer that handles 100k lines.

**Lane:** WEB · **Depends on:** [WEB-01](WEB-01-api-auth-layer.md), [CON-03](CON-03-contract-runs.md) · **Size budget:** ~700 changed lines (hard limit 1000) · **Branch:** `ticket/web-12`

**Spec:** v1 §10.6 logs; ov §4.5 live data, §4.6 performance

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/lib/sse/useEventStream.ts`
- Create: `frontend/src/features/run-logs/` — `LogView.tsx`, `LogToolbar.tsx`, `useRunLogs.ts`, `index.ts`
- Test: `frontend/src/lib/sse/useEventStream.test.ts`, `frontend/src/features/run-logs/logs.test.tsx`

## Interfaces

- **Produces:**
```ts
function useEventStream<T extends { seq: number }>(url: string | null, opts: { onDone?: () => void }):
  { items: T[]; status: "connecting" | "open" | "reconnecting" | "done" | "error" };
```
- Wraps `EventSource` (with credentials); on error, reconnects with backoff 1 s → 10 s and sends the last seq as `Last-Event-ID` (native EventSource does this when the server sets `id`); drops items with `seq <= lastSeq`; `done` event closes and sets `status: "done"`.
- `LogView`: `@tanstack/react-virtual`; level colors via tokens + level text; filters `step`, `level`, `q` (search highlights) as search params; auto-scroll pauses when the user scrolls up → "Jump to latest" button; copy line; download all as `.txt`; "Reconnecting…" banner; a polite live region announces only step changes (not every line).

## Tasks

### Task 1: Live logs (useEventStream + LogView)

- [ ] **Step 1: Write the failing test**

```tsx
test("reconnect de-duplicates by seq", async () => { /* mock EventSource: 1,2,3, error, reconnect replays 2,3,4 → items seqs [1,2,3,4] */ });
test("shows Reconnecting banner while reconnecting", async () => { });
test("done closes the stream", async () => { });
test("100k lines render under budget", async () => { const t = performance.now(); render(<LogView lines={make(100_000)} />); expect(performance.now() - t).toBeLessThan(500); expect(screen.getAllByRole("row").length).toBeLessThan(200); });
test("scrolling up pauses auto-scroll and shows Jump to latest", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/lib/sse src/features/run-logs`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/lib/sse src/features/run-logs`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): live logs (useeventstream + logview)"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-12`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
