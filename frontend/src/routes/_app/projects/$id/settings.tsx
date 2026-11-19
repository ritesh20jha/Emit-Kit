import { createFileRoute } from "@tanstack/react-router";
import { ProjectSettings } from "@/features/project-settings";

/** The project's webhook, output mode and the delete action. */
export const Route = createFileRoute("/_app/projects/$id/settings")({
  component: SettingsRoute,
});

function SettingsRoute() {
  const { id } = Route.useParams();
  return <ProjectSettings projectId={id} />;
}
