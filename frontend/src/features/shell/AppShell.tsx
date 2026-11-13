import { useRouter, useRouterState } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { useMe } from "@/lib/auth/session";
import { type FeedbackError, ErrorState, Skeleton, useMediaQuery } from "@/ui";
import { defaultOrgId, lastOrg } from "./lastOrg";
import { MobileDrawer } from "./MobileDrawer";
import { Sidebar } from "./Sidebar";
import { TopBar } from "./TopBar";

const MOBILE_QUERY = "(max-width: 767px)";

/** The orgId of the deepest matched route that has one, so org pages can name their org in the URL. */
function useRouteOrgId(): string | undefined {
  return useRouterState({
    select: (state) => {
      for (const match of [...state.matches].reverse()) {
        const orgId = (match.params as { orgId?: unknown }).orgId;
        if (typeof orgId === "string") return orgId;
      }
      return undefined;
    },
  });
}

/** Chrome for signed-in pages: org navigation beside the content, or a drawer below 768 px. */
export function AppShell({ children }: { children: ReactNode }) {
  const { data: me } = useMe();
  const routeOrgId = useRouteOrgId();
  const isMobile = useMediaQuery(MOBILE_QUERY);
  if (!me) return <ShellSkeleton />;

  const nav = { orgs: me.orgs, currentOrgId: routeOrgId ?? defaultOrgId(me.orgs, lastOrg.get()) };
  return (
    <div className="flex min-h-dvh">
      {!isMobile && (
        <aside className="w-56 shrink-0 border-r border-border">
          <Sidebar {...nav} />
        </aside>
      )}
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar user={me} menu={isMobile ? <MobileDrawer {...nav} /> : undefined} />
        <main className="flex-1 p-4">{children}</main>
      </div>
    </div>
  );
}

/** Loading state of the shell: a skeleton in the shape of the chrome, so the page does not jump when it arrives. */
export function ShellSkeleton() {
  return (
    <div aria-busy="true" className="flex min-h-dvh">
      <p role="status" className="sr-only">Loading EmitKit</p>
      <div className="flex w-56 shrink-0 flex-col gap-3 border-r border-border p-3">
        <Skeleton className="h-8" />
        <Skeleton className="h-8" />
        <Skeleton className="h-8" />
      </div>
      <div className="flex flex-1 flex-col gap-4 p-4">
        <Skeleton className="h-12" />
        <Skeleton className="h-6 w-48" />
      </div>
    </div>
  );
}

/** The shell could not load the session. Retry runs the route's loaders again. */
export function ShellError({ error }: { error: unknown }) {
  const router = useRouter();
  // An AppError keeps its code for ErrorState to show; anything else gets a generic message.
  const feedback: FeedbackError = error instanceof Error ? error : { message: "EmitKit could not load this page." };
  return (
    <div className="flex min-h-dvh items-center justify-center p-4">
      <div className="w-full max-w-md">
        <ErrorState error={feedback} onRetry={() => void router.invalidate()} />
      </div>
    </div>
  );
}
