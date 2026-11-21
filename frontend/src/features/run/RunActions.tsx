import { useNavigate } from "@tanstack/react-router";
import { Download } from "lucide-react";
import { useState } from "react";
import { usePermission } from "@/lib/auth/permissions";
import { Button, buttonClasses, ConfirmDialog } from "@/ui";
import { isActive, type RunDetail, useCancelRun, useRerun } from "./api";

/** Cancel, Rerun and Download zip. Each shows only when the run's status and the user's role allow it. */
export function RunActions({ run, orgId }: { run: RunDetail; orgId: string }) {
  const [confirming, setConfirming] = useState(false);
  const cancel = useCancelRun(run.id);
  const rerun = useRerun(run.projectId);
  const navigate = useNavigate();
  const canCancel = usePermission("RUN_CANCEL", orgId) && isActive(run.status);
  const canRerun = usePermission("RUN_RERUN", orgId) && !isActive(run.status);
  const openRun = (next: RunDetail) => void navigate({ to: "/runs/$runId", params: { runId: next.id } });

  return (
    <div className="flex flex-wrap gap-2">
      {canCancel && (
        <Button variant="secondary" onClick={() => setConfirming(true)}>Cancel run</Button>
      )}
      {canRerun && (
        <Button variant="secondary" loading={rerun.isPending} onClick={() => rerun.mutate(run.id, { onSuccess: openRun })}>
          Rerun
        </Button>
      )}
      {run.artifactAvailable && (
        <a href={`/api/v1/runs/${run.id}/artifact`} download className={buttonClasses("secondary", "md")}>
          <Download aria-hidden="true" className="size-3.5" />
          Download zip
        </a>
      )}
      <ConfirmDialog
        open={confirming} onOpenChange={setConfirming} title="Cancel this run?"
        description="The run stops where it is, and its generated branch is removed. A cancelled run cannot be resumed."
        confirmLabel="Yes, cancel run" cancelLabel="Keep running" tone="danger" onConfirm={() => cancel.mutateAsync().then(() => undefined)}
      />
    </div>
  );
}
