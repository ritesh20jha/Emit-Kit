import { createFileRoute } from "@tanstack/react-router";
import { useRefreshRunOnStep } from "@/features/run";
import { LogView, useRunLogs } from "@/features/run-logs";

/** The run's live log, the default section. Its step, level and search filters live in the URL, so a view can be linked. */
export const Route = createFileRoute("/_app/runs/$runId/")({
  component: LogsRoute,
});

function LogsRoute() {
  const { runId } = Route.useParams();
  const logs = useRunLogs(runId);
  useRefreshRunOnStep(runId, logs.lines);
  return (
    <LogView lines={logs.lines} status={logs.status} filters={logs.filters} onFiltersChange={logs.setFilters} onRetry={logs.retry} />
  );
}
