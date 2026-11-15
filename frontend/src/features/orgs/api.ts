import { queryOptions, useMutation, useQueryClient } from "@tanstack/react-query";
import { failureFeedback, toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";
import { toast } from "@/ui";

export type OrgDetail = components["schemas"]["OrgDetail"];

export function orgQuery(orgId: string) {
  return queryOptions({
    queryKey: qk.orgs.one(orgId),
    queryFn: async (): Promise<OrgDetail> => {
      const { data, error, response } = await api.GET("/orgs/{orgId}", { params: { path: { orgId } } });
      if (!response.ok) throw toAppError(response, error);
      return data as OrgDetail;
    },
  });
}

/** Re-reads the signed-in user's orgs from GitHub. Refetches the session and every org view, which carry the counts. */
export function useResync() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (): Promise<void> => {
      const { error, response } = await api.POST("/me/sync-orgs");
      if (!response.ok) throw toAppError(response, error);
    },
    onSuccess: async () => {
      toast.success("Synced");
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: qk.me }),
        queryClient.invalidateQueries({ queryKey: qk.orgs.all }),
      ]);
    },
    onError: (error) => toast.error(failureFeedback(error)),
  });
}
