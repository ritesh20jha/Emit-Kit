import { createFileRoute } from "@tanstack/react-router";
import { MembersPage } from "@/features/members";

/** Members of an org. Everyone reads the list; admins change roles inline. */
export const Route = createFileRoute("/_app/orgs/$orgId/members")({
  component: MembersRoute,
});

function MembersRoute() {
  const { orgId } = Route.useParams();
  return <MembersPage orgId={orgId} />;
}
