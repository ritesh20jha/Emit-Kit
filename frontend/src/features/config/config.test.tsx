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
