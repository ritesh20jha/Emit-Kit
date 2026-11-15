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
    <ProjectsList
      orgId={orgId}
      search={search}
      onSearchChange={(next, { replace }) => void navigate({ search: next, replace })}
    />
  );
}
