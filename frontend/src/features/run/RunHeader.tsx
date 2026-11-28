import type { ReactNode } from "react";
import { formatDuration, TRIGGER_LABEL } from "@/features/project-overview";
import { CopyButton, StatusPill } from "@/ui";
import { RunActions } from "./RunActions";
import type { RunDetail } from "./api";

const Fact = ({ label, children }: { label: string; children: ReactNode }) => (
  <div className="flex min-w-0 flex-col gap-1 bg-surface px-4 py-3"><dt className="font-mono text-2xs font-medium tracking-[0.06em] text-fg-subtle uppercase">{label}</dt><dd className="min-w-0 truncate text-sm text-fg">{children}</dd></div>
);

/** Status, facts and actions. The commit shows its short SHA; its copy button copies the full one. */
export function RunHeader({ run, orgId }: { run: RunDetail; orgId: string }) {
  const { commitSha: sha, startedAt, finishedAt } = run;
  const duration = startedAt && finishedAt ? Date.parse(finishedAt) - Date.parse(startedAt) : null;
  return (
    <header className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <h1 className="min-w-0 text-3xl font-semibold tracking-tight text-fg">
          Run <span className="font-mono text-2xl font-medium break-all text-fg-muted">{run.id}</span>
        </h1>
        <StatusPill status={run.status} />
      </div>
      {/* 1px gaps over the border colour draw the cell rules, so the strip reflows without doubled edges. */}
      <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-card border border-border bg-border shadow-raised sm:grid-cols-3 lg:grid-cols-6">
        <Fact label="Trigger">{TRIGGER_LABEL[run.trigger]}{run.dryRun && " (dry run)"}</Fact>
        <Fact label="Branch"><span className="font-mono">{run.branchName ?? "—"}</span></Fact>
        <Fact label="Commit">{sha ? <span className="inline-flex items-center gap-1 font-mono">{sha.slice(0, 7)}<CopyButton value={sha} label="Copy SHA" /></span> : "—"}</Fact>
        <Fact label="Version"><span className="font-mono">{run.sdkVersion ?? "—"}</span></Fact>
        <Fact label="Duration"><span className="font-mono">{formatDuration(duration)}</span></Fact>
        <Fact label="Pull request">{run.prUrl ? <a href={run.prUrl} target="_blank" rel="noreferrer" className="text-accent underline decoration-accent/40 underline-offset-2 hover:decoration-accent">View pull request</a> : "—"}</Fact>
      </dl>
      {run.failureMessage && (
        <p className="rounded-card border border-status-failed/35 bg-status-failed/8 px-4 py-3 text-sm text-status-failed">{run.failureMessage} {run.failureCode && <code className="font-mono text-xs text-fg-muted">{run.failureCode}</code>}</p>
      )}
      <RunActions run={run} orgId={orgId} />
    </header>
  );
}
