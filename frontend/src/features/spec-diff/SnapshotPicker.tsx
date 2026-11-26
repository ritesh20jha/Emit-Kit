import { formatDate } from "@/lib/utils/dates";
import { Select, type SelectOption } from "@/ui";
import type { SnapshotSummary } from "./api";

export interface SnapshotPickerProps {
  /** Newest first. */
  snapshots: SnapshotSummary[];
  from: string;
  to: string;
  onChange: (next: { from: string; to: string }) => void;
}

/** Two selects for the versions compared: the older (from) and the newer (to). */
export function SnapshotPicker({ snapshots, from, to, onChange }: SnapshotPickerProps) {
  const options: SelectOption[] = snapshots.map((snapshot) => ({ value: snapshot.id, label: snapshotLabel(snapshot) }));
  return (
    <div className="flex flex-wrap items-center gap-3 text-sm">
      <span className="text-fg-muted">From</span>
      <Select aria-label="Older version" options={options} value={from} onValueChange={(value) => onChange({ from: value, to })} className="min-w-56" />
      <span className="text-fg-muted">to</span>
      <Select aria-label="Newer version" options={options} value={to} onValueChange={(value) => onChange({ from, to: value })} className="min-w-56" />
    </div>
  );
}

/** A version by its date, its info version when it has one, and the start of its content hash. */
function snapshotLabel({ createdAt, infoVersion, contentHash }: SnapshotSummary): string {
  const version = infoVersion ? ` · v${infoVersion}` : "";
  return `${formatDate(createdAt)}${version} · ${contentHash.slice(0, 7)}`;
}
