import type { ReactNode } from "react";

export interface DataListProps<T> {
  /** Accessible name of the list. */
  label: string;
  rows: T[];
  rowKey: (row: T) => string;
  /** One card per row. */
  renderCard: (row: T) => ReactNode;
}

/** Card list for narrow screens, the mobile form of a Table. Each row is one list item. */
export function DataList<T>({ label, rows, rowKey, renderCard }: DataListProps<T>) {
  return (
    <ul aria-label={label} className="flex flex-col gap-2">
      {rows.map((row) => (
        <li key={rowKey(row)} className="rounded-card border border-border bg-bg p-3">{renderCard(row)}</li>
      ))}
    </ul>
  );
}
