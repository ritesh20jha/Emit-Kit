import { AlertTriangle } from "lucide-react";
import type { ReactNode } from "react";
import type { Change, OpRef, SpecDiff } from "./api";

interface Row {
  key: string;
  op: OpRef;
  note?: ReactNode;
}

/**
 * What changed between two versions, as a list. Breaking changes come first, then added and removed operations, then
 * the other changes to modified operations. A breaking change is labelled in text as well as in colour.
 */
export function DiffSummary({ diff }: { diff: SpecDiff }) {
  const breaking = changesWhere(diff, true).map(({ op, change }) => ({
    key: changeKey(op, change),
    op,
    note: (
      <>
        <span className="inline-flex items-center gap-1 font-medium text-status-failed">
          <AlertTriangle aria-hidden="true" className="size-4" />
          Breaking
        </span>
        <span className="text-fg-muted">{describe(change)}</span>
      </>
    ),
  }));
  const modified = changesWhere(diff, false).map(({ op, change }) => ({
    key: changeKey(op, change),
    op,
    note: <span className="text-fg-muted">{describe(change)}</span>,
  }));
  const sections: [string, Row[]][] = [
    ["Breaking changes", breaking],
    ["Added", diff.added.map((op) => ({ key: opKey(op), op }))],
    ["Removed", diff.removed.map((op) => ({ key: opKey(op), op }))],
    ["Modified", modified],
  ];

  return (
    <section aria-label="Changes" className="flex flex-col gap-5">
      {sections
        .filter(([, rows]) => rows.length > 0)
        .map(([title, rows]) => (
          <div key={title} className="flex flex-col gap-2">
            <h3 className="text-sm font-medium text-fg">{title}</h3>
            <ul className="divide-y divide-border rounded-card border border-border">
              {rows.map((row) => (
                <li key={row.key} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2 text-sm">
                  <OpName op={row.op} />
                  {row.note}
                </li>
              ))}
            </ul>
          </div>
        ))}
    </section>
  );
}

function changesWhere(diff: SpecDiff, breaking: boolean): { op: OpRef; change: Change }[] {
  return diff.modified.flatMap(({ op, changes }) => changes.filter((change) => change.breaking === breaking).map((change) => ({ op, change })));
}

function OpName({ op }: { op: OpRef }) {
  return (
    <span className="flex flex-wrap items-baseline gap-x-2">
      <code className="font-mono text-fg">
        {op.method.toUpperCase()} {op.path}
      </code>
      <span className="text-fg-muted">{op.operationId}</span>
    </span>
  );
}

function describe({ kind, location }: Change): string {
  return `${kind.toLowerCase().replaceAll("_", " ")} at ${location}`;
}

function opKey(op: OpRef): string {
  return `${op.method}:${op.path}`;
}

function changeKey(op: OpRef, change: Change): string {
  return `${opKey(op)}:${change.kind}:${change.location}`;
}
