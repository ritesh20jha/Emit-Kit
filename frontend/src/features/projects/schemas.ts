import type { components } from "@/lib/api/generated/schema";

export type RunStatus = components["schemas"]["RunStatus"];

/** Every run status, in the order the status filter lists them. */
export const RUN_STATUSES: readonly RunStatus[] = [
  "QUEUED",
  "RUNNING",
  "AWAITING_APPROVAL",
  "SUCCESS",
  "FAILED",
  "CANCELLED",
];

/** The projects list's URL search params. An absent key means no filter, or the first page. */
export interface ProjectSearch {
  q?: string;
  /** Only projects whose last run has this status. */
  status?: RunStatus;
  cursor?: string;
}

export function isRunStatus(value: unknown): value is RunStatus {
  return typeof value === "string" && (RUN_STATUSES as readonly string[]).includes(value);
}

/** Reads the search params. Empty or unknown values are dropped, so a stale link still opens the list. */
export function parseProjectSearch(search: Record<string, unknown>): ProjectSearch {
  const result: ProjectSearch = {};
  if (typeof search.q === "string" && search.q !== "") result.q = search.q;
  if (isRunStatus(search.status)) result.status = search.status;
  if (typeof search.cursor === "string" && search.cursor !== "") result.cursor = search.cursor;
  return result;
}
