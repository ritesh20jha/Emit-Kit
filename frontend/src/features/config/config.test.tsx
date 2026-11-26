import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeAll, beforeEach, expect, test } from "vitest";
import { config, projectHandlers } from "@/test/msw/handlers/projects";
import { server } from "@/test/msw/server";
import { renderRoute, stubViewport } from "@/test/renderRoute";

// Radix Checkbox measures itself with ResizeObserver, which jsdom does not implement.
beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
});

beforeEach(() => {
  stubViewport();
  server.use(...projectHandlers);
});

/** Records every PUT to the config endpoint. The reply is the saved config, or the given problem. */
function recordSaves(reply?: () => Response) {
  const bodies: unknown[] = [];
  server.use(
    http.put("/api/v1/projects/:id/config", async ({ request }) => {
      bodies.push(await request.json());
      return reply ? reply() : HttpResponse.json({ ...config, version: config.version + 1 });
    }),
  );
  return bodies;
}

test("language fields appear only with SDK", async () => {
  const user = userEvent.setup();
  renderRoute("/projects/p1/config");

  const sdk = await screen.findByRole("checkbox", { name: "SDK" });
  expect(sdk).toBeChecked();
  expect(screen.getByRole("checkbox", { name: "TypeScript" })).toBeInTheDocument();

  await user.click(sdk);
  expect(screen.queryByRole("checkbox", { name: "TypeScript" })).not.toBeInTheDocument();
  expect(screen.queryByRole("checkbox", { name: "Python" })).not.toBeInTheDocument();

  await user.click(sdk);
  expect(screen.getByRole("checkbox", { name: "Python" })).toBeInTheDocument();
});

test("python selected requires PyPI name", async () => {
  const user = userEvent.setup();
  const bodies = recordSaves();
  renderRoute("/projects/p1/config");

  await user.click(await screen.findByRole("checkbox", { name: "Python" }));
  const pypi = screen.getByLabelText("PyPI name");
  await user.click(screen.getByRole("button", { name: "Save" }));

  expect(await screen.findByText("Enter the PyPI name for Python.")).toBeInTheDocument();
  expect(pypi).toHaveAttribute("aria-invalid", "true");
  expect(bodies).toHaveLength(0);
});

test("AI key shows Key set and Replace; never pre-filled", async () => {
  const user = userEvent.setup();
  renderRoute("/projects/p1/config");

  expect(await screen.findByText("Key set")).toBeInTheDocument();
  expect(screen.queryByLabelText("AI key")).not.toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Replace" }));
  expect(screen.getByLabelText("AI key")).toHaveValue("");
});

test("save disabled until dirty; leaving asks first", async () => {
  const user = userEvent.setup();
  const router = renderRoute("/projects/p1/config");
  const save = await screen.findByRole("button", { name: "Save" });
  const tabs = screen.getByRole("navigation", { name: "Sections" });
  expect(save).toBeDisabled();

  const dir = screen.getByLabelText("Output directory");
  await user.clear(dir);
  await user.type(dir, "gen/");
  expect(save).toBeEnabled();

  await user.click(within(tabs).getByRole("link", { name: "Settings" }));
  const dialog = await screen.findByRole("dialog", { name: "Discard unsaved changes?" });
  await user.click(within(dialog).getByRole("button", { name: "Keep editing" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  expect(router.state.location.pathname).toBe("/projects/p1/config");

  await user.click(within(tabs).getByRole("link", { name: "Settings" }));
  await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Discard changes" }));
  await waitFor(() => expect(router.state.location.pathname).toBe("/projects/p1/settings"));
});

test("server field errors shown inline", async () => {
  const user = userEvent.setup();
  recordSaves(() =>
    HttpResponse.json(
      {
        type: "about:blank",
        title: "Bad Request",
        status: 400,
        code: "VALIDATION_FAILED",
        detail: "Request validation failed.",
        errors: [{ field: "aiModel", message: "Unknown model: gemini-x." }],
      },
      { status: 400 },
    ),
  );
  renderRoute("/projects/p1/config");

  const model = await screen.findByLabelText("Model");
  await user.clear(model);
  await user.type(model, "gemini-x");
  await user.click(screen.getByRole("button", { name: "Save" }));

  expect(await screen.findByText("Unknown model: gemini-x.")).toBeInTheDocument();
  expect(model).toHaveAttribute("aria-invalid", "true");
});

test("editor sees read-only values", async () => {
  renderRoute("/projects/p1/config", "EDITOR");

  expect(
    await screen.findByText("You have view access. Ask an org admin to change these settings.")
  ).toBeInTheDocument();
  expect(screen.getByText(config.outputDir)).toBeInTheDocument();
  expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
});

/** Edits the output directory, so the form is dirty and Save is enabled. */
async function editOutputDir(user: ReturnType<typeof userEvent.setup>) {
  const dir = await screen.findByLabelText("Output directory");
  await user.clear(dir);
  await user.type(dir, "gen/");
}

/** Clicks Save and returns the body of the one PUT it sent. */
async function saveBody(user: ReturnType<typeof userEvent.setup>, bodies: unknown[]) {
  await user.click(screen.getByRole("button", { name: "Save" }));
  await waitFor(() => expect(bodies).toHaveLength(1));
  return bodies[0];
}

test("AI key left alone is sent as null", async () => {
  const user = userEvent.setup();
  const bodies = recordSaves();
  renderRoute("/projects/p1/config");
  await editOutputDir(user);
  expect(await saveBody(user, bodies)).toMatchObject({ aiApiKey: null });
});

test("Replace then Cancel keeps the stored key, sent as null", async () => {
  const user = userEvent.setup();
  const bodies = recordSaves();
  renderRoute("/projects/p1/config");
  await user.click(await screen.findByRole("button", { name: "Replace" }));
  await user.click(screen.getByRole("button", { name: "Cancel" }));
  await editOutputDir(user);
  expect(await saveBody(user, bodies)).toMatchObject({ aiApiKey: null });
});

test("a typed replacement key is sent as the typed value", async () => {
  const user = userEvent.setup();
  const bodies = recordSaves();
  renderRoute("/projects/p1/config");
  await user.click(await screen.findByRole("button", { name: "Replace" }));
  await user.type(screen.getByLabelText("AI key"), "k-123");
  expect(await saveBody(user, bodies)).toMatchObject({ aiApiKey: "k-123" });
});

test("Remove key is sent as an empty string", async () => {
  const user = userEvent.setup();
  const bodies = recordSaves();
  renderRoute("/projects/p1/config");
  await user.click(await screen.findByRole("button", { name: "Remove key" }));
  expect(screen.getByText("The key will be removed when you save.")).toBeInTheDocument();
  expect(await saveBody(user, bodies)).toMatchObject({ aiApiKey: "" });
});

test("Undo after Remove key leaves nothing to save", async () => {
  const user = userEvent.setup();
  const bodies = recordSaves();
  renderRoute("/projects/p1/config");
  await user.click(await screen.findByRole("button", { name: "Remove key" }));
  await user.click(screen.getByRole("button", { name: "Undo" }));
  expect(screen.getByText("Key set")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  expect(bodies).toHaveLength(0);
});

test("package fields follow the chosen languages", async () => {
  const user = userEvent.setup();
  renderRoute("/projects/p1/config");
  expect(await screen.findByLabelText("npm scope")).toBeInTheDocument();
  expect(screen.queryByLabelText("Maven group ID")).not.toBeInTheDocument();
  await user.click(screen.getByRole("checkbox", { name: "Java" }));
  expect(screen.getByLabelText("Maven group ID")).toBeInTheDocument();
  expect(screen.getByLabelText("Maven artifact ID")).toBeInTheDocument();
  await user.click(screen.getByRole("checkbox", { name: "TypeScript" }));
  expect(screen.queryByLabelText("npm scope")).not.toBeInTheDocument();
});

test("the welcome banner shows with welcome=1 and not on the plain page", async () => {
  renderRoute("/projects/p1/config?welcome=1");
  expect(await screen.findByText("Choose what to generate")).toBeInTheDocument();
});

test("the plain config page has no welcome banner", async () => {
  renderRoute("/projects/p1/config");
  expect(await screen.findByLabelText("Output directory")).toBeInTheDocument();
  expect(screen.queryByText("Choose what to generate")).not.toBeInTheDocument();
});
