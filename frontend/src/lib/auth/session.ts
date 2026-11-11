import { queryOptions, useQuery } from "@tanstack/react-query";
import { toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";

export type Me = components["schemas"]["MeResponse"];

export const meQuery = queryOptions({
  queryKey: qk.me,
  queryFn: async (): Promise<Me> => {
    const { data, error, response } = await api.GET("/me");
    if (!data) throw toAppError(response, error);
    return data;
  },
});

/** The signed-in user with their org memberships and roles. */
export function useMe() {
  return useQuery(meQuery);
}
