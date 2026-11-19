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
        // The config page arrives with WEB-10, so the typed router does not know this path yet.
        void navigate({ to: `/projects/${project.id}/config` as "/", search: { welcome: 1 } });
      }}
    />
  );
}
