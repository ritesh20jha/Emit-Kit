import { createFileRoute } from "@tanstack/react-router";
import { ConfigForm, parseConfigSearch } from "@/features/config";

/** The project's generation settings. `?welcome=1` comes from the new-project wizard and shows the outputs prompt. */
export const Route = createFileRoute("/_app/projects/$id/config")({
  validateSearch: parseConfigSearch,
  component: ConfigRoute,
});

function ConfigRoute() {
  const { id } = Route.useParams();
  const { welcome } = Route.useSearch();
  return <ConfigForm projectId={id} welcome={welcome === 1} />;
}
