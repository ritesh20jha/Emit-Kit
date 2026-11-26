import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useRouterState } from "@tanstack/react-router";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { renderWithProviders } from "@/test/render";
import { LogView } from "./LogView";
import type { LogLine } from "./logFilters";
import { useRunLogs } from "./useRunLogs";

type RunLogs = ReturnType<typeof useRunLogs>;

/** Stands in for the browser EventSource. The test opens it and delivers log events. */
class FakeEventSource {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 2;
  static instances: FakeEventSource[] = [];

  readyState = FakeEventSource.CONNECTING;
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  private handlers = new Map<string, Array<(event: MessageEvent) => void>>();

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, handler: (event: MessageEvent) => void) {
    this.handlers.set(type, [...(this.handlers.get(type) ?? []), handler]);
  }

  close() {
    this.readyState = FakeEventSource.CLOSED;
  }

  open() {
    this.readyState = FakeEventSource.OPEN;
    this.onopen?.(new Event("open"));
  }

  send(type: string, data: string) {
    for (const handler of this.handlers.get(type) ?? []) handler(new MessageEvent(type, { data }));
  }
}

const LINES: LogLine[] = [
  { seq: 1, ts: "2026-11-13T15:30:00.000Z", level: "INFO", step: "PARSE_SPEC", message: "parsed the spec" },
  { seq: 2, ts: "2026-11-13T15:30:01.000Z", level: "ERROR", step: "PARSE_SPEC", message: "timeout while parsing" },
  { seq: 3, ts: "2026-11-13T15:30:02.000Z", level: "INFO", step: "FINALIZE", message: "done 404 items" },
];

/** The run page as WEB-13 will wire it: the hook feeds LogView, and the current URL is shown for the assertions. */
function Harness({ onHook }: { onHook: (hook: RunLogs) => void }) {
  const hook = useRunLogs("r1");
  onHook(hook);
  const href = useRouterState({ select: (state) => state.location.href });
  return (
    <>
      <span data-testid="location">{href}</span>
      <LogView
        lines={hook.lines}
        status={hook.status}
        filters={hook.filters}
        onFiltersChange={hook.setFilters}
        onRetry={hook.retry}
      />
    </>
  );
}

function renderRunLogs(route: string) {
  const hooks: RunLogs[] = [];
  renderWithProviders(<Harness onHook={(hook) => hooks.push(hook)} />, { route });
  return { hook: () => hooks.at(-1) as RunLogs };
}

/** Waits for the page to open its stream, then delivers LINES over it. */
async function streamLines() {
  await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));
  act(() => {
    const source = FakeEventSource.instances[0];
    source?.open();
    for (const line of LINES) source?.send("log", JSON.stringify(line));
  });
}

function currentUrl(): URL {
  return new URL(screen.getByTestId("location").textContent ?? "", "http://app.test");
}

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  // jsdom has no layout. A 480 px viewport lets the virtualizer render its rows.
  vi.spyOn(Element.prototype, "clientHeight", "get").mockReturnValue(480);
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("useRunLogs", () => {
  test("typing in the search box writes q to the URL and keeps other params", async () => {
    // No delay between keys: each keystroke lands before the router has finished the previous write.
    const user = userEvent.setup({ delay: null });
    renderRunLogs("/runs/r1?tab=logs");
    await streamLines();

    const search = await screen.findByRole("searchbox", { name: /search/i });
    await user.type(search, "timeout");

    await waitFor(() => expect(currentUrl().searchParams.get("q")).toBe("timeout"));
    expect(currentUrl().searchParams.get("tab")).toBe("logs");
    expect(search).toHaveValue("timeout");
  });

  test("a level in the URL narrows the rendered rows", async () => {
    renderRunLogs("/runs/r1?level=ERROR");
    await streamLines();

    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(1));
    expect(screen.getByRole("row")).toHaveTextContent("timeout while parsing");
  });

  test("a numeric-looking q in the URL is still applied", async () => {
    renderRunLogs("/runs/r1?q=404");
    await streamLines();

    expect(await screen.findByRole("searchbox", { name: /search/i })).toHaveValue("404");
    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(1));
    expect(screen.getByRole("row")).toHaveTextContent("done 404 items");
  });

  test("retry is exposed and restarts the stream", async () => {
    const { hook } = renderRunLogs("/runs/r1");
    await streamLines();

    expect(typeof hook().retry).toBe("function");
    act(() => hook().retry());
    expect(FakeEventSource.instances).toHaveLength(2);
  });
});
