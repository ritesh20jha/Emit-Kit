import { createFileRoute } from "@tanstack/react-router";
import { RunLogs } from "@/features/run";

/** The run's live log, the default section. Its step, level and search filters live in the URL, so a view can be linked. */
export const Route = createFileRoute("/_app/runs/$runId/logs")({
  component: LogsRoute,
});

function LogsRoute() {
  const { runId } = Route.useParams();
  return <RunLogs runId={runId} />;
}
