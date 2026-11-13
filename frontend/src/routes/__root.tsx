import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext, Outlet } from "@tanstack/react-router";
import { FileQuestion } from "lucide-react";
import { EmptyState } from "@/ui";

/** Available to every route's loaders and beforeLoad hooks. */
export interface RouterContext {
  queryClient: QueryClient;
}

export const Route = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
  notFoundComponent: NotFound,
});

function RootLayout() {
  return <Outlet />;
}

function NotFound() {
  return (
    <main className="flex min-h-dvh items-center justify-center p-4">
      <EmptyState icon={FileQuestion} title="Page not found" description="The address may be mistyped, or the page has moved." />
    </main>
  );
}
