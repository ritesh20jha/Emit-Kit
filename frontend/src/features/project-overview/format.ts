import type { components } from "@/lib/api/generated/schema";

type RunStatus = components["schemas"]["RunStatus"];
type RunTrigger = components["schemas"]["RunTrigger"];

export const TRIGGER_LABEL: Record<RunTrigger, string> = { MANUAL: "Manual", WEBHOOK: "Webhook", RERUN: "Rerun" };

const UNFINISHED: ReadonlySet<RunStatus> = new Set(["QUEUED", "RUNNING", "AWAITING_APPROVAL"]);

/** Whether a run has ended, so it can be rerun. */
export function isFinished(status: RunStatus): boolean {
  return !UNFINISHED.has(status);
}

/** A run's length: "45s", "1m 12s", "2h 3m". A run that has not finished has none, shown as a dash. */
export function formatDuration(ms: number | null): string {
  if (ms === null) return "—";
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ${seconds % 60}s`;
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

/** The run page's route. It arrives with WEB-13, so the typed router does not know this path yet. */
export const runPath = (runId: string) => `/runs/${runId}` as "/";
