import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";
import { AppShell, ShellError, ShellSkeleton } from "@/features/shell";
import { AppError } from "@/lib/api/appError";
import { meQuery } from "@/lib/auth/session";

/** Guard for every signed-in page: loads the session first, and sends anyone without one to login. */
export const Route = createFileRoute("/_app")({
  beforeLoad: async ({ context, location }) => {
    try {
      await context.queryClient.ensureQueryData(meQuery);
    } catch (error) {
      if (error instanceof AppError && error.status === 401) {
        throw redirect({ to: "/login", search: { next: location.href } });
      }
      throw error;
    }
  },
  pendingComponent: ShellSkeleton,
  errorComponent: ShellError,
  component: AppLayout,
});

function AppLayout() {
  return (
    <AppShell>
      <Outlet />
    </AppShell>
  );
}
