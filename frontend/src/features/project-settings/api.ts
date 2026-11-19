import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { ProjectDetail } from "@/features/projects";
import { failureFeedback, toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";
import { toast } from "@/ui";

type WebhookStatus = components["schemas"]["WebhookStatus"];

/** Registers the push webhook on the project's repository again, for when GitHub no longer delivers pushes. */
export function useRegisterWebhook(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (): Promise<WebhookStatus> => {
      const { data, error, response } = await api.POST("/projects/{id}/webhook", { params: { path: { id: projectId } } });
      if (!response.ok) throw toAppError(response, error);
      return data as WebhookStatus;
    },
    onSuccess: (webhook) => {
      toast.success("Webhook registered");
      queryClient.setQueryData<ProjectDetail>(qk.projects.one(projectId), (project) => project && { ...project, webhook });
    },
    onError: (error) => toast.error(failureFeedback(error)),
    // A failed attempt can leave the webhook gone, so the project is read again whatever the outcome.
    onSettled: () => queryClient.invalidateQueries({ queryKey: qk.projects.one(projectId) }),
  });
}

/**
 * Deletes the project. The caller navigates away on success. The org's project lists are marked stale, so none shows it from cache.
 */
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
