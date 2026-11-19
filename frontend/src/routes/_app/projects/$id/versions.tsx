import { createFileRoute } from "@tanstack/react-router";
import { VersionsList } from "@/features/versions";

/** The project's version history, newest first. */
export const Route = createFileRoute("/_app/projects/$id/versions")({
  component: VersionsRoute,
});

function VersionsRoute() {
  const { id } = Route.useParams();
  return <VersionsList projectId={id} />;
}
