import { createFileRoute } from "@tanstack/react-router";
import { parseProjectSearch, ProjectsList } from "@/features/projects";

/** The org's projects. Search, the status filter and the page cursor live in the URL, so a view can be bookmarked and survives a reload. */
export const Route = createFileRoute("/_app/orgs/$orgId/")({
  validateSearch: parseProjectSearch,
  component: OrgProjectsPage,
});

function OrgProjectsPage() {
  const { orgId } = Route.useParams();
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  return (
    // Keyed by org: a switch changes only the params, and a pending search must not carry into the new org.
    <ProjectsList
      key={orgId}
      orgId={orgId}
      search={search}
      onSearchChange={(next, { replace }) => void navigate({ search: next, replace })}
    />
  );
}
