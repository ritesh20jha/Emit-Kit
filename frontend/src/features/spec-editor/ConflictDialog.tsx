import { useQuery, useQueryClient } from "@tanstack/react-query";
import { lazy, Suspense, useState } from "react";
import { QueryBody } from "@/features/projects";
import { AppError, failureFeedback } from "@/lib/api/appError";
import { Button, ConfirmDialog, Dialog, Skeleton, toast } from "@/ui";
import { type SpecResponse, currentSpecQuery, useSaveSpec } from "./api";

const MergeView = lazy(() => import("./MergeView"));

export interface ConflictDialogProps {
  projectId: string;
  /** The rejected save. Its `extra.currentSha` is the sha the server has now. */
  conflict: AppError;
  /** The editor's text, which is "mine" in a merge. */
  mine: string;
  /** Whether the editor has unsaved edits. Reload asks first when it has. */
  dirty: boolean;
  onDismiss: () => void;
  /** Loads the server's spec into the editor in place of the user's text. */
  onReload: (spec: SpecResponse) => void;
  /** The merged text was saved at `sha`, so it is the editor's new base. */
  onMerged: (content: string, sha: string) => void;
  /** A merged save met another conflict. The dialog stays open against the new sha. */
  onConflict: (error: AppError) => void;
}

/**
 * Shown when a save is rejected as SPEC_CONFLICT. It offers the server's version (Reload) or a side-by-side merge
 * (Compare). The server's spec is read as soon as the conflict arrives, so Compare has it ready.
 */
export function ConflictDialog({ projectId, conflict, mine, dirty, onDismiss, onReload, onMerged, onConflict }: ConflictDialogProps) {
  const queryClient = useQueryClient();
  const sha = typeof conflict.extra.currentSha === "string" ? conflict.extra.currentSha : "";
  const current = useQuery(currentSpecQuery(projectId, sha));
  const save = useSaveSpec(projectId);
  const [comparing, setComparing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [merged, setMerged] = useState(mine);
  // The sha whose merge view is on screen. Save merged waits for it, so nothing is saved before the server's text shows.
  const [readySha, setReadySha] = useState<string | null>(null);

  /** Reads the server's spec fresh, then hands it over. A failure rejects, so the caller can show it. */
  async function loadServer() {
    onReload(await queryClient.fetchQuery(currentSpecQuery(projectId, sha)));
  }

  function reload() {
    if (dirty) setConfirming(true);
    else loadServer().catch((error: unknown) => toast.error(failureFeedback(error)));
  }

  async function saveMerged() {
    try {
      const saved = await save.mutateAsync({ content: merged, generate: false, sha });
      onMerged(merged, saved.newSha);
    } catch (error) {
      // A 409 again means the server moved on. Keep the merge and compare against its new sha.
      if (error instanceof AppError && error.status === 409) onConflict(error);
      else toast.error(failureFeedback(error));
    }
  }

  return (
    <>
      <Dialog
        open={!confirming}
        onOpenChange={(open) => !open && onDismiss()}
        wide={comparing}
        title="Someone else changed this file."
        description="Your edits are not saved. Reload to take the saved version, or compare the two and save a merge."
        footer={
          <>
            <Button variant="secondary" size="sm" onClick={reload}>
              Reload
            </Button>
            {comparing ? (
              <Button size="sm" loading={save.isPending} disabled={!current.data || readySha !== sha} onClick={() => void saveMerged()}>
                Save merged
              </Button>
            ) : (
              <Button variant="secondary" size="sm" onClick={() => setComparing(true)}>
                Compare
              </Button>
            )}
          </>
        }
      >
        {comparing && (
          <div data-testid="merge-view" className="h-[60vh] min-h-72 overflow-hidden rounded-card border border-border">
            <QueryBody query={current} label="the saved spec" noAccess="edit this spec">
              {(saved) => (
                <Suspense fallback={<Skeleton className="h-full" />}>
                  <MergeView key={sha} theirs={saved.content} mine={merged} onMineChange={setMerged} onReady={() => setReadySha(sha)} />
                </Suspense>
              )}
            </QueryBody>
          </div>
        )}
      </Dialog>
      <ConfirmDialog
        open={confirming}
        onOpenChange={(open) => !open && setConfirming(false)}
        title="Discard your edits?"
        description="Reloading replaces your edits with the saved version. They cannot be recovered."
        confirmLabel="Discard edits"
        cancelLabel="Keep editing"
        tone="danger"
        onConfirm={loadServer}
      />
    </>
  );
}
