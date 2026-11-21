import { forEachDiagnostic } from "@codemirror/lint";
import { EditorView } from "@codemirror/view";
import { act, renderHook, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, expect, test, vi } from "vitest";
import { projectHandlers, run, runDetail } from "@/test/msw/handlers/projects";
import { server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { renderRoute, stubViewport } from "@/test/renderRoute";
import { SpecEditor } from "./index";
import { useSettledText } from "./problems";
import { validateSpec } from "./validation";

// Validation runs in a Web Worker, so it is replaced at that boundary. Lint and the editor run for real.
vi.mock("./validation", () => ({ validateSpec: vi.fn(async () => []) }));

const SPEC = "openapi: 3.0.3\ninfo:\n  title: Billing\n  version: 1.0.0\npaths: {}\n";
const OTHER = "openapi: 3.0.3\ninfo:\n  title: Payments\n  version: 2.0.0\npaths: {}\n";
const TITLE_WARNING = { jsonPath: "$.info.title", line: 3, message: "Title should be descriptive", ruleId: "info-title", severity: "WARNING" };
const SAVE_DONE = { commitSha: "c2", newSha: "sha2", runId: null, runError: null };

let linted: string[] = [];

beforeEach(() => {
  stubViewport();
  linted = [];
  vi.mocked(validateSpec).mockResolvedValue([]);
  // jsdom has no layout or pointer capture. CodeMirror measures text and toasts capture the pointer, so those calls
  // return empty results. Real Ranges stay: user-event selects through them.
  Range.prototype.getClientRects = () => [] as unknown as DOMRectList;
  Range.prototype.getBoundingClientRect = () => new DOMRect();
  HTMLElement.prototype.setPointerCapture ??= () => {};
  HTMLElement.prototype.releasePointerCapture ??= () => {};
  HTMLElement.prototype.hasPointerCapture ??= () => false;
  server.use(
    ...projectHandlers,
    http.get("/api/v1/projects/:id/spec", ({ params }) =>
      HttpResponse.json(params.id === "p2" ? { content: OTHER, format: "yaml", sha: "sha2" } : { content: SPEC, format: "yaml", sha: "sha1" }),
    ),
    http.post("/api/v1/lint", async ({ request }) => {
      linted.push(((await request.json()) as { content: string }).content);
      return HttpResponse.json([TITLE_WARNING]);
    }),
  );
});

test("clicking a problem moves the cursor to its line", async () => {
  const user = userEvent.setup();
  await openEditor();

  expect(await screen.findByText("Title should be descriptive")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Go to line 3" }));

  expect(lineOfCursor()).toBe(3);
});

test("navigating away with unsaved edits asks first", async () => {
  const user = userEvent.setup();
  const router = renderRoute("/projects/p1/editor");
  await mountedView();
  typeAtEnd("\n# draft");

  await user.click(screen.getByRole("link", { name: "Overview" }));
  const dialog = await screen.findByRole("dialog", { name: "Leave with unsaved changes?" });
  expect(router.state.location.pathname).toBe("/projects/p1/editor");

  await user.click(within(dialog).getByRole("button", { name: "Keep editing" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  expect(router.state.location.pathname).toBe("/projects/p1/editor");

  await user.click(screen.getByRole("link", { name: "Overview" }));
  await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Discard changes" }));
  await waitFor(() => expect(router.state.location.pathname).toBe("/projects/p1"));
});

test("mod+s saves", async () => {
  const user = userEvent.setup();
  let saved: unknown;
  server.use(http.put("/api/v1/projects/:id/spec", async ({ request }) => ((saved = await request.json()), HttpResponse.json(SAVE_DONE))));
  await openEditor();
  typeAtEnd("\n# edit");

  await user.click(contentArea());
  await user.keyboard("{Control>}s{/Control}");

  await waitFor(() => expect(saved).toEqual({ content: `${SPEC}\n# edit`, generate: false, sha: "sha1" }));
});

test("202 runError shows Retry run action", async () => {
  const user = userEvent.setup();
  const runBodies: unknown[] = [];
  const runError = { code: "SANDBOX_TIMEOUT", detail: "The generation sandbox did not start." };
  server.use(
    http.put("/api/v1/projects/:id/spec", () => HttpResponse.json({ ...SAVE_DONE, runError }, { status: 202 })),
    http.post("/api/v1/projects/:id/runs", async ({ request }) => ((runBodies.push(await request.json())), HttpResponse.json(runDetail(run("r9", "QUEUED", "MANUAL", 0, null)), { status: 201 }))),
  );
  await openEditor();
  typeAtEnd("\n# edit");

  await user.click(screen.getByRole("button", { name: "Save & generate" }));
  expect(await screen.findByText("The generation sandbox did not start.")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Retry run" }));
  await waitFor(() => expect(runBodies).toEqual([{ dryRun: false }]));
});

test("lint markers come from POST /lint", async () => {
  await openEditor();

  await waitFor(() => expect(linted).toContain(SPEC));
  await waitFor(() => expect(markers()).toEqual([{ message: "Title should be descriptive", line: 3 }]));
});

test("a rate-limited lint check keeps browser problems and markers, with an error notice", async () => {
  vi.mocked(validateSpec).mockResolvedValue([{ message: "Flow sequence in block collection", path: "", line: 2 }]);
  server.use(
    http.post("/api/v1/lint", () =>
      HttpResponse.json({ code: "RATE_LIMITED", title: "Too many requests", status: 429, detail: "Lint is limited. Wait a minute." }, { status: 429 }),
    ),
  );
  await openEditor();
  const problems = await screen.findByRole("region", { name: "Problems" });

  expect(await within(problems).findByRole("alert")).toHaveTextContent("Lint is limited. Wait a minute.");
  expect(within(problems).getByText("Flow sequence in block collection")).toBeInTheDocument();
  await waitFor(() => expect(markers()).toEqual([{ message: "Flow sequence in block collection", line: 2 }]));
});

test("dry run is blocked while edits are unsaved", async () => {
  await openEditor();
  expect(screen.getByRole("button", { name: "Dry run" })).not.toHaveAttribute("aria-disabled");

  typeAtEnd("\n# edit");

  expect(await screen.findByRole("button", { name: "Dry run" })).toHaveAttribute("aria-disabled", "true");
});

test("a 409 is handed to onConflict", async () => {
  const user = userEvent.setup();
  const onConflict = vi.fn();
  server.use(http.put("/api/v1/projects/:id/spec", () => HttpResponse.json({ code: "SPEC_CONFLICT", title: "Conflict", status: 409 }, { status: 409 })));
  renderWithProviders(<SpecEditor projectId="p1" onConflict={onConflict} />, { route: "/projects/p1/editor" });
  await mountedView();
  typeAtEnd("\n# edit");

  await user.click(screen.getByRole("button", { name: "Save" }));

  await waitFor(() => expect(onConflict).toHaveBeenCalledWith(expect.objectContaining({ code: "SPEC_CONFLICT", status: 409 })));
});

test("a spec that fails to load shows an error with Retry", async () => {
  server.use(http.get("/api/v1/projects/:id/spec", () => HttpResponse.json({ code: "SPEC_NOT_FOUND", title: "Not found", status: 404 }, { status: 404 })));
  renderRoute("/projects/p1/editor");

  expect(await screen.findByRole("alert")).toHaveTextContent("SPEC_NOT_FOUND");
  expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
});

test("Save & generate that starts a run offers View run to that run", async () => {
  const user = userEvent.setup();
  let body: unknown;
  server.use(http.put("/api/v1/projects/:id/spec", async ({ request }) => ((body = await request.json()), HttpResponse.json({ ...SAVE_DONE, runId: "r9" }))));
  const router = renderRoute("/projects/p1/editor");
  await mountedView();
  typeAtEnd("\n# edit");

  await user.click(screen.getByRole("button", { name: "Save & generate" }));

  // Toasts outlive a test, so the action is looked up in the toast that carries this test's message.
  const toast = await toastWith("Saved and generation started");
  expect(body).toEqual({ content: `${SPEC}\n# edit`, generate: true, sha: "sha1" });
  await user.click(within(toast).getByRole("button", { name: "View run" }));
  await waitFor(() => expect(router.state.location.pathname).toBe("/runs/r9"));
});

test("switching to a cached project shows and saves that project's spec", async () => {
  const user = userEvent.setup();
  let saved: unknown;
  server.use(http.put("/api/v1/projects/:id/spec", async ({ params, request }) => ((saved = { project: params.id, body: await request.json() }), HttpResponse.json(SAVE_DONE))));
  // p2 is loaded and left first, so its spec is cached when the editor later moves to it from p1.
  const router = renderRoute("/projects/p2/editor");
  await waitFor(() => expect(editorView().state.doc.toString()).toBe(OTHER));
  await router.navigate({ to: "/projects/$id/editor", params: { id: "p1" } });
  await waitFor(() => expect(editorView().state.doc.toString()).toBe(SPEC));
  await router.navigate({ to: "/projects/$id/editor", params: { id: "p2" } });
  await waitFor(() => expect(editorView().state.doc.toString()).toBe(OTHER));

  typeAtEnd("\n# edit");
  await user.click(contentArea());
  await user.keyboard("{Control>}s{/Control}");

  await waitFor(() => expect(saved).toEqual({ project: "p2", body: { content: `${OTHER}\n# edit`, generate: false, sha: "sha2" } }));
});

test("problems are listed by severity, then by line", async () => {
  vi.mocked(validateSpec).mockResolvedValue([{ message: "Schema finding", path: "/info", line: 2 }]);
  server.use(
    http.post("/api/v1/lint", () =>
      HttpResponse.json([
        { ...TITLE_WARNING, message: "Info note", severity: "INFO", line: 1 },
        { ...TITLE_WARNING, message: "Warning on title", line: 3 },
        { ...TITLE_WARNING, message: "Error on paths", severity: "ERROR", line: 5 },
      ]),
    ),
  );
  renderRoute("/projects/p1/editor");

  const problems = await screen.findByRole("region", { name: "Problems" });
  await waitFor(() => expect(within(problems).getAllByRole("listitem")).toHaveLength(4));
  const messages = within(problems).getAllByRole("listitem").map((item) => item.querySelector("p")?.textContent);
  expect(messages).toEqual(["Schema finding", "Error on paths", "Warning on title", "Info note"]);
});

test("the settled text follows the text only after 500 ms without a change", () => {
  vi.useFakeTimers();
  try {
    const { result, rerender } = renderHook(({ text }) => useSettledText(text), { initialProps: { text: "a" } });
    rerender({ text: "ab" });
    act(() => vi.advanceTimersByTime(400));
    rerender({ text: "abc" });
    act(() => vi.advanceTimersByTime(499));
    expect(result.current).toBe("a");
    act(() => vi.advanceTimersByTime(1));
    expect(result.current).toBe("abc");
  } finally {
    vi.useRealTimers();
  }
});

/** Renders the editor route and waits for the lazily loaded editor to mount. */
async function openEditor(path = "/projects/p1/editor") {
  renderRoute(path);
  await mountedView();
}

/** Waits for the lazily loaded editor to mount and returns its view. */
async function mountedView(): Promise<EditorView> {
  await waitFor(() => expect(document.querySelector(".cm-content")).not.toBeNull());
  return editorView();
}

function editorView(): EditorView {
  const view = EditorView.findFromDOM(document.querySelector<HTMLElement>(".cm-editor") as HTMLElement);
  if (!view) throw new Error("editor view not found");
  return view;
}

function contentArea(): HTMLElement {
  return document.querySelector<HTMLElement>(".cm-content") as HTMLElement;
}

/** Edits through the view's dispatch, the same path as typing. */
function typeAtEnd(text: string) {
  const view = editorView();
  act(() => view.dispatch({ changes: { from: view.state.doc.length, insert: text } }));
}

function lineOfCursor(): number {
  const view = editorView();
  return view.state.doc.lineAt(view.state.selection.main.head).number;
}

/** The inline markers the editor currently shows, with the line each one sits on. */
function markers(): { message: string; line: number }[] {
  const found: { message: string; line: number }[] = [];
  const view = editorView();
  forEachDiagnostic(view.state, (diagnostic, from) => found.push({ message: diagnostic.message, line: view.state.doc.lineAt(from).number }));
  return found;
}

async function toastWith(text: string): Promise<HTMLElement> {
  return (await screen.findByText(text)).closest<HTMLElement>("[data-sonner-toast]") as HTMLElement;
}
