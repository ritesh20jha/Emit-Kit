import { useQuery } from "@tanstack/react-query";
import { FolderOpen } from "lucide-react";
import { QueryBody } from "@/features/projects";
import { cn } from "@/lib/utils/cn";
import { EmptyState } from "@/ui";
import { type ManifestEntry, filesQuery } from "./api";

const STATUS: Record<ManifestEntry["status"], { label: string; tone: string }> = {
  ADDED: { label: "Added", tone: "text-status-success" },
  MODIFIED: { label: "Modified", tone: "text-status-awaiting" },
  UNCHANGED: { label: "Unchanged", tone: "text-fg-muted" },
  SKIPPED: { label: "Skipped", tone: "text-fg-muted" },
};

/** A tree line: a folder name, or a file with its manifest entry. */
interface Row {
  key: string;
  name: string;
  depth: number;
  entry?: ManifestEntry;
}

/** The manifest as tree lines: each folder once, before its first file, then the files, indented by depth. */
function toRows(entries: ManifestEntry[]): Row[] {
  const folders = new Set<string>();
  return entries.flatMap((entry) => {
    const parts = entry.path.split("/");
    const folderRows = parts.slice(0, -1).flatMap((name, depth) => {
      const key = parts.slice(0, depth + 1).join("/");
      if (folders.has(key)) return [];
      folders.add(key);
      return [{ key, name: `${name}/`, depth }];
    });
    return [...folderRows, { key: entry.path, name: parts.at(-1) ?? entry.path, depth: parts.length - 1, entry }];
  });
}

export interface FilesTreeProps {
  runId: string;
  /** The file whose diff is open, from ?path=. */
  selected?: string;
  onSelect: (path: string) => void;
}

/** The run's generated files as a tree, each file with its status. */
export function FilesTree({ runId, selected, onSelect }: FilesTreeProps) {
  const files = useQuery(filesQuery(runId));
  return (
    <QueryBody query={files} label="the files" noAccess="view this run">
      {(entries) =>
        entries.length === 0 ? (
          <EmptyState icon={FolderOpen} title="No files" description="This run did not generate any files." />
        ) : (
          <nav aria-label="Generated files" className="min-w-0">
            <ul className="flex flex-col gap-0.5">
              {toRows(entries).map((row) => {
                const indent = { paddingLeft: `${row.depth * 0.75 + 0.5}rem` };
                if (!row.entry) {
                  return <li key={row.key} style={indent} className="py-1 font-mono text-xs text-fg-muted">{row.name}</li>;
                }
                const { label, tone } = STATUS[row.entry.status];
                const isSelected = selected === row.key;
                return (
                  <li key={row.key}>
                    <button
                      type="button"
                      style={indent}
                      aria-current={isSelected ? "true" : undefined}
                      onClick={() => onSelect(row.key)}
                      className={cn("flex w-full items-center gap-2 rounded-control py-1 pr-2 text-left font-mono text-xs text-fg hover:bg-bg-subtle", isSelected && "bg-bg-muted")}
                    >
                      <span className="min-w-0 flex-1 truncate">{row.name}</span>
                      <span className={cn("shrink-0 font-sans", tone)}>{label}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </nav>
        )
      }
    </QueryBody>
  );
}
