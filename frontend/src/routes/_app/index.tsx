import { createFileRoute, redirect } from "@tanstack/react-router";
import { Building } from "lucide-react";
import { defaultOrgId, lastOrg } from "@/features/shell";
import { meQuery } from "@/lib/auth/session";
import { EmptyState } from "@/ui";

/** `/` opens the last-used org, or the personal org when the remembered one is gone. */
export const Route = createFileRoute("/_app/")({
  beforeLoad: async ({ context }) => {
    const me = await context.queryClient.ensureQueryData(meQuery);
    const orgId = defaultOrgId(me.orgs, lastOrg.get());
    // The org page route is added by WEB-04/05, so the target is a plain href.
    if (orgId !== undefined) throw redirect({ href: `/orgs/${orgId}` });
  },
  component: NoOrgs,
});

function NoOrgs() {
  return (
    <EmptyState
      icon={Building}
      title="No organizations yet"
      description="Your GitHub organizations appear here once they are synced to EmitKit."
    />
  );
}
