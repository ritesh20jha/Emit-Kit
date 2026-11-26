import { Fragment, useMemo } from "react";
import { cn } from "@/lib/utils/cn";
import { type DiffRow, lineDiff } from "./spec-lines";

export interface TextDiffProps {
  oldText: string;
  newText: string;
}

/** A line diff of two texts, side by side from 768 px up and in one column below it. Changes carry a sign and a label, not colour alone. */
export function TextDiff({ oldText, newText }: TextDiffProps) {
  const rows = useMemo(() => lineDiff(linesOf(oldText), linesOf(newText)), [oldText, newText]);
  return (
    <div role="group" aria-label="Changed lines, old and new" className="grid grid-cols-1 overflow-x-auto rounded-card border border-border font-mono text-xs md:grid-cols-2">
      {rows.map((row, index) => (
        <Fragment key={index}>
          <Cell side="old" row={row} />
          <Cell side="new" row={row} />
        </Fragment>
      ))}
    </div>
  );
}

function linesOf(text: string): string[] {
  return text === "" ? [] : text.split("\n");
}

function Cell({ side, row }: { side: "old" | "new"; row: DiffRow }) {
  const text = row[side];
  // A matching line shows once, on the old side. An empty side keeps its place on the wide layout only.
  const hidden = text === null || (row.same && side === "new");
  const sign = row.same || text === null ? " " : side === "old" ? "-" : "+";
  const label = row.same || text === null ? "" : side === "old" ? "Removed: " : "Added: ";
  return (
    <div className={cn("flex min-h-5 gap-2 px-3", hidden ? "hidden md:flex" : "flex", !row.same && text !== null && (side === "old" ? "bg-status-failed/10" : "bg-status-success/10"))}>
      <span aria-hidden="true" className="select-none">
        {sign}
      </span>
      <span className="sr-only">{label}</span>
      <span className="whitespace-pre">{text ?? ""}</span>
    </div>
  );
}
