import { queryOptions, useMutation, useQueryClient } from "@tanstack/react-query";
import { failureFeedback, toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";
import { toast } from "@/ui";

export type Member = components["schemas"]["MemberResponse"];
export type Role = components["schemas"]["Role"];

export function membersQuery(orgId: string) {
  return queryOptions({
    queryKey: qk.orgs.members(orgId),
    queryFn: async (): Promise<Member[]> => {
      const { data, error, response } = await api.GET("/orgs/{orgId}/members", { params: { path: { orgId } } });
      if (!response.ok) throw toAppError(response, error);
      return data as Member[];
    },
  });
}

/**
 * Changes one member's role. The row changes at once; a failure puts the previous list back and toasts why.
 * The list is refetched afterwards, so the source column shows the server's value (Set manually).
 */
export function useChangeRole(orgId: string) {
  const queryClient = useQueryClient();
  const key = qk.orgs.members(orgId);
  return useMutation({
    mutationFn: async ({ userId, role }: { userId: string; role: Role }): Promise<Member> => {
      const { data, error, response } = await api.PATCH("/orgs/{orgId}/members/{userId}", {
        params: { path: { orgId, userId } },
        body: { role },
      });
      if (!response.ok) throw toAppError(response, error);
      return data as Member;
    },
    onMutate: async ({ userId, role }) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<Member[]>(key);
      queryClient.setQueryData<Member[]>(key, (rows) =>
        rows?.map((row) => (row.userId === userId ? { ...row, role } : row)),
      );
      return { previous };
    },
    onError: (error, _variables, context) => {
      queryClient.setQueryData(key, context?.previous);
      toast.error(failureFeedback(error));
    },
    // A self-demotion changes what /me says about the signed-in user, so the session is refetched too. Exact, so the
    // quota query under ["me"] is left alone.
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: key }),
        queryClient.invalidateQueries({ queryKey: qk.me, exact: true }),
      ]),
  });
}
