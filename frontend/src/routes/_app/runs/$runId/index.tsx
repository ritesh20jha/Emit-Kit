import { createFileRoute } from "@tanstack/react-router";
import { RunLogs } from "@/features/run";

/** `/runs/$runId` shows the log too, so a bare run link works. The same view is at `/logs`. */
export const Route = createFileRoute("/_app/runs/$runId/")({
  component: IndexRoute,
});

function IndexRoute() {
  const { runId } = Route.useParams();
  return <RunLogs runId={runId} />;
}
