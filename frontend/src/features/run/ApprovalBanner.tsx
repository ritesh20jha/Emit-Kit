import { useState } from "react";
import { usePermission } from "@/lib/auth/permissions";
import { Button, Label, Textarea } from "@/ui";
import { breakingChanges } from "./changeItems";
import { type RunDetail, useDecideApproval } from "./api";

/** Shown while the run waits for approval, with the breaking changes it carries. Admins decide; others are told to wait. */
export function ApprovalBanner({ run, orgId }: { run: RunDetail; orgId: string }) {
  const canApprove = usePermission("RUN_APPROVE", orgId);
  const [comment, setComment] = useState("");
  const decide = useDecideApproval(run.id);
  if (run.status !== "AWAITING_APPROVAL") return null;

  const breaking = breakingChanges(run.diffSummary);
  return (
    <section aria-labelledby="approval-heading" className="flex flex-col gap-3 rounded-card border border-status-awaiting/40 bg-bg-subtle p-4">
      <h2 id="approval-heading" className="text-base font-medium text-fg">Approval needed</h2>
      {breaking.length > 0 ? (
        <ul className="flex list-disc flex-col gap-1 pl-5 text-sm text-fg">
          {breaking.map((item) => (
            <li key={item.key}>{item.label}: <code className="font-mono text-xs">{item.title}</code>{item.detail && <span className="text-fg-muted"> ({item.detail})</span>}</li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-fg-muted">This run is paused until an admin approves it.</p>
      )}
      {canApprove ? (
        <div className="flex flex-col gap-2">
          <Label htmlFor="approval-comment">Comment (optional)</Label>
          <Textarea id="approval-comment" maxLength={2000} value={comment} onChange={(event) => setComment(event.target.value)} />
          <div className="flex flex-wrap gap-2">
            <Button loading={decide.isPending} onClick={() => decide.mutate({ decision: "APPROVED", comment })}>Approve</Button>
            <Button variant="secondary" loading={decide.isPending} onClick={() => decide.mutate({ decision: "REJECTED", comment })}>Reject</Button>
          </div>
        </div>
      ) : (
        <p className="text-sm text-fg-muted">Waiting for an admin to approve.</p>
      )}
    </section>
  );
}
