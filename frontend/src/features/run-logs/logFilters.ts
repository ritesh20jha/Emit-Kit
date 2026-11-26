import type { components } from "@/lib/api/generated/schema";

export type LogLine = components["schemas"]["LogLine"];
export type StepName = components["schemas"]["StepName"];
export type LogLevel = "INFO" | "WARN" | "ERROR";

export interface LogFilters {
  step: StepName | "";
  level: LogLevel | "";
  /** Case-insensitive text to find in the message. */
  q: string;
}

export const NO_FILTERS: LogFilters = { step: "", level: "", q: "" };

/** Every step in pipeline order, with the label people read. The Record keeps it in step with the contract. */
export const STEP_LABELS: Record<StepName, string> = {
  FETCH_SPEC: "Fetch spec",
  PARSE_SPEC: "Parse spec",
  LINT_SPEC: "Lint spec",
  DIFF_SPEC: "Diff spec",
  CALC_VERSION: "Calculate version",
  BREAKING_GATE: "Breaking-change gate",
  RUN_GENERATORS: "Run generators",
  SYNTAX_CHECK: "Syntax check",
  PACKAGE_ARTIFACT: "Package artifact",
  COMMIT_OUTPUT: "Commit output",
  FINALIZE: "Finalize",
};

export const LEVELS: readonly LogLevel[] = ["INFO", "WARN", "ERROR"];

export function isStepName(value: string): value is StepName {
  return Object.hasOwn(STEP_LABELS, value);
}

export function isLogLevel(value: string): value is LogLevel {
  return (LEVELS as readonly string[]).includes(value);
}

/** The URL search params that hold the filters. Other params on the page are left alone. */
export const FILTER_PARAMS = ["step", "level", "q"] as const;

/**
 * Reads the filters from the raw query string. Values are read as text, so `?q=404` is the search "404", not a number.
 * Unknown step or level values are ignored, so a stale link never breaks the view.
 */
export function parseFilters(params: URLSearchParams): LogFilters {
  const step = params.get("step") ?? "";
  const level = params.get("level") ?? "";
  return {
    step: isStepName(step) ? step : "",
    level: isLogLevel(level) ? level : "",
    q: params.get("q") ?? "",
  };
}

/**
 * One pass over the lines. With no filter the input array itself comes back, so an unfiltered log costs nothing here.
 */
export function filterLines(lines: LogLine[], { step, level, q }: LogFilters): LogLine[] {
  const needle = q.trim().toLowerCase();
  if (!step && !level && !needle) return lines;
  return lines.filter(
    (line) =>
      (!step || line.step === step) &&
      (!level || line.level === level) &&
      (!needle || line.message.toLowerCase().includes(needle)),
  );
}
