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

/**
 * The router reads each search value as JSON, so a typed `?q=2024` arrives as the number 2024.
 * Text values are read back as text, and empty ones are dropped.
 */
function asText(value: unknown): string | undefined {
  const text = typeof value === "number" ? String(value) : value;
  return typeof text === "string" && text !== "" ? text : undefined;
}

/** Reads the search params. Unknown values are dropped, so a stale link still opens the list. */
export function parseProjectSearch(search: Record<string, unknown>): ProjectSearch {
  const result: ProjectSearch = {};
  const q = asText(search.q);
  if (q !== undefined) result.q = q;
  if (isRunStatus(search.status)) result.status = search.status;
  const cursor = asText(search.cursor);
  if (cursor !== undefined) result.cursor = cursor;
  return result;
}
