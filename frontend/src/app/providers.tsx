import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { AppError } from "@/lib/api/appError";

/**
 * The app's QueryClient. Client errors (4xx) are not retried: a 401 must reach the login redirect at once,
 * and retrying a 403 or 404 only delays the message.
 */
export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (failureCount, error) => failureCount < 3 && !(error instanceof AppError && error.status < 500),
      },
    },
  });
}

/** Wraps the app in global providers. The router gets the same client through its context. */
export function AppProviders({ queryClient, children }: { queryClient: QueryClient; children: ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
