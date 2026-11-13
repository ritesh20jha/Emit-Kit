import { useNavigate, useRouterState, useSearch } from "@tanstack/react-router";
import { useCallback, useMemo } from "react";
import { useEventStream } from "@/lib/sse/useEventStream";
import { type LogFilters, type LogLine, parseFilters } from "./logFilters";

/**
 * The live log of a run. The stream replays from the first line, so no separate list request is needed.
 * The step, level and q filters live in the URL search params, so a filtered view can be linked.
 * A null runId connects to nothing.
 */
export function useRunLogs(runId: string | null) {
  const stream = useEventStream<LogLine>(runId === null ? null : `/api/v1/runs/${encodeURIComponent(runId)}/logs/stream`);

  const search = useSearch({ strict: false });
  const filters = useMemo(() => parseFilters(search), [search]);
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  // The run route owns its search schema, so the filters are written as a plain query string here.
  const setFilters = useCallback(
    (next: LogFilters) => {
      const params = new URLSearchParams();
      if (next.step) params.set("step", next.step);
      if (next.level) params.set("level", next.level);
      if (next.q) params.set("q", next.q);
      const query = params.toString();
      void navigate({ href: query ? `${pathname}?${query}` : pathname, replace: true });
    },
    [navigate, pathname],
  );

  return { lines: stream.items, status: stream.status, retry: stream.reconnect, filters, setFilters };
}
