import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { components } from "@/lib/api/generated/schema";
import { LogView } from "./LogView";

type LogLine = components["schemas"]["LogLine"];

function make(count: number): LogLine[] {
  return Array.from({ length: count }, (_, index) => ({
    seq: index + 1,
    ts: "2026-11-13T15:30:00.000Z",
    level: index % 50 === 0 ? "WARN" : "INFO",
    step: index < count / 2 ? "RUN_GENERATORS" : "SYNTAX_CHECK",
    message: `generated line ${index + 1}`,
  }));
}

/** jsdom has no layout. A 480 px viewport lets the virtualizer render its rows. */
beforeEach(() => {
  vi.spyOn(Element.prototype, "clientHeight", "get").mockReturnValue(480);
});

afterEach(() => {
  vi.restoreAllMocks();
});

/** jsdom has no layout. These numbers make the grid look scrolled to the top of a 20 000 px log. */
function stubScrollMetrics(element: HTMLElement, scrollHeight: number, clientHeight: number) {
  Object.defineProperty(element, "scrollHeight", { configurable: true, value: scrollHeight });
  Object.defineProperty(element, "clientHeight", { configurable: true, value: clientHeight });
  Object.defineProperty(element, "scrollTop", { configurable: true, writable: true, value: 0 });
}

describe("LogView", () => {
  test("shows Reconnecting banner while reconnecting", () => {
    const { rerender } = render(<LogView lines={make(3)} status="reconnecting" />);
    expect(screen.getByText(/reconnecting/i)).toBeInTheDocument();

    rerender(<LogView lines={make(3)} status="open" />);
    expect(screen.queryByText(/reconnecting/i)).not.toBeInTheDocument();
  });

  test("100k lines render under budget", () => {
    const t = performance.now();
    render(<LogView lines={make(100_000)} />);
    expect(performance.now() - t).toBeLessThan(500);
    expect(screen.getAllByRole("row").length).toBeLessThan(200);
  });

  test("scrolling up pauses auto-scroll and shows Jump to latest", async () => {
    const user = userEvent.setup();
    const { rerender } = render(<LogView lines={make(500)} />);
    const grid = screen.getByRole("table");
    stubScrollMetrics(grid, 20_000, 500);

    // The reader scrolls up, away from the bottom of the log.
    grid.scrollTop = 0;
    fireEvent.scroll(grid);
    expect(screen.getByRole("button", { name: /jump to latest/i })).toBeInTheDocument();

    // A new line arrives while paused: the view stays where the reader put it.
    rerender(<LogView lines={make(501)} />);
    expect(grid.scrollTop).toBe(0);
    expect(screen.getByRole("button", { name: /jump to latest/i })).toBeInTheDocument();

    // Jump to latest moves to the bottom and resumes following.
    await user.click(screen.getByRole("button", { name: /jump to latest/i }));
    expect(grid.scrollTop).toBe(20_000);
    expect(grid).toHaveFocus();
    expect(screen.queryByRole("button", { name: /jump to latest/i })).not.toBeInTheDocument();
  });

  test("filters narrow the lines and the row count", async () => {
    const user = userEvent.setup();
    const onFiltersChange = vi.fn();
    const lines = make(4).map((line) => ({ ...line, level: line.seq === 2 ? "ERROR" : "INFO" }));
    render(
      <LogView
        lines={lines}
        filters={{ step: "", level: "ERROR", q: "" }}
        onFiltersChange={onFiltersChange}
      />,
    );

    expect(screen.getByRole("table")).toHaveAttribute("aria-rowcount", "1");
    expect(screen.getAllByRole("row")).toHaveLength(1);

    await user.type(screen.getByRole("searchbox", { name: /search/i }), "x");
    expect(onFiltersChange).toHaveBeenLastCalledWith({ step: "", level: "ERROR", q: "x" });
  });
});
