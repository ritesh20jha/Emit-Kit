import { useEffect, useEffectEvent, useRef, useState } from "react";
import { Input, Select } from "@/ui";
import { isRunStatus, RUN_STATUSES, type RunStatus } from "./schemas";

/** Quiet time after the last keystroke before the search is written to the URL. */
export const SEARCH_DEBOUNCE_MS = 300;

/** Radix Select reserves "" for no selection, so "All" is a named value that maps to no filter. */
const ALL = "all";

const STATUS_LABELS: Record<RunStatus, string> = {
  QUEUED: "Queued",
  RUNNING: "Running",
  AWAITING_APPROVAL: "Awaiting approval",
  SUCCESS: "Succeeded",
  FAILED: "Failed",
  CANCELLED: "Cancelled",
};

const STATUS_OPTIONS = [
  { value: ALL, label: "All statuses" },
  ...RUN_STATUSES.map((status) => ({ value: status, label: STATUS_LABELS[status] })),
];

export interface ProjectFiltersProps {
  q: string;
  status?: RunStatus;
  /** Called once typing has paused for SEARCH_DEBOUNCE_MS. An empty string means no search. */
  onQueryCommit: (q: string) => void;
  onStatusChange: (status: RunStatus | undefined) => void;
}

export function ProjectFilters({ q, status, onQueryCommit, onStatusChange }: ProjectFiltersProps) {
  const [text, setText] = useState(q);
  // The query the URL is known to hold. The box's own writes match it, so they never overwrite what is being typed.
  const known = useRef(q);

  // A query changed from elsewhere (Back, a shared link) replaces the box's text.
  useEffect(() => {
    if (q === known.current) return;
    known.current = q;
    setText(q);
  }, [q]);

  // Reads the latest callback without restarting the timer when the parent re-renders.
  const commit = useEffectEvent(onQueryCommit);
  useEffect(() => {
    if (text === known.current) return;
    const timer = window.setTimeout(() => {
      known.current = text;
      commit(text);
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [text]);

  return (
    <div role="group" aria-label="Project filters" className="flex flex-wrap items-center gap-2">
      <Input
        type="search"
        aria-label="Search projects"
        placeholder="Search repositories"
        className="w-56"
        value={text}
        onChange={(event) => setText(event.target.value)}
      />
      <Select
        aria-label="Last run status"
        options={STATUS_OPTIONS}
        value={status ?? ALL}
        onValueChange={(value) => onStatusChange(isRunStatus(value) ? value : undefined)}
      />
    </div>
  );
}
