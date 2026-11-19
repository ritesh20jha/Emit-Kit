import { queryOptions } from "@tanstack/react-query";
import { toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";

export type VersionResponse = components["schemas"]["VersionResponse"];

const CHANGE_TYPE: Record<VersionResponse["changeType"], string> = { MAJOR: "Major", MINOR: "Minor", PATCH: "Patch" };

/** The word for a version's change type, as the history and the overview show it. */
export const changeTypeLabel = (changeType: VersionResponse["changeType"]) => CHANGE_TYPE[changeType];

/** The project's published versions, newest first. */
export function versionsQuery(projectId: string) {
  return queryOptions({
    queryKey: qk.versions(projectId),
    queryFn: async (): Promise<VersionResponse[]> => {
      const { data, error, response } = await api.GET("/projects/{id}/versions", { params: { path: { id: projectId } } });
      if (!response.ok) throw toAppError(response, error);
      return data as VersionResponse[];
    },
  });
}
