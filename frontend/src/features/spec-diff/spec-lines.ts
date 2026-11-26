import type { OpRef, SpecDiff } from "./api";

/** One line of a diff: the old text and the new text on this row, or null where a side has no line. */
export interface DiffRow {
  old: string | null;
  new: string | null;
  /** The same line on both sides, so it is context, not a change. */
  same: boolean;
}

/** An operation as one line, such as `POST /invoices (createInvoice)`. */
export function opLine({ method, path, operationId }: OpRef): string {
  return `${method.toUpperCase()} ${path} (${operationId})`;
}

/**
 * Two texts for a diff of the spec. The server returns operations and changes, not the spec's text, so each side is
 * built from them. Only removed operations are on the old side alone and only added operations on the new side alone.
 * A modified operation and its change lines are on both sides, so they read as context, not as a removal or addition.
 */
export function summaryTexts(diff: SpecDiff): { oldText: string; newText: string } {
  const modifiedLines = diff.modified.flatMap(({ op, changes }) => [opLine(op), ...changes.map((change) => `  ${change.kind} at ${change.location}`)]);
  const oldLines = [...diff.removed.map(opLine), ...modifiedLines];
  const newLines = [...modifiedLines, ...diff.added.map(opLine)];
  return { oldText: oldLines.join("\n"), newText: newLines.join("\n") };
}

/**
 * A line diff by longest common subsequence. Between two matching lines, removed and added lines pair up row by row,
 * so a change reads across the two sides.
 */
export function lineDiff(oldLines: string[], newLines: string[]): DiffRow[] {
  const n = oldLines.length;
  const m = newLines.length;
  // lcs[i][j] is the length of the longest common subsequence of oldLines from i and newLines from j.
  const lcs = Array.from({ length: n + 1 }, () => Array.from({ length: m + 1 }, () => 0));
  const at = (i: number, j: number): number => lcs[i]?.[j] ?? 0;
  for (let i = n - 1; i >= 0; i--) {
    const row = lcs[i];
    for (let j = m - 1; j >= 0; j--) {
      if (row) row[j] = oldLines[i] === newLines[j] ? at(i + 1, j + 1) + 1 : Math.max(at(i + 1, j), at(i, j + 1));
    }
  }

  const rows: DiffRow[] = [];
  let removed: string[] = [];
  let added: string[] = [];
  const flush = () => {
    const count = Math.max(removed.length, added.length);
    for (let k = 0; k < count; k++) rows.push({ old: removed[k] ?? null, new: added[k] ?? null, same: false });
    removed = [];
    added = [];
  };

  let i = 0;
  let j = 0;
  while (i < n || j < m) {
    const oldLine = oldLines[i];
    const newLine = newLines[j];
    if (oldLine !== undefined && newLine !== undefined && oldLine === newLine) {
      flush();
      rows.push({ old: oldLine, new: newLine, same: true });
      i++;
      j++;
    } else if (newLine !== undefined && (oldLine === undefined || at(i, j + 1) >= at(i + 1, j))) {
      added.push(newLine);
      j++;
    } else if (oldLine !== undefined) {
      removed.push(oldLine);
      i++;
    }
  }
  flush();
  return rows;
}
