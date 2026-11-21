import { createFileRoute } from "@tanstack/react-router";
import { NewProjectFlow, parseNewProjectSearch } from "@/features/new-project";

/** The new-project wizard. Its step and values live in the URL, so Back works and a refresh keeps the step. */
export const Route = createFileRoute("/_app/orgs/$orgId/projects/new")({
  validateSearch: parseNewProjectSearch,
  component: NewProjectPage,
});

function NewProjectPage() {
  const { orgId } = Route.useParams();
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  return (
    <NewProjectFlow
      key={orgId}
      orgId={orgId}
      search={search}
      onSearchChange={(next) => void navigate({ search: next })}
      onCreated={(project) => {
        void navigate({ to: "/projects/$id/config", params: { id: project.id }, search: { welcome: 1 }, replace: true });
      }}
    />
  );
}
