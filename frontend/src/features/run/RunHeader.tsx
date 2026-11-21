import type { ReactNode } from "react";
import { formatDuration, TRIGGER_LABEL } from "@/features/project-overview";
import { CopyButton, StatusPill } from "@/ui";
import { RunActions } from "./RunActions";
import type { RunDetail } from "./api";

const Fact = ({ label, children }: { label: string; children: ReactNode }) => (
  <div className="flex flex-col gap-0.5">
    <dt className="text-xs text-fg-muted">{label}</dt>
    <dd className="text-sm text-fg">{children}</dd>
  </div>
);

/** Status, facts and actions. The commit shows its short SHA; its copy button copies the full one. */
export function RunHeader({ run, orgId }: { run: RunDetail; orgId: string }) {
  const { commitSha: sha, startedAt, finishedAt } = run;
  const duration = startedAt && finishedAt ? Date.parse(finishedAt) - Date.parse(startedAt) : null;
  return (
    <header className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="min-w-0 break-all font-mono text-xl font-semibold text-fg">Run {run.id}</h1>
        <StatusPill status={run.status} />
      </div>
      <dl className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3 lg:grid-cols-6">
        <Fact label="Trigger">{TRIGGER_LABEL[run.trigger]}{run.dryRun && " (dry run)"}</Fact>
        <Fact label="Branch"><span className="font-mono">{run.branchName ?? "—"}</span></Fact>
        <Fact label="Commit">{sha ? <span className="inline-flex items-center gap-1 font-mono">{sha.slice(0, 7)}<CopyButton value={sha} label="Copy SHA" /></span> : "—"}</Fact>
        <Fact label="Version"><span className="font-mono">{run.sdkVersion ?? "—"}</span></Fact>
        <Fact label="Duration"><span className="font-mono">{formatDuration(duration)}</span></Fact>
        <Fact label="Pull request">{run.prUrl ? <a href={run.prUrl} target="_blank" rel="noreferrer" className="text-accent underline">View pull request</a> : "—"}</Fact>
      </dl>
      {run.failureMessage && (
        <p className="text-sm text-status-failed">{run.failureMessage} {run.failureCode && <code className="font-mono text-xs text-fg-muted">{run.failureCode}</code>}</p>
      )}
      <RunActions run={run} orgId={orgId} />
    </header>
  );
}
