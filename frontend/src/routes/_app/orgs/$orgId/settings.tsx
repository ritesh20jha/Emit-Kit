import { createFileRoute } from "@tanstack/react-router";
import { OrgSettings } from "@/features/orgs";

/** Org details and the GitHub re-sync. */
export const Route = createFileRoute("/_app/orgs/$orgId/settings")({
  component: SettingsRoute,
});

function SettingsRoute() {
  const { orgId } = Route.useParams();
  return <OrgSettings orgId={orgId} />;
}
