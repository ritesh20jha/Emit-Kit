import { useMutation, useQueryClient } from "@tanstack/react-query";
import { failureFeedback, toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import { qk } from "@/lib/api/queryKeys";
import { toast } from "@/ui";

/** Registers the push webhook on the project's repository again, for when GitHub no longer delivers pushes. */
export function useRegisterWebhook(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const { data, error, response } = await api.POST("/projects/{id}/webhook", { params: { path: { id: projectId } } });
      if (!response.ok) throw toAppError(response, error);
      return data;
    },
    onSuccess: async () => {
      toast.success("Webhook registered");
      await queryClient.invalidateQueries({ queryKey: qk.projects.one(projectId) });
    },
    onError: (error) => toast.error(failureFeedback(error)),
  });
}

/** Deletes the project. The caller navigates away on success. The org's project lists are marked stale, so none shows it from cache. */
export function useDeleteProject(projectId: string, orgId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const { error, response } = await api.DELETE("/projects/{id}", { params: { path: { id: projectId } } });
      if (!response.ok) throw toAppError(response, error);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.projects.list(orgId) }),
  });
}
