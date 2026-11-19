import { keepPreviousData, queryOptions } from "@tanstack/react-query";
import { toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";
import type { ProjectSearch } from "./schemas";

export type PageProjectSummary = components["schemas"]["PageProjectSummary"];
export type ProjectDetail = components["schemas"]["ProjectDetail"];

/** One project's detail. Every project page shares this query, so it is read once per project. */
export function projectQuery(projectId: string) {
  return queryOptions({
    queryKey: qk.projects.one(projectId),
    queryFn: async (): Promise<ProjectDetail> => {
      const { data, error, response } = await api.GET("/projects/{id}", { params: { path: { id: projectId } } });
      if (!response.ok) throw toAppError(response, error);
      return data as ProjectDetail;
    },
  });
}

/** One page of the org's projects for these filters. The previous page stays on screen while the next one loads. */
export function projectsQuery(orgId: string, search: ProjectSearch) {
  return queryOptions({
    queryKey: qk.projects.list(orgId, { ...search }),
    queryFn: async (): Promise<PageProjectSummary> => {
      const { data, error, response } = await api.GET("/orgs/{orgId}/projects", {
        params: { path: { orgId }, query: search },
      });
      if (!response.ok) throw toAppError(response, error);
      return data as PageProjectSummary;
    },
    placeholderData: keepPreviousData,
  });
}
