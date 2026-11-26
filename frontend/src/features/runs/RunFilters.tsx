import { TRIGGER_LABEL } from "@/features/project-overview";
import { Select, type SelectOption } from "@/ui";
import { RUN_STATUSES, RUN_TRIGGERS, type RunSearch, type RunStatus } from "./schemas";

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

const STATUS_OPTIONS: SelectOption[] = [
  { value: ALL, label: "All statuses" },
  ...RUN_STATUSES.map((status) => ({ value: status, label: STATUS_LABELS[status] })),
];

const TRIGGER_OPTIONS: SelectOption[] = [
  { value: ALL, label: "All triggers" },
  ...RUN_TRIGGERS.map((trigger) => ({ value: trigger, label: TRIGGER_LABEL[trigger] })),
];

export interface RunFiltersProps {
  search: RunSearch;
  /** Called with the whole next search. A changed filter drops the cursor, since it belongs to the old list. */
  onChange: (next: RunSearch) => void;
}

/** The status and trigger filters. Their values live only in the URL. */
export function RunFilters({ search, onChange }: RunFiltersProps) {
  return (
    <div role="group" aria-label="Run filters" className="flex flex-wrap items-center gap-2">
      <Select
        aria-label="Run status"
        options={STATUS_OPTIONS}
        value={search.status ?? ALL}
        onValueChange={(value) => onChange({ ...search, status: RUN_STATUSES.find((status) => status === value), cursor: undefined })}
      />
      <Select
        aria-label="Run trigger"
        options={TRIGGER_OPTIONS}
        value={search.trigger ?? ALL}
        onValueChange={(value) => onChange({ ...search, trigger: RUN_TRIGGERS.find((trigger) => trigger === value), cursor: undefined })}
      />
    </div>
  );
}
