import { useNavigate, useRouterState } from "@tanstack/react-router";
import { useCallback, useMemo } from "react";
import { useEventStream } from "@/lib/sse/useEventStream";
import { FILTER_PARAMS, type LogFilters, type LogLine, parseFilters } from "./logFilters";

/**
 * The live log of a run. The stream replays from the first line, so no separate list request is needed.
 * The step, level and q filters live in the URL search params, so a filtered view can be linked.
 * A null runId connects to nothing.
 */
export function useRunLogs(runId: string | null) {
  const stream = useEventStream<LogLine>(runId === null ? null : `/api/v1/runs/${encodeURIComponent(runId)}/logs/stream`);

  // The raw query string, not the parsed search object: the router turns `?q=404` into a number.
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const searchStr = useRouterState({ select: (state) => state.location.searchStr });
  const filters = useMemo(() => parseFilters(new URLSearchParams(searchStr)), [searchStr]);
  const navigate = useNavigate();
  // The run route owns its search schema, so the filters are written as a plain query string. Other params are kept.
  const setFilters = useCallback(
    (next: LogFilters) => {
      const params = new URLSearchParams(searchStr);
      for (const key of FILTER_PARAMS) params.delete(key);
      if (next.step) params.set("step", next.step);
      if (next.level) params.set("level", next.level);
      if (next.q) params.set("q", next.q);
      const query = params.toString();
      void navigate({ href: query ? `${pathname}?${query}` : pathname, replace: true });
    },
    [navigate, pathname, searchStr],
  );

  return { lines: stream.items, status: stream.status, retry: stream.reconnect, filters, setFilters };
}
