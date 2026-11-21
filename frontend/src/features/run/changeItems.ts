import type { components } from "@/lib/api/generated/schema";

type SpecDiff = components["schemas"]["SpecDiff"];
type OpRef = components["schemas"]["OpRef"];

export interface ChangeItem {
  key: string;
  breaking: boolean;
  label: string;
  title: string;
  detail: string;
}

const operation = (op: OpRef) => `${op.method} ${op.path}`;

/**
 * The diff as one list, breaking changes first: removed operations, breaking changes, the other changes, then added
 * operations. The backend counts a removed operation as breaking.
 */
export function changeItems(diff: SpecDiff | null): ChangeItem[] {
  if (!diff) return [];
  const removed = diff.removed.map((op, i) => ({ key: `removed-${i}`, breaking: true, label: "Removed operation", title: operation(op), detail: "" }));
  const changes = diff.modified.flatMap((entry, i) =>
    entry.changes.map((change, j) => ({
      key: `changed-${i}-${j}`,
      breaking: change.breaking,
      label: change.breaking ? "Breaking change" : "Change",
      title: operation(entry.op),
      detail: `${change.kind.toLowerCase().replaceAll("_", " ")} at ${change.location}`,
    })),
  );
  const added = diff.added.map((op, i) => ({ key: `added-${i}`, breaking: false, label: "Added operation", title: operation(op), detail: "" }));
  return [...removed, ...changes.filter((item) => item.breaking), ...changes.filter((item) => !item.breaking), ...added];
}

/** The breaking items of a diff, in list order. */
export const breakingChanges = (diff: SpecDiff | null): ChangeItem[] => changeItems(diff).filter((item) => item.breaking);
