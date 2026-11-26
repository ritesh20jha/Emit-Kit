import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from "@tanstack/react-router";
import { FolderOpen } from "lucide-react";
import type { ReactNode } from "react";
import type { MockInstance } from "vitest";
import { Breadcrumbs } from "./Breadcrumbs";
import { Button } from "./Button";
import { CodeBlock } from "./CodeBlock";
import { CopyButton } from "./CopyButton";
import { EmptyState } from "./EmptyState";
import { ErrorState } from "./ErrorState";
import { NoAccessState } from "./NoAccessState";
import { ResponsiveTable } from "./ResponsiveTable";
import { RouteTabs } from "./RouteTabs";
import { useMediaQuery } from "./useMediaQuery";

// Any console output fails the test: React, act and TanStack Router warnings must not reach the test log.
let consoleSpies: MockInstance[] = [];

// jsdom has no matchMedia. This fake evaluates min-width queries against a viewport width that a test sets.
type Listener = (event: { matches: boolean }) => void;
let viewport = 1024;
let listeners: { query: string; listener: Listener }[] = [];
const matchesQuery = (query: string) => viewport >= Number(/min-width:\s*(\d+)px/.exec(query)?.[1] ?? 0);

/** Resizes the viewport and notifies matchMedia listeners, as a browser resize does. */
function setViewport(width: number) {
  viewport = width;
  for (const { query, listener } of listeners) listener({ matches: matchesQuery(query) });
}

beforeEach(() => {
  consoleSpies = [vi.spyOn(console, "error"), vi.spyOn(console, "warn")];
  viewport = 1024;
  listeners = [];
  window.matchMedia = ((query: string) => ({
    get matches() {
      return matchesQuery(query);
    },
    media: query,
    addEventListener: (_: string, listener: Listener) => listeners.push({ query, listener }),
    removeEventListener: (_: string, listener: Listener) => {
      listeners = listeners.filter((entry) => entry.listener !== listener);
    },
  })) as unknown as typeof window.matchMedia;
});

afterEach(() => {
  for (const spy of consoleSpies) {
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  }
  vi.restoreAllMocks();
});

/** Renders ui as the page for path, inside a memory-history router that knows the project routes. Waits for the router to load. */
async function renderAt(path: string, ui: ReactNode) {
  const root = createRootRoute();
  const page = () => <>{ui}</>;
  const paths = ["/projects/p1", "/projects/p1/runs", "/projects/p1/settings", "/projects/p1/secrets", "/projects/p1/members", "/projects/p1/webhooks", "/projects/p1/audit"];
  const routes = paths.map((to) =>
    createRoute({ getParentRoute: () => root, path: to, component: page }),
  );
  const router = createRouter({
    routeTree: root.addChildren(routes),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  const result = render(<RouterProvider router={router} />);
  await act(() => router.load());
  return result;
}

const TABS = [
  { to: "/projects/p1", label: "Overview" },
  { to: "/projects/p1/runs", label: "Runs" },
  { to: "/projects/p1/settings", label: "Settings" },
];

const SEVEN_TABS = [
  ...TABS,
  { to: "/projects/p1/secrets", label: "Secrets" },
  { to: "/projects/p1/members", label: "Members" },
  { to: "/projects/p1/webhooks", label: "Webhooks" },
  { to: "/projects/p1/audit", label: "Audit log" },
];

const RUNS = [
  { id: "r1", name: "emitkit generate", status: "Succeeded" },
  { id: "r2", name: "emitkit publish", status: "Failed" },
];

const RUN_COLUMNS = [
  { key: "name", header: "Run", cell: (run: (typeof RUNS)[number]) => run.name },
  { key: "status", header: "Status", cell: (run: (typeof RUNS)[number]) => run.status },
];

test("RouteTabs marks the active route", async () => {
  await renderAt("/projects/p1/runs", <RouteTabs tabs={TABS.slice(0, 2)} />);
  expect(screen.getByRole("link", { name: "Runs" })).toHaveAttribute("aria-current", "page");
  expect(screen.getByRole("link", { name: "Overview" })).not.toHaveAttribute("aria-current");
});

test("RouteTabs keeps the active tab when the page has search params", async () => {
  await renderAt("/projects/p1/runs?status=failed", <RouteTabs tabs={TABS} />);
  expect(screen.getByRole("link", { name: "Runs" })).toHaveAttribute("aria-current", "page");
  expect(screen.getByRole("link", { name: "Overview" })).not.toHaveAttribute("aria-current");
});

test("RouteTabs arrow keys and Home/End move focus between tabs", async () => {
  const user = userEvent.setup();
  await renderAt("/projects/p1", <RouteTabs tabs={TABS} />);
  const [overview, runs, settings] = screen.getAllByRole("link") as [HTMLElement, HTMLElement, HTMLElement];
  overview.focus();

  await user.keyboard("{ArrowRight}");
  expect(runs).toHaveFocus();
  await user.keyboard("{ArrowRight}");
  expect(settings).toHaveFocus();
  await user.keyboard("{ArrowRight}");
  expect(overview).toHaveFocus();
  await user.keyboard("{ArrowLeft}");
  expect(settings).toHaveFocus();
  await user.keyboard("{Home}");
  expect(overview).toHaveFocus();
  await user.keyboard("{End}");
  expect(settings).toHaveFocus();
});

test("RouteTabs keeps all seven tabs in a nav that scrolls sideways instead of overflowing", async () => {
  await renderAt("/projects/p1", <RouteTabs tabs={SEVEN_TABS} />);
  const nav = screen.getByRole("navigation", { name: "Sections" });
  expect(nav).toHaveClass("overflow-x-auto", "-m-1", "p-1", "scroll-px-1");
  expect(nav.querySelector("ul")).toHaveClass("w-max", "min-w-full");
  expect(within(nav).getAllByRole("link")).toHaveLength(7);
});

test("RouteTabs scrolls each focused tab into view, so its focus ring is not cut off at the nav edge", async () => {
  const user = userEvent.setup();
  // jsdom has no scrollIntoView. Stub it on the prototype for this test only.
  const scrollIntoView = vi.fn();
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: scrollIntoView });
  try {
    await renderAt("/projects/p1", <RouteTabs tabs={SEVEN_TABS} />);
    const links = screen.getAllByRole("link");
    for (let i = 0; i < links.length; i++) await user.tab();
    expect(links.at(-1)).toHaveFocus();
    expect(scrollIntoView).toHaveBeenLastCalledWith({ block: "nearest", inline: "nearest" });
    expect(scrollIntoView.mock.contexts.at(-1)).toBe(links.at(-1));
  } finally {
    delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView;
  }
});

test("ErrorState shows the message and code, and Retry calls onRetry", async () => {
  const user = userEvent.setup();
  const onRetry = vi.fn();
  render(<ErrorState error={{ code: "RUN_NOT_FOUND", message: "This run no longer exists." }} onRetry={onRetry} />);

  const alert = screen.getByRole("alert");
  expect(within(alert).getByText("This run no longer exists.")).toBeInTheDocument();
  expect(within(alert).getByText("RUN_NOT_FOUND").tagName).toBe("CODE");

  await user.click(screen.getByRole("button", { name: "Retry" }));
  expect(onRetry).toHaveBeenCalledTimes(1);
});

test("ResponsiveTable renders cards below 768px and a table at 768px and up", () => {
  const table = () => (
    <ResponsiveTable label="Runs" columns={RUN_COLUMNS} rows={RUNS} rowKey={(run) => run.id} renderCard={(run) => run.name} />
  );
  setViewport(375);
  const { unmount } = render(table());
  expect(within(screen.getByRole("list", { name: "Runs" })).getAllByRole("listitem")).toHaveLength(2);
  expect(screen.queryByRole("table")).not.toBeInTheDocument();
  unmount();

  setViewport(1024);
  render(table());
  expect(within(screen.getByRole("table", { name: "Runs" })).getAllByRole("columnheader")).toHaveLength(2);
  expect(screen.queryByRole("list")).not.toBeInTheDocument();
});

test("useMediaQuery reacts when the viewport changes", () => {
  function ViewportProbe() {
    return <p>{useMediaQuery("(min-width: 768px)") ? "wide" : "narrow"}</p>;
  }
  setViewport(375);
  render(<ViewportProbe />);
  expect(screen.getByText("narrow")).toBeInTheDocument();

  act(() => setViewport(1024));
  expect(screen.getByText("wide")).toBeInTheDocument();
});

test("CopyButton writes the value to the clipboard and announces Copied", async () => {
  const user = userEvent.setup();
  render(<CopyButton value="emitkit run --watch" label="Copy command" />);

  await user.click(screen.getByRole("button", { name: "Copy command" }));

  expect(await navigator.clipboard.readText()).toBe("emitkit run --watch");
  const status = screen.getByRole("status");
  await waitFor(() => expect(status).toHaveTextContent("Copied"));
});

test("CopyButton announces Copy failed when the clipboard rejects, without throwing", async () => {
  const user = userEvent.setup();
  vi.spyOn(navigator.clipboard, "writeText").mockRejectedValueOnce(new Error("Permission denied"));
  render(<CopyButton value="secret" />);

  await user.click(screen.getByRole("button", { name: "Copy" }));

  const status = screen.getByRole("status");
  await waitFor(() => expect(status).toHaveTextContent("Copy failed"));
});

test("NoAccessState states the action and names the admins", () => {
  render(<NoAccessState action="rotate secrets" admins={["ana@acme.dev", "raj@acme.dev"]} />);
  expect(screen.getByText("You need admin access to rotate secrets. Ask an org admin.")).toBeInTheDocument();
  expect(screen.getByText("Org admins: ana@acme.dev, raj@acme.dev")).toBeInTheDocument();
});

test("EmptyState action fires", async () => {
  const user = userEvent.setup();
  const onCreate = vi.fn();
  render(<EmptyState icon={FolderOpen} title="No projects yet" action={<Button onClick={onCreate}>New project</Button>} />);
  expect(screen.getByRole("heading", { name: "No projects yet" })).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "New project" }));
  expect(onCreate).toHaveBeenCalledTimes(1);
});

test("Breadcrumbs links earlier items and marks the last as the current page", async () => {
  await renderAt("/projects/p1/runs", <Breadcrumbs items={[{ label: "Overview", to: "/projects/p1" }, { label: "Runs" }]} />);
  const nav = screen.getByRole("navigation", { name: "Breadcrumb" });
  expect(within(nav).getByRole("link", { name: "Overview" })).toHaveAttribute("href", "/projects/p1");
  expect(within(nav).getByText("Runs")).toHaveAttribute("aria-current", "page");
  expect(within(nav).queryByRole("link", { name: "Runs" })).not.toBeInTheDocument();
});

test("CodeBlock scroll region is keyboard focusable with an accessible name", () => {
  render(<CodeBlock code="npm test" language="bash" />);
  const region = screen.getByRole("region", { name: "bash code" });
  region.focus();
  expect(region).toHaveFocus();
  expect(screen.getByRole("button", { name: "Copy code" })).toBeInTheDocument();
});

test("CodeBlock regions are named by language, fall back to Code, and label overrides", () => {
  render(
    <>
      <CodeBlock code="on: push" language="yaml" />
      <CodeBlock code="plain output" />
      <CodeBlock code="emitkit generate" language="bash" label="Generate command" />
    </>,
  );
  expect(screen.getByRole("region", { name: "yaml code" })).toBeInTheDocument();
  expect(screen.getByRole("region", { name: "Code" })).toBeInTheDocument();
  expect(screen.getByRole("region", { name: "Generate command" })).toBeInTheDocument();
});
