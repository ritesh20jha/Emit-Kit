import { createFileRoute } from "@tanstack/react-router";
import { SpecEditor } from "@/features/spec-editor";

/** The project's spec editor: the spec with inline problems, save, save & generate and dry run. */
export const Route = createFileRoute("/_app/projects/$id/editor")({
  component: EditorRoute,
});

function EditorRoute() {
  const { id } = Route.useParams();
  return <SpecEditor projectId={id} />;
}
