import { keepPreviousData, queryOptions, useMutation, useQueryClient } from "@tanstack/react-query";
import { toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";

export type RepoOption = components["schemas"]["RepoOption"];
export type SpecCheck = components["schemas"]["SpecCheckResponse"];
export type ProjectDetail = components["schemas"]["ProjectDetail"];
export type CreateProjectRequest = components["schemas"]["CreateProjectRequest"];

/** Repositories the user can see, filtered by name. The previous list stays up while the next one loads. */
export function reposQuery(orgId: string, q: string) {
  return queryOptions({
    queryKey: qk.vcsRepos(orgId, q),
    queryFn: async (): Promise<RepoOption[]> => {
      const { data, error, response } = await api.GET("/orgs/{orgId}/vcs-repos", {
        params: { path: { orgId }, query: q ? { q } : {} },
      });
      if (!response.ok) throw toAppError(response, error);
      return data as RepoOption[];
    },
    placeholderData: keepPreviousData,
  });
}

/** Whether a spec file at `path` on `branch` parses and is valid. Callers enable it only once both are set. */
export function specCheckQuery(orgId: string, repo: string, path: string, branch: string) {
  const [owner = "", name = ""] = repo.split("/");
  return queryOptions({
    queryKey: qk.specCheck(orgId, repo, path, branch),
    queryFn: async (): Promise<SpecCheck> => {
      const { data, error, response } = await api.GET("/orgs/{orgId}/vcs-repos/{owner}/{repo}/spec-check", {
        params: { path: { orgId, owner, repo: name }, query: { path, branch: branch || undefined } },
      });
      if (!response.ok) throw toAppError(response, error);
      return data as SpecCheck;
    },
  });
}

/** Creates the project. The project list is stale afterwards, so every page and filter of it is refetched. */
export function useCreateProject(orgId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateProjectRequest): Promise<ProjectDetail> => {
      const { data, error, response } = await api.POST("/orgs/{orgId}/projects", {
        params: { path: { orgId } },
        body,
      });
      if (!response.ok) throw toAppError(response, error);
      return data as ProjectDetail;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.projects.forOrg(orgId) }),
  });
}
