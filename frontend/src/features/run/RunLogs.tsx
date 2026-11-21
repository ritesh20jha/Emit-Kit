import { LogView, useRunLogs } from "@/features/run-logs";
import { useRefreshRunOnStep } from "./api";

/** The live log of one run: the default view of the run page, at `/runs/$runId` and `/runs/$runId/logs`. */
export function RunLogs({ runId }: { runId: string }) {
  const logs = useRunLogs(runId);
  useRefreshRunOnStep(runId, logs.lines);
  return <LogView lines={logs.lines} status={logs.status} filters={logs.filters} onFiltersChange={logs.setFilters} onRetry={logs.retry} />;
}
