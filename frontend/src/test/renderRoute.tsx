import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { AppProviders } from "@/app/providers";
import { createAppRouter } from "@/app/router";
import type { components } from "@/lib/api/generated/schema";
import { defaultMe } from "./msw/handlers/me";
import { server } from "./msw/server";

/** jsdom has no matchMedia. The shell and responsive tables read it, so the viewport width is faked. */
export function stubViewport(width = 1280) {
  window.matchMedia = ((query: string) => {
    const max = /max-width:\s*(\d+)px/.exec(query);
    const matches = max ? width <= Number(max[1]) : false;
    return { matches, media: query, addEventListener: () => {}, removeEventListener: () => {} };
  }) as unknown as typeof window.matchMedia;
}

/** Mounts the real app router at `path`, so route files, shell and page features run together. `role` is the user's role in org1. */
export function renderRoute(path: string, role: components["schemas"]["Role"] = "ADMIN") {
  if (role !== "ADMIN") {
    const orgs = [{ orgId: "org1", name: "Acme", slug: "acme", isPersonal: false, role }];
    server.use(http.get("/api/v1/me", () => HttpResponse.json({ ...defaultMe, orgs })));
  }
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }));
  render(
    <AppProviders queryClient={queryClient}>
      <RouterProvider router={router} />
    </AppProviders>,
  );
  return router;
}
