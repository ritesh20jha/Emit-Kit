import { queryOptions, useMutation, useQueryClient } from "@tanstack/react-query";
import { failureFeedback, toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";
import { toast } from "@/ui";

export type RunSummary = components["schemas"]["RunSummary"];
export type RunDetail = components["schemas"]["RunDetail"];

/** The overview lists this many runs. The first of them is the last run. */
const RECENT_RUN_COUNT = 5;

/** The newest runs of a project, one page. */
export function recentRunsQuery(projectId: string) {
  return queryOptions({
    queryKey: qk.runs.list(projectId, { limit: RECENT_RUN_COUNT }),
    queryFn: async () => {
      const { data, error, response } = await api.GET("/projects/{id}/runs", {
        params: { path: { id: projectId }, query: { limit: RECENT_RUN_COUNT } },
      });
      if (!response.ok) throw toAppError(response, error);
      return data as components["schemas"]["PageRunSummary"];
    },
  });
}

/** An active run is read again this often when polled, as a fallback to the refresh on each new step. */
const POLL_INTERVAL_MS = 10_000;
const ACTIVE: ReadonlySet<RunDetail["status"]> = new Set(["QUEUED", "RUNNING", "AWAITING_APPROVAL"]);
export const isActive = (status: RunDetail["status"]): boolean => ACTIVE.has(status);

/** One run in full, which carries its lint counts. `poll` re-reads it every 10 s while it is active (the run page does). */
export function runQuery(runId: string, poll = false) {
  return queryOptions({
    queryKey: qk.runs.one(runId),
    queryFn: async (): Promise<RunDetail> => {
      const { data, error, response } = await api.GET("/runs/{runId}", { params: { path: { runId } } });
      if (!response.ok) throw toAppError(response, error);
      return data as RunDetail;
    },
    refetchInterval: poll ? (query) => (query.state.data && isActive(query.state.data.status) ? POLL_INTERVAL_MS : false) : undefined,
  });
}

/** The project's generation settings. The overview reads only the AI provider and whether its key is set. */
export function configQuery(projectId: string) {
  return queryOptions({
    queryKey: qk.projects.config(projectId),
    queryFn: async () => {
      const { data, error, response } = await api.GET("/projects/{id}/config", { params: { path: { id: projectId } } });
      if (!response.ok) throw toAppError(response, error);
      return data as components["schemas"]["ConfigResponse"];
    },
  });
}

/** Starts a run of the project's current spec. Where the user goes next is the caller's choice. */
export function useTriggerRun(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (): Promise<RunDetail> => {
      const { data, error, response } = await api.POST("/projects/{id}/runs", {
        params: { path: { id: projectId } },
        body: { dryRun: false },
      });
      if (!response.ok) throw toAppError(response, error);
      return data as RunDetail;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.runs.list(projectId) }),
    onError: (error) => toast.error(failureFeedback(error)),
  });
}

/** Starts a new run that repeats an earlier one. */
export function useRerun(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (runId: string): Promise<RunDetail> => {
      const { data, error, response } = await api.POST("/runs/{runId}/rerun", { params: { path: { runId } } });
      if (!response.ok) throw toAppError(response, error);
      return data as RunDetail;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.runs.list(projectId) }),
    onError: (error) => toast.error(failureFeedback(error)),
  });
}
