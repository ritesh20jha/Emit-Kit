import { createFileRoute } from "@tanstack/react-router";
import { parseRunSearch, RunsPage } from "@/features/runs";

/** The project's runs, newest first. Filters and the page cursor live in the URL, so a view can be bookmarked and survives a reload. */
export const Route = createFileRoute("/_app/projects/$id/runs")({
  validateSearch: parseRunSearch,
  component: RunsRoute,
});

function RunsRoute() {
  const { id } = Route.useParams();
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  return <RunsPage projectId={id} search={search} onSearchChange={(next) => void navigate({ search: next })} />;
}
