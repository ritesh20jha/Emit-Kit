import { createFileRoute } from "@tanstack/react-router";
import { ProjectOverview } from "@/features/project-overview";

/** The project's overview: last run, version, spec, health and recent activity. */
export const Route = createFileRoute("/_app/projects/$id/")({
  component: OverviewRoute,
});

function OverviewRoute() {
  const { id } = Route.useParams();
  return <ProjectOverview projectId={id} />;
}
