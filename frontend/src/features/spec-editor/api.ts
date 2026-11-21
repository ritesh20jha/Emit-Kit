import { queryOptions, useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { failureFeedback, toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";
import { toast, type ToastAction } from "@/ui";

export type SpecResponse = components["schemas"]["SpecResponse"];
type SaveSpecRequest = components["schemas"]["SaveSpecRequest"];
export type SaveSpecResponse = components["schemas"]["SaveSpecResponse"];
export type LintIssue = components["schemas"]["LintIssue"];
export type RunDetail = components["schemas"]["RunDetail"];

/** The project's spec file: its text, its format and the sha a save must name as its base. */
export function specQuery(projectId: string) {
  return queryOptions({
    queryKey: qk.projects.spec(projectId),
    queryFn: async (): Promise<SpecResponse> => {
      const { data, error, response } = await api.GET("/projects/{id}/spec", { params: { path: { id: projectId } } });
      if (!response.ok) throw toAppError(response, error);
      return data as SpecResponse;
    },
  });
}

/** Lint issues for one text, from the server. The text is the key, and the result never goes stale. */
export function lintQuery(content: string) {
  return queryOptions({
    queryKey: qk.lint(content),
    staleTime: Infinity,
    queryFn: async (): Promise<LintIssue[]> => {
      const { data, error, response } = await api.POST("/lint", { body: { content } });
      if (!response.ok) throw toAppError(response, error);
      return data as LintIssue[];
    },
  });
}

/**
 * Saves the spec. A 200 means saved, and a 202 means saved while the generation run did not start (`runError`).
 * A 409 rejects with SPEC_CONFLICT, which the caller hands on.
 */
export function useSaveSpec(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: SaveSpecRequest): Promise<SaveSpecResponse> => {
      const { data, error, response } = await api.PUT("/projects/{id}/spec", {
        params: { path: { id: projectId } },
        body,
      });
      if (!response.ok) throw toAppError(response, error);
      return data as SaveSpecResponse;
    },
    onSuccess: (saved, body) => {
      // The saved text is the base of the next save, so the cached spec follows it. A remount then starts from it.
      queryClient.setQueryData<SpecResponse>(qk.projects.spec(projectId), (spec) => spec && { ...spec, content: body.content, sha: saved.newSha });
      void queryClient.invalidateQueries({ queryKey: qk.projects.spec(projectId) });
      void queryClient.invalidateQueries({ queryKey: qk.versions(projectId) });
      void queryClient.invalidateQueries({ queryKey: qk.runs.list(projectId) });
    },
  });
}

/** Starts a dry run (`true`) or a generation run (`false`) of the project's saved spec. */
export function useStartRun(projectId: string) {
  const queryClient = useQueryClient();
  const viewRun = useViewRun();
  return useMutation({
    mutationFn: async (dryRun: boolean): Promise<RunDetail> => {
      const { data, error, response } = await api.POST("/projects/{id}/runs", {
        params: { path: { id: projectId } },
        body: { dryRun },
      });
      if (!response.ok) throw toAppError(response, error);
      return data as RunDetail;
    },
    onSuccess: (run, dryRun) => {
      void queryClient.invalidateQueries({ queryKey: qk.runs.list(projectId) });
      toast.success(dryRun ? "Dry run started" : "Run started", viewRun(run.id));
    },
    onError: (error) => toast.error(failureFeedback(error)),
  });
}

/** Builds the "View run" toast action for a run id. */
export function useViewRun() {
  const navigate = useNavigate();
  return (runId: string): ToastAction => ({
    label: "View run",
    onClick: () => void navigate({ to: `/runs/${runId}` as "/" }),
  });
}
