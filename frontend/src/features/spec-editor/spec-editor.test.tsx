import { forEachDiagnostic } from "@codemirror/lint";
import { EditorView } from "@codemirror/view";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, expect, test, vi } from "vitest";
import { projectHandlers, run, runDetail } from "@/test/msw/handlers/projects";
import { server } from "@/test/msw/server";
import { renderRoute, stubViewport } from "@/test/renderRoute";
import { renderWithProviders } from "@/test/render";
import { SpecEditor } from "./index";

// Validation runs in a Web Worker. The worker is replaced at this boundary; the lint and editor paths are real.
vi.mock("./validation", () => ({ validateSpec: vi.fn(async () => []) }));

const SPEC = "openapi: 3.0.3\ninfo:\n  title: Billing\n  version: 1.0.0\npaths: {}\n";

const LINT_ON_TITLE = {
  jsonPath: "$.info.title",
  line: 3,
  message: "Title should be descriptive",
  ruleId: "info-title",
  severity: "WARNING",
};

let lintedTexts: string[] = [];

beforeEach(() => {
  stubViewport();
  lintedTexts = [];
  // jsdom has no layout or pointer capture. CodeMirror measures text ranges and toasts capture the pointer, so the
  // layout calls they make return empty results. Real Ranges are kept: user-event selects through them.
  Range.prototype.getClientRects = () => [] as unknown as DOMRectList;
  Range.prototype.getBoundingClientRect = () => new DOMRect();
  HTMLElement.prototype.setPointerCapture ??= () => {};
  HTMLElement.prototype.releasePointerCapture ??= () => {};
  HTMLElement.prototype.hasPointerCapture ??= () => false;
  server.use(
    ...projectHandlers,
    http.get("/api/v1/projects/:id/spec", () => HttpResponse.json({ content: SPEC, format: "yaml", sha: "sha1" })),
    http.post("/api/v1/lint", async ({ request }) => {
      const body = (await request.json()) as { content: string };
      lintedTexts.push(body.content);
      return HttpResponse.json([LINT_ON_TITLE]);
    }),
  );
});

test("clicking a problem moves the cursor to its line", async () => {
  const user = userEvent.setup();
  renderRoute("/projects/p1/editor");
  await waitForEditor();

  expect(await screen.findByText("Title should be descriptive")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Go to line 3" }));

  expect(lineOfCursor()).toBe(3);
});

test("navigating away with unsaved edits asks first", async () => {
  const user = userEvent.setup();
  const router = renderRoute("/projects/p1/editor");
  await waitForEditor();
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
  server.use(
    http.put("/api/v1/projects/:id/spec", async ({ request }) => {
      saved = await request.json();
      return HttpResponse.json({ commitSha: "c2", newSha: "sha2", runId: null, runError: null });
    }),
  );
  renderRoute("/projects/p1/editor");
  await waitForEditor();
  typeAtEnd("\n# edit");

  await user.click(editorContent());
  await user.keyboard("{Control>}s{/Control}");

  await waitFor(() => expect(saved).toEqual({ content: `${SPEC}\n# edit`, generate: false, sha: "sha1" }));
});

test("202 runError shows Retry run action", async () => {
  const user = userEvent.setup();
  const runBodies: unknown[] = [];
  server.use(
    http.put("/api/v1/projects/:id/spec", () =>
      HttpResponse.json(
        {
          commitSha: "c2",
          newSha: "sha2",
          runId: null,
          runError: { code: "SANDBOX_TIMEOUT", detail: "The generation sandbox did not start." },
        },
        { status: 202 },
      ),
    ),
    http.post("/api/v1/projects/:id/runs", async ({ request }) => {
      runBodies.push(await request.json());
      return HttpResponse.json(runDetail(run("r9", "QUEUED", "MANUAL", 0, null)), { status: 201 });
    }),
  );
  renderRoute("/projects/p1/editor");
  await waitForEditor();
  typeAtEnd("\n# edit");

  await user.click(screen.getByRole("button", { name: "Save & generate" }));
  expect(await screen.findByText("The generation sandbox did not start.")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Retry run" }));
  await waitFor(() => expect(runBodies).toEqual([{ dryRun: false }]));
});

test("lint markers come from POST /lint", async () => {
  renderRoute("/projects/p1/editor");
  await waitForEditor();

  await waitFor(() => expect(lintedTexts).toContain(SPEC));
  await waitFor(() => {
    const markers: { message: string; line: number }[] = [];
    forEachDiagnostic(editorView().state, (diagnostic, from) => {
      markers.push({ message: diagnostic.message, line: editorView().state.doc.lineAt(from).number });
    });
    expect(markers).toEqual([{ message: "Title should be descriptive", line: 3 }]);
  });
});

test("dry run is blocked while edits are unsaved", async () => {
  renderRoute("/projects/p1/editor");
  await waitForEditor();
  const dryRun = screen.getByRole("button", { name: "Dry run" });
  expect(dryRun).not.toHaveAttribute("aria-disabled");

  typeAtEnd("\n# edit");

  expect(await screen.findByRole("button", { name: "Dry run" })).toHaveAttribute("aria-disabled", "true");
});

test("a 409 is handed to onConflict", async () => {
  const user = userEvent.setup();
  const onConflict = vi.fn();
  server.use(
    http.put("/api/v1/projects/:id/spec", () =>
      HttpResponse.json({ code: "SPEC_CONFLICT", title: "Conflict", status: 409 }, { status: 409 }),
    ),
  );
  renderWithProviders(<SpecEditor projectId="p1" onConflict={onConflict} />, { route: "/projects/p1/editor" });
  await waitForEditor();
  typeAtEnd("\n# edit");

  await user.click(screen.getByRole("button", { name: "Save" }));

  await waitFor(() => expect(onConflict).toHaveBeenCalledTimes(1));
  expect(onConflict).toHaveBeenCalledWith(expect.objectContaining({ code: "SPEC_CONFLICT", status: 409 }));
});

test("a spec that fails to load shows an error with retry", async () => {
  server.use(
    http.get("/api/v1/projects/:id/spec", () =>
      HttpResponse.json({ code: "SPEC_NOT_FOUND", title: "Not found", status: 404 }, { status: 404 }),
    ),
  );
  renderRoute("/projects/p1/editor");

  expect(await screen.findByRole("alert")).toHaveTextContent("SPEC_NOT_FOUND");
  expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
});

/** Waits until the lazily loaded CodeMirror editor is mounted. */
async function waitForEditor() {
  await waitFor(() => expect(document.querySelector(".cm-content")).not.toBeNull());
}

function editorContent(): HTMLElement {
  const content = document.querySelector<HTMLElement>(".cm-content");
  if (!content) throw new Error("editor not mounted");
  return content;
}

function editorView(): EditorView {
  const dom = document.querySelector<HTMLElement>(".cm-editor");
  const view = dom && EditorView.findFromDOM(dom);
  if (!view) throw new Error("editor view not found");
  return view;
}

/** Edits the document through the view's own dispatch, the same path as typing. */
function typeAtEnd(text: string) {
  const view = editorView();
  act(() => view.dispatch({ changes: { from: view.state.doc.length, insert: text } }));
}

function lineOfCursor(): number {
  const view = editorView();
  return view.state.doc.lineAt(view.state.selection.main.head).number;
}
