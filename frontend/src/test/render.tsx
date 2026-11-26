import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from "@tanstack/react-router";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { qk } from "@/lib/api/queryKeys";
import type { Me } from "@/lib/auth/session";

interface RenderOptions {
  /** Mounts `ui` inside the router at this URL (path plus optional search). */
  route?: string;
  /** Seeds the `/me` query so the first render already has the session. */
  me?: Me;
}

/**
 * Renders `ui` with a fresh QueryClient (no retries, no refetch of seeded data) and,
 * when `route` is given, a memory-history router whose catch-all route renders `ui`.
 */
export function renderWithProviders(ui: ReactElement, { route, me }: RenderOptions = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  if (me) queryClient.setQueryData(qk.me, me);

  const content =
    route === undefined ? ui : <RouterProvider router={routerFor(ui, route)} />;

  return {
    queryClient,
    ...render(<QueryClientProvider client={queryClient}>{content}</QueryClientProvider>),
  };
}

function routerFor(ui: ReactElement, route: string) {
  const rootRoute = createRootRoute();
  const page = createRoute({ getParentRoute: () => rootRoute, path: "$", component: () => ui });
  return createRouter({
    routeTree: rootRoute.addChildren([page]),
    history: createMemoryHistory({ initialEntries: [route] }),
  });
}
