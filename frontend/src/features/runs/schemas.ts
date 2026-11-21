import type { components } from "@/lib/api/generated/schema";

export type RunStatus = components["schemas"]["RunStatus"];
export type RunTrigger = components["schemas"]["RunTrigger"];

/** Every run status, in the order the status filter lists them. */
export const RUN_STATUSES: readonly RunStatus[] = ["QUEUED", "RUNNING", "AWAITING_APPROVAL", "SUCCESS", "FAILED", "CANCELLED"];

/** Every trigger, in the order the trigger filter lists them. */
export const RUN_TRIGGERS: readonly RunTrigger[] = ["MANUAL", "WEBHOOK", "RERUN"];

/** The runs list's URL search params. An absent key means no filter, or the first page. */
export interface RunSearch {
  status?: RunStatus;
  trigger?: RunTrigger;
  cursor?: string;
}

/** The value of `search` when it is one of `options`, else undefined. */
function oneOf<T extends string>(options: readonly T[], value: unknown): T | undefined {
  return options.find((option) => option === value);
}

/**
 * The router reads each search value as JSON, so a typed `?cursor=2024` arrives as the number 2024.
 * Text values are read back as text, and empty ones are dropped.
 */
function asText(value: unknown): string | undefined {
  const text = typeof value === "number" ? String(value) : value;
  return typeof text === "string" && text !== "" ? text : undefined;
}

/**
 * Reads the search params. Unknown filter values come back as undefined, so a stale link still opens the list.
 * Every key is returned, even when empty: the router merges this result over the raw URL search, and a key left
 * out would keep its invalid value.
 */
export function parseRunSearch(search: Record<string, unknown>): RunSearch {
  return {
    status: oneOf(RUN_STATUSES, search.status),
    trigger: oneOf(RUN_TRIGGERS, search.trigger),
    cursor: asText(search.cursor),
  };
}
