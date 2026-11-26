import { queryOptions, useMutation, useQueryClient } from "@tanstack/react-query";
import { toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";

export type ConfigResponse = components["schemas"]["ConfigResponse"];
export type ConfigRequest = components["schemas"]["ConfigRequest"];

/** The project's generation settings. Same key as the overview's, so a save reaches both. */
export function configQuery(projectId: string) {
  return queryOptions({
    queryKey: qk.projects.config(projectId),
    queryFn: async (): Promise<ConfigResponse> => {
      const { data, error, response } = await api.GET("/projects/{id}/config", { params: { path: { id: projectId } } });
      if (!response.ok) throw toAppError(response, error);
      return data as ConfigResponse;
    },
  });
}

/**
 * Saves a new version of the config. The saved config becomes the cached one. A failure is the caller's to show,
 * because a field error belongs on its field.
 */
export function useSaveConfig(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: ConfigRequest): Promise<ConfigResponse> => {
      const { data, error, response } = await api.PUT("/projects/{id}/config", {
        params: { path: { id: projectId } },
        body,
      });
      if (!response.ok) throw toAppError(response, error);
      return data as ConfigResponse;
    },
    onSuccess: (saved) => queryClient.setQueryData(qk.projects.config(projectId), saved),
  });
}
