import type { QueryClient } from "@tanstack/react-query";
import { toAppError } from "@/lib/api/appError";
import { readCookie } from "@/lib/api/client";

/**
 * Ends the server session, drops every cached query and returns to the login page. Spring Security serves
 * /logout, so it is not in the generated client; the request carries the same XSRF header the client sends.
 */
export async function signOut(queryClient: QueryClient): Promise<void> {
  const token = readCookie("XSRF-TOKEN");
  const response = await globalThis.fetch(`${window.location.origin}/api/v1/logout`, {
    method: "POST",
    credentials: "include",
    headers: token === undefined ? undefined : { "X-XSRF-TOKEN": token },
  });
  if (!response.ok) throw toAppError(response, await response.json().catch(() => undefined));
  queryClient.clear();
  window.location.href = "/login";
}
