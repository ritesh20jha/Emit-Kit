import { useQuery } from "@tanstack/react-query";
import { useBlocker } from "@tanstack/react-router";
import { lazy, Suspense, useRef, useState } from "react";
import { QueryBody } from "@/features/projects";
import { AppError, failureFeedback } from "@/lib/api/appError";
import { ConfirmDialog, Skeleton, toast } from "@/ui";
import { type SaveSpecResponse, type SpecResponse, specQuery, useSaveSpec, useStartRun, useViewRun } from "./api";
import type { CodeEditorHandle } from "./CodeEditor";
import { ConflictDialog } from "./ConflictDialog";
import { ProblemsPanel } from "./ProblemsPanel";
import { useProblems } from "./problems";
import { SpecToolbar } from "./SpecToolbar";

const CodeEditor = lazy(() => import("./CodeEditor"));

export interface SpecEditorProps {
  projectId: string;
  /** Called with a save rejected as SPEC_CONFLICT (409). The editor shows its own conflict dialog as well. */
  onConflict?: (error: AppError) => void;
}

/** The project's spec, edited in place. Loading and failure to load show here; editing is in EditorSession. */
export function SpecEditor({ projectId, onConflict }: SpecEditorProps) {
  const spec = useQuery(specQuery(projectId));
  return (
    <QueryBody query={spec} label="the spec" noAccess="edit this spec">
      {(loaded) => <EditorSession key={projectId} projectId={projectId} loaded={loaded} onConflict={onConflict} />}
    </QueryBody>
  );
}

/**
 * One editing session of a loaded spec. The text and the save base (the sha it was loaded or last saved at) are
 * local, so refetches never overwrite what the user is typing. Keyed by project, so a switch starts a new session.
 */
function EditorSession({ projectId, loaded, onConflict }: { projectId: string; loaded: SpecResponse; onConflict?: (error: AppError) => void }) {
  const [text, setText] = useState(loaded.content);
  const [base, setBase] = useState({ content: loaded.content, sha: loaded.sha });
  // A reload or merge replaces the editor's text. CodeEditor reads its text once, so a new revision remounts it.
  const [revision, setRevision] = useState(0);
  const [conflict, setConflict] = useState<AppError | null>(null);
  const editor = useRef<CodeEditorHandle>(null);
  const dirty = text !== base.content;

  const save = useSaveSpec(projectId);
  const startRun = useStartRun(projectId);
  const viewRun = useViewRun();
  const problems = useProblems(text);
  const blocker = useBlocker({ shouldBlockFn: () => true, disabled: !dirty, withResolver: true });

  async function saveText(generate: boolean) {
    if (!dirty || save.isPending) return;
    const content = text;
    try {
      const saved = await save.mutateAsync({ content, generate, sha: base.sha });
      setBase({ content, sha: saved.newSha });
      reportSaved(saved);
    } catch (error) {
      if (error instanceof AppError && error.status === 409) {
        setConflict(error);
        onConflict?.(error);
      } else toast.error(failureFeedback(error));
    }
  }

  /** The editor now holds `content`, saved at `sha`: that is both its text and its base. */
  function adopt(content: string, sha: string) {
    setText(content);
    setBase({ content, sha });
    setRevision((current) => current + 1);
    setConflict(null);
  }

  /** A saved spec whose run did not start keeps the save; the toast offers the run again. */
  function reportSaved(saved: SaveSpecResponse) {
    if (saved.runError) {
      toast.success("Saved");
      toast.error({ message: saved.runError.detail, code: saved.runError.code }, { label: "Retry run", onClick: () => startRun.mutate(false) });
    } else if (saved.runId) toast.success("Saved and generation started", viewRun(saved.runId));
    else toast.success("Saved");
  }

  return (
    <div className="flex flex-col gap-4">
      <SpecToolbar dirty={dirty} saving={save.isPending} onSave={() => void saveText(false)} onSaveAndGenerate={() => void saveText(true)} onDryRun={() => startRun.isPending || startRun.mutate(true)} />
      <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_18rem]">
        <div className="h-[60vh] min-h-72 overflow-hidden rounded-card border border-border">
          <Suspense fallback={<Skeleton className="h-full" />}>
            <CodeEditor key={revision} ref={editor} initialText={base.content} format={loaded.format} problems={problems.problems} onChange={setText} onSave={() => void saveText(false)} />
          </Suspense>
        </div>
        <ProblemsPanel view={problems} onJump={(line) => editor.current?.jumpTo(line)} />
      </div>
      <ConfirmDialog
        open={blocker.status === "blocked"}
        onOpenChange={(open) => open || blocker.reset?.()}
        title="Leave with unsaved changes?"
        description="Your spec edits have not been saved. Leaving now discards them."
        confirmLabel="Discard changes"
        cancelLabel="Keep editing"
        tone="danger"
        onConfirm={async () => blocker.proceed?.()}
      />
      {conflict && (
        <ConflictDialog
          projectId={projectId}
          conflict={conflict}
          mine={text}
          dirty={dirty}
          onDismiss={() => setConflict(null)}
          onReload={(spec) => adopt(spec.content, spec.sha)}
          onMerged={(content, sha) => {
            adopt(content, sha);
            toast.success("Merged and saved");
          }}
          onConflict={setConflict}
        />
      )}
    </div>
  );
}
