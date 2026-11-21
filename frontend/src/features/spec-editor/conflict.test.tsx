import { EditorView } from "@codemirror/view";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { projectHandlers } from "@/test/msw/handlers/projects";
import { server } from "@/test/msw/server";
import { renderRoute, stubViewport } from "@/test/renderRoute";

// Validation runs in a Web Worker, so it is replaced at that boundary. Lint and the editor run for real.
vi.mock("./validation", () => ({ validateSpec: vi.fn(async () => []) }));

const SPEC = "openapi: 3.0.3\ninfo:\n  title: Billing\n  version: 1.0.0\npaths: {}\n";
const THEIRS = "openapi: 3.0.3\ninfo:\n  title: Billing\n  version: 2.0.0\npaths: {}\n";
const CONFLICT = { code: "SPEC_CONFLICT", status: 409, currentSha: "theirs" };
const SERVER_ERROR = { code: "INTERNAL", status: 500, title: "Server error" };

let putBodies: { content: string; sha: string }[] = [];

beforeEach(() => {
  stubViewport();
  // jsdom has no layout or pointer capture, which CodeMirror and toasts need. Real Ranges stay: user-event selects through them.
  Range.prototype.getClientRects = () => [] as unknown as DOMRectList;
  Range.prototype.getBoundingClientRect = () => new DOMRect();
  HTMLElement.prototype.setPointerCapture ??= () => {};
  HTMLElement.prototype.releasePointerCapture ??= () => {};
  HTMLElement.prototype.hasPointerCapture ??= () => false;
  putBodies = [];
  server.events.on("request:start", async ({ request }) => {
    if (request.method === "PUT") putBodies.push(await request.clone().json());
  });
  // The first read is the spec the editor opens with. Every later read is the server's newer version.
  let gets = 0;
  server.use(
    ...projectHandlers,
    http.get("/api/v1/projects/:id/spec", () => {
      gets += 1;
      return HttpResponse.json(gets === 1 ? { content: SPEC, format: "yaml", sha: "sha1" } : { content: THEIRS, format: "yaml", sha: "theirs" });
    }),
    http.post("/api/v1/lint", () => HttpResponse.json([])),
  );
});

afterEach(() => server.events.removeAllListeners());

test("conflictDialog_offersCompare", async () => {
  server.use(http.put("/api/v1/projects/p1/spec", () => HttpResponse.json(CONFLICT, { status: 409 })));
  await saveWithEdits("mine");
  expect(await screen.findByRole("dialog", { name: /someone else changed this file/i })).toBeVisible();
  await userEvent.click(screen.getByRole("button", { name: "Compare" }));
  expect(screen.getByTestId("merge-view")).toBeVisible();
  await waitFor(() => expect(mergePanes()[0].state.doc.toString()).toBe(THEIRS));
  await waitFor(() => expect(screen.getByRole("button", { name: "Save merged" })).toBeEnabled());
  await userEvent.click(screen.getByRole("button", { name: "Save merged" }));
  await waitFor(() => expect(lastPutBody().sha).toBe("theirs"));
});

test("the merge view shows the server's spec on the left and the user's text on the right", async () => {
  server.use(http.put("/api/v1/projects/p1/spec", () => HttpResponse.json(CONFLICT, { status: 409 })));
  await openMerge();

  const [theirs, mine] = mergePanes();
  expect(theirs.state.doc.toString()).toBe(THEIRS);
  expect(mine.state.doc.toString()).toBe(`${SPEC}\n# mine`);
  expect(theirs.state.readOnly).toBe(true);
});

test("an edit in the merge view's right pane is what Save merged sends", async () => {
  server.use(http.put("/api/v1/projects/p1/spec", () => HttpResponse.json(CONFLICT, { status: 409 })));
  await openMerge();

  typeAtEnd(mergePanes()[1], "\n# merged");
  await userEvent.click(screen.getByRole("button", { name: "Save merged" }));

  await waitFor(() => expect(lastPutBody()).toMatchObject({ content: `${SPEC}\n# mine\n# merged`, sha: "theirs" }));
});

test("a second conflict on Save merged keeps the dialog open and the user's merge", async () => {
  let puts = 0;
  server.use(
    http.put("/api/v1/projects/p1/spec", () => {
      puts += 1;
      return HttpResponse.json({ ...CONFLICT, currentSha: puts === 1 ? "theirs" : "theirs-2" }, { status: 409 });
    }),
  );
  await openMerge();
  typeAtEnd(mergePanes()[1], "\n# merged");

  await userEvent.click(screen.getByRole("button", { name: "Save merged" }));

  await waitFor(() => expect(lastPutBody().sha).toBe("theirs"));
  await waitFor(() => expect(screen.getByRole("button", { name: "Save merged" })).toBeEnabled());
  const dialog = screen.getByRole("dialog", { name: /someone else changed this file/i });
  expect(mergePanes()[1].state.doc.toString()).toBe(`${SPEC}\n# mine\n# merged`);

  await userEvent.click(within(dialog).getByRole("button", { name: "Save merged" }));
  await waitFor(() => expect(lastPutBody().sha).toBe("theirs-2"));
});

test("when the server's spec cannot be read, Save merged stays disabled", async () => {
  let gets = 0;
  server.use(
    http.get("/api/v1/projects/:id/spec", () => {
      gets += 1;
      return gets === 1 ? HttpResponse.json({ content: SPEC, format: "yaml", sha: "sha1" }) : HttpResponse.json(SERVER_ERROR, { status: 500 });
    }),
    http.put("/api/v1/projects/p1/spec", () => HttpResponse.json(CONFLICT, { status: 409 })),
  );
  await saveWithEdits("mine");
  const dialog = await screen.findByRole("dialog", { name: /someone else changed this file/i });
  await userEvent.click(within(dialog).getByRole("button", { name: "Compare" }));

  expect(await within(dialog).findByRole("alert")).toBeInTheDocument();
  expect(document.querySelector('[data-testid="merge-view"] .cm-editor')).toBeNull();
  expect(screen.getByRole("button", { name: "Save merged" })).toBeDisabled();
});

test("Reload discards mine after confirming", async () => {
  const user = userEvent.setup();
  server.use(http.put("/api/v1/projects/p1/spec", () => HttpResponse.json(CONFLICT, { status: 409 })));
  await saveWithEdits("mine");
  const conflict = await screen.findByRole("dialog", { name: /someone else changed this file/i });

  await user.click(within(conflict).getByRole("button", { name: "Reload" }));
  const confirm = await screen.findByRole("dialog", { name: "Discard your edits?" });
  expect(editorView().state.doc.toString()).toContain("# mine");

  await user.click(within(confirm).getByRole("button", { name: "Discard edits" }));
  await waitFor(() => expect(editorView().state.doc.toString()).toBe(THEIRS));
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});

/** Saves an edit into a conflict, then opens Compare and waits until Save merged can be used. */
async function openMerge() {
  await saveWithEdits("mine");
  await screen.findByRole("dialog", { name: /someone else changed this file/i });
  await userEvent.click(screen.getByRole("button", { name: "Compare" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Save merged" })).toBeEnabled());
}

/** Opens the editor, adds `text` to the end of the spec and clicks Save. */
async function saveWithEdits(text: string) {
  await openEditor();
  typeAtEnd(editorView(), `\n# ${text}`);
  await userEvent.click(screen.getByRole("button", { name: "Save" }));
}

/** The body of the most recent PUT, read as the request went out. */
function lastPutBody(): { content: string; sha: string } {
  const body = putBodies.at(-1);
  if (!body) throw new Error("no spec save was sent");
  return body;
}

async function openEditor() {
  renderRoute("/projects/p1/editor");
  await waitFor(() => expect(document.querySelector(".cm-content")).not.toBeNull());
}

function editorView(): EditorView {
  const view = EditorView.findFromDOM(document.querySelector<HTMLElement>(".cm-editor") as HTMLElement);
  if (!view) throw new Error("editor view not found");
  return view;
}

/** The merge view's two panes, left (the server's spec) then right (the user's text). */
function mergePanes(): [EditorView, EditorView] {
  const views = Array.from(document.querySelectorAll<HTMLElement>('[data-testid="merge-view"] .cm-editor'), (dom) => EditorView.findFromDOM(dom));
  const [left, right] = views;
  if (!left || !right) throw new Error("merge panes not found");
  return [left, right];
}

/** Edits through the view's dispatch, the same path as typing. */
function typeAtEnd(view: EditorView, text: string) {
  act(() => view.dispatch({ changes: { from: view.state.doc.length, insert: text } }));
}
