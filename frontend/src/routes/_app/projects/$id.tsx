import { createFileRoute, Outlet } from "@tanstack/react-router";
import { ProjectLayout } from "@/features/project-overview";

/** One project. Its header and section tabs are shared; each section is a child route with its own URL. */
export const Route = createFileRoute("/_app/projects/$id")({
  component: ProjectRoute,
});

function ProjectRoute() {
  const { id } = Route.useParams();
  return (
    <ProjectLayout projectId={id}>
      <Outlet />
    </ProjectLayout>
  );
}
