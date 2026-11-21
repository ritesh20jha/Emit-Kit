import { unifiedMergeView } from "@codemirror/merge";
import { EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { useQuery } from "@tanstack/react-query";
import { FileCheck } from "lucide-react";
import { useEffect, useRef } from "react";
import { QueryBody } from "@/features/projects";
import { EmptyState } from "@/ui";
import { fileQuery } from "./api";

/** The two sides a unified diff compares, from its hunks: the original is context and removed lines, the modified side context and added lines. */
export function splitUnifiedDiff(diff: string): { original: string; modified: string } {
  const original: string[] = [];
  const modified: string[] = [];
  let inHunk = false; // The file header (--- and +++) comes before the first hunk.
  for (const line of diff.split("\n")) {
    if (line.startsWith("@@")) inHunk = true;
    else if (!inHunk || line.startsWith("\\")) continue;
    else if (line.startsWith("+")) modified.push(line.slice(1));
    else if (line.startsWith("-")) original.push(line.slice(1));
    else if (line.startsWith(" ")) {
      original.push(line.slice(1));
      modified.push(line.slice(1));
    }
  }
  return { original: original.join("\n"), modified: modified.join("\n") };
}

/** Colours come from the design tokens, so the diff follows light and dark mode. */
const theme = EditorView.theme({
  "&": { color: "var(--color-fg)", backgroundColor: "transparent" },
  ".cm-gutters": { backgroundColor: "var(--color-bg-muted)", color: "var(--color-fg-subtle)", borderRight: "1px solid var(--color-border)" },
});

/** One file's diff against the target branch in CodeMirror's read-only unified merge view. Loaded lazily (see LazyFileDiff). */
export function FileDiff({ runId, path }: { runId: string; path: string }) {
  const file = useQuery(fileQuery(runId, path));
  return (
    <QueryBody query={file} label="the diff" noAccess="view this run">
      {(view) =>
        view.unifiedDiff ? (
          <DiffView path={path} diff={view.unifiedDiff} />
        ) : (
          <EmptyState icon={FileCheck} title={view.status === "SKIPPED" ? "This file was skipped" : "No changes to show"} description="The file matches the target branch, or the run did not write it." />
        )
      }
    </QueryBody>
  );
}

function DiffView({ path, diff }: { path: string; diff: string }) {
  const host = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!host.current) return;
    const { original, modified } = splitUnifiedDiff(diff);
    const extensions = [EditorState.readOnly.of(true), EditorView.editable.of(false), EditorView.lineWrapping, theme];
    const view = new EditorView({
      parent: host.current,
      state: EditorState.create({ doc: modified, extensions: [...extensions, unifiedMergeView({ original, mergeControls: false })] }),
    });
    return () => view.destroy();
  }, [diff]);
  return <div ref={host} role="region" aria-label={`Diff of ${path}`} className="max-h-[70vh] min-w-0 overflow-auto rounded-card border border-border bg-bg-subtle font-mono text-xs" />;
}
