import { queryOptions, useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { type RunDetail } from "@/features/project-overview";
import type { LogLine } from "@/features/run-logs";
import { failureFeedback, toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";
import { toast } from "@/ui";

export { isActive, type RunDetail, runQuery } from "@/features/project-overview";
export type ManifestEntry = components["schemas"]["ManifestEntry"];
export type FileView = components["schemas"]["FileView"];
export type LintIssue = components["schemas"]["LintIssue"];
type Decision = components["schemas"]["Decision"];

/** The data of a call that succeeded. A failed one throws its AppError. */
async function unwrap<T>(call: Promise<{ data?: unknown; error?: unknown; response: Response }>): Promise<T> {
  const { data, error, response } = await call;
  if (!response.ok) throw toAppError(response, error);
  return data as T;
}

export const filesQuery = (runId: string) =>
  queryOptions({ queryKey: qk.runs.files(runId), queryFn: () => unwrap<ManifestEntry[]>(api.GET("/runs/{runId}/files", { params: { path: { runId } } })) });

/** One file with its unified diff against the target branch. */
export function fileQuery(runId: string, path: string) {
  return queryOptions({
    queryKey: [...qk.runs.files(runId), path, "diff"],
    queryFn: () => unwrap<FileView>(api.GET("/runs/{runId}/files/{path}", { params: { path: { runId, path }, query: { diff: true } } })),
  });
}

export const lintQuery = (runId: string) =>
  queryOptions({ queryKey: qk.runs.lint(runId), queryFn: () => unwrap<LintIssue[]>(api.GET("/runs/{runId}/lint", { params: { path: { runId } } })) });

/** Cancels a run that has not ended. The dialog that asks shows any error, so none is toasted here. */
export function useCancelRun(runId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap<RunDetail>(api.POST("/runs/{runId}/cancel", { params: { path: { runId } } })),
    onSuccess: (run) => queryClient.setQueryData(qk.runs.one(runId), run),
  });
}

/** Starts a new run that repeats an earlier one. The caller opens it. */
export function useRerun(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (runId: string) => unwrap<RunDetail>(api.POST("/runs/{runId}/rerun", { params: { path: { runId } } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.runs.list(projectId) }),
    onError: (error) => toast.error(failureFeedback(error)),
  });
}

/** Approves or rejects a run awaiting approval. The answer replaces the run in the cache. */
export function useDecideApproval(runId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ decision, comment }: { decision: Decision; comment: string }) =>
      unwrap<RunDetail>(api.POST("/runs/{runId}/approval", { params: { path: { runId } }, body: { decision, comment: comment.trim() || null } })),
    onSuccess: (run) => queryClient.setQueryData(qk.runs.one(runId), run),
    onError: (error) => toast.error(failureFeedback(error)),
  });
}

/** Reads the run again when the log names a new step, so the header and timeline follow it between polls. Only the newest step is compared. */
export function useRefreshRunOnStep(runId: string, lines: LogLine[]) {
  const queryClient = useQueryClient();
  let step: string | undefined;
  for (let index = lines.length - 1; index >= 0 && step === undefined; index--) step = lines[index]?.step ?? undefined;
  useEffect(() => {
    if (step !== undefined) void queryClient.invalidateQueries({ queryKey: qk.runs.one(runId) });
  }, [queryClient, runId, step]);
}
