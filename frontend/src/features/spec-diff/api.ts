import { queryOptions } from "@tanstack/react-query";
import { toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";

export type SnapshotSummary = components["schemas"]["SnapshotSummary"];
export type SpecDiff = components["schemas"]["SpecDiff"];
export type OpRef = components["schemas"]["OpRef"];
export type Change = components["schemas"]["Change"];
export type ChangeKind = components["schemas"]["ChangeKind"];

/** The saved versions of the spec, newest first. */
export function snapshotsQuery(projectId: string) {
  return queryOptions({
    queryKey: qk.projects.snapshots(projectId),
    queryFn: async (): Promise<SnapshotSummary[]> => {
      const { data, error, response } = await api.GET("/projects/{id}/spec/snapshots", { params: { path: { id: projectId } } });
      if (!response.ok) throw toAppError(response, error);
      return data as SnapshotSummary[];
    },
  });
}

/** What changed between two saved versions. `from` is the older one and `to` the newer. */
export function specDiffQuery(projectId: string, from: string, to: string) {
  return queryOptions({
    queryKey: qk.projects.specDiff(projectId, from, to),
    queryFn: async (): Promise<SpecDiff> => {
      const { data, error, response } = await api.GET("/projects/{id}/spec/diff", {
        params: { path: { id: projectId }, query: { from, to } },
      });
      if (!response.ok) throw toAppError(response, error);
      return data as SpecDiff;
    },
  });
}
