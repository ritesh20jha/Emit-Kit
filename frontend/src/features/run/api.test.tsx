import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { expect, test, vi } from "vitest";
import type { LogLine } from "@/features/run-logs";
import { qk } from "@/lib/api/queryKeys";
import { type RunDetail, runQuery, useRefreshRunOnStep } from "./api";

const line = (step: LogLine["step"]): LogLine => ({ seq: 1, ts: "2026-11-13T15:00:00Z", level: "INFO", message: "working", step });

test("reads the run again once per new step in the log", () => {
  const queryClient = new QueryClient();
  const invalidate = vi.spyOn(queryClient, "invalidateQueries");
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  const { rerender } = renderHook(({ lines }: { lines: LogLine[] }) => useRefreshRunOnStep("r1", lines), {
    wrapper,
    initialProps: { lines: [line("RUN_GENERATORS")] },
  });
  expect(invalidate).toHaveBeenCalledTimes(1);
  expect(invalidate).toHaveBeenLastCalledWith({ queryKey: qk.runs.one("r1") });

  rerender({ lines: [line("RUN_GENERATORS"), line("RUN_GENERATORS")] }); // more lines, same step: no read
  expect(invalidate).toHaveBeenCalledTimes(1);
  rerender({ lines: [line("RUN_GENERATORS"), line("COMMIT_OUTPUT")] });
  expect(invalidate).toHaveBeenCalledTimes(2);
});

/** The run query's polling, called as TanStack Query calls it with the cached run. */
function intervalFor(status: RunDetail["status"]) {
  const { refetchInterval } = runQuery("r1");
  if (typeof refetchInterval !== "function") throw new Error("runQuery should poll with a function");
  return refetchInterval({ state: { data: { status } } } as never);
}

test("polls every 10 seconds while the run is active", () => {
  for (const status of ["QUEUED", "RUNNING", "AWAITING_APPROVAL"] as const) expect(intervalFor(status)).toBe(10_000);
});

test("stops polling once the run has ended", () => {
  for (const status of ["SUCCESS", "FAILED", "CANCELLED"] as const) expect(intervalFor(status)).toBe(false);
});
