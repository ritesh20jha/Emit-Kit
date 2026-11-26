import { keepPreviousData, queryOptions, useMutation, useQueryClient } from "@tanstack/react-query";
import { failureFeedback, toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";
import { toast } from "@/ui";
import type { RunSearch, RunStatus } from "./schemas";

export type PageRunSummary = components["schemas"]["PageRunSummary"];
type RunDetail = components["schemas"]["RunDetail"];

/** Runs per page. */
const PAGE_SIZE = 20;

/** How often a list that holds a moving run is read again. */
const POLL_INTERVAL_MS = 5000;

const MOVING: ReadonlySet<RunStatus> = new Set(["QUEUED", "RUNNING"]);

/** Whether a page holds a run that is still moving, so the list should be read again. */
const hasMovingRun = (page: PageRunSummary | undefined): boolean =>
  page?.items.some((run) => MOVING.has(run.status)) ?? false;

/**
 * One page of the project's runs for these filters, polled while a run is queued or running. The router's search also
 * carries any unknown URL keys, so only the three run filters are taken, for both the request and the cache key.
 * The previous page stays on screen while a new filter or page loads.
 */
export function runsQuery(projectId: string, { status, trigger, cursor }: RunSearch) {
  const filters = { status, trigger, cursor };
  return queryOptions({
    queryKey: qk.runs.list(projectId, filters),
    queryFn: async (): Promise<PageRunSummary> => {
      const { data, error, response } = await api.GET("/projects/{id}/runs", {
        params: { path: { id: projectId }, query: { ...filters, limit: PAGE_SIZE } },
      });
      if (!response.ok) throw toAppError(response, error);
      return data as PageRunSummary;
    },
    placeholderData: keepPreviousData,
    refetchInterval: (query) => (hasMovingRun(query.state.data) ? POLL_INTERVAL_MS : false),
  });
}

/** Starts a run of the project's current spec. A dry run generates without publishing. Where the user goes next is the caller's choice. */
export function useStartRun(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (dryRun: boolean): Promise<RunDetail> => {
      const { data, error, response } = await api.POST("/projects/{id}/runs", {
        params: { path: { id: projectId } },
        body: { dryRun },
      });
      if (!response.ok) throw toAppError(response, error);
      return data as RunDetail;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.runs.list(projectId) }),
    onError: (error) => toast.error(failureFeedback(error)),
  });
}
