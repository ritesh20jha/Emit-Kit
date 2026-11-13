import { queryOptions, useQuery } from "@tanstack/react-query";
import { TriangleAlert } from "lucide-react";
import { toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";

const POLL_MS = 60_000;
/** Shown only when less than this share of the GitHub API quota is left. */
const LOW_RATIO = 0.2;

const vcsQuotaQuery = queryOptions({
  queryKey: qk.vcsQuota,
  queryFn: async (): Promise<components["schemas"]["QuotaResponse"]> => {
    const { data, error, response } = await api.GET("/me/vcs-quota");
    if (!response.ok) throw toAppError(response, error);
    return data as components["schemas"]["QuotaResponse"];
  },
  refetchInterval: POLL_MS,
});

function formatTime(iso: string): string {
  const date = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * Warns when the GitHub API quota runs low. Renders nothing while it is healthy, loading or unavailable:
 * the quota is an advisory, not a view of its own.
 */
export function QuotaIndicator() {
  const { data } = useQuery(vcsQuotaQuery);
  if (!data) return null;
  const ratio = data.limit > 0 ? data.remaining / data.limit : 0;
  if (ratio >= LOW_RATIO) return null;
  return (
    <p className="flex items-center gap-1.5 text-xs text-status-awaiting">
      <TriangleAlert aria-hidden="true" className="size-3.5 shrink-0" />
      <span>GitHub quota low: resets at {formatTime(data.resetAt)}</span>
    </p>
  );
}
