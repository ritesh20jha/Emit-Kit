import { Download } from "lucide-react";
import { Button, Input, Select } from "@/ui";
import { isLogLevel, isStepName, LEVELS, type LogFilters, STEP_LABELS } from "./logFilters";

/** Radix Select reserves "" for no selection, so "All" is a named value that maps to an empty filter. */
const ALL = "all";

const STEP_OPTIONS = [
  { value: ALL, label: "All steps" },
  ...Object.entries(STEP_LABELS).map(([value, label]) => ({ value, label })),
];
const LEVEL_OPTIONS = [{ value: ALL, label: "All levels" }, ...LEVELS.map((level) => ({ value: level, label: level }))];

export interface LogToolbarProps {
  filters: LogFilters;
  onChange: (next: LogFilters) => void;
  /** Downloads every line of the run, not only the filtered ones. */
  onDownload: () => void;
}

export function LogToolbar({ filters, onChange, onDownload }: LogToolbarProps) {
  return (
    <div role="toolbar" aria-label="Log filters" className="flex flex-wrap items-center gap-2">
      <Select
        aria-label="Step"
        options={STEP_OPTIONS}
        value={filters.step || ALL}
        onValueChange={(value) => onChange({ ...filters, step: isStepName(value) ? value : "" })}
      />
      <Select
        aria-label="Level"
        options={LEVEL_OPTIONS}
        value={filters.level || ALL}
        onValueChange={(value) => onChange({ ...filters, level: isLogLevel(value) ? value : "" })}
      />
      <Input
        type="search"
        aria-label="Search messages"
        placeholder="Search"
        className="w-48"
        value={filters.q}
        onChange={(event) => onChange({ ...filters, q: event.target.value })}
      />
      <Button variant="secondary" size="sm" className="ml-auto" onClick={onDownload}>
        <Download aria-hidden="true" className="size-3.5" />
        Download .txt
      </Button>
    </div>
  );
}
