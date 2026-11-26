import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { type ProjectDetail, QueryBody, Panel } from "@/features/projects";
import { usePermission } from "@/lib/auth/permissions";
import { relativeTime } from "@/lib/utils/dates";
import { Button, buttonClasses, StatusPill } from "@/ui";
import { type RunDetail, type RunSummary, recentRunsQuery, useRerun, useTriggerRun } from "./api";
import { formatDuration, isFinished, runPath, TRIGGER_LABEL } from "./format";

/** The newest run, with Trigger run and Rerun. Each button shows only when the user may use it. */
export function LastRunCard({ project }: { project: ProjectDetail }) {
  const runs = useQuery(recentRunsQuery(project.id));
  const canTrigger = usePermission("RUN_TRIGGER", project.orgId);
  const canRerun = usePermission("RUN_RERUN", project.orgId);
  const trigger = useTriggerRun(project.id);
  const rerun = useRerun(project.id);
  const navigate = useNavigate();
  const openRun = (run: RunDetail) => void navigate({ to: runPath(run.id) });

  return (
    <Panel title="Last run">
      <QueryBody query={runs} label="the last run" noAccess="view this project">
        {({ items: [last] }) => (
          <>
            {last ? (
              <RunFacts run={last} />
            ) : (
              <p className="text-sm text-fg-muted">No runs yet. Trigger the first run to generate the SDKs and docs.</p>
            )}
            <div className="flex flex-wrap gap-2">
              {canTrigger && (
                <Button loading={trigger.isPending} onClick={() => trigger.mutate(undefined, { onSuccess: openRun })}>
                  Trigger run
                </Button>
              )}
              {last && canRerun && isFinished(last.status) && (
                <Button variant="secondary" loading={rerun.isPending} onClick={() => rerun.mutate(last.id, { onSuccess: openRun })}>
                  Rerun
                </Button>
              )}
              {last && (
                <Link to={runPath(last.id)} className={buttonClasses("ghost", "md")}>
                  View run
                </Link>
              )}
            </div>
          </>
        )}
      </QueryBody>
    </Panel>
  );
}

function RunFacts({ run }: { run: RunSummary }) {
  return (
    <dl className="grid grid-cols-[max-content_1fr] items-center gap-x-6 gap-y-2 text-sm">
      <dt className="text-fg-muted">Status</dt>
      <dd>
        <StatusPill status={run.status} />
      </dd>
      <dt className="text-fg-muted">Trigger</dt>
      <dd className="text-fg">{TRIGGER_LABEL[run.trigger]}</dd>
      <dt className="text-fg-muted">Started</dt>
      <dd>
        <time dateTime={run.createdAt} className="text-fg">
          {relativeTime(run.createdAt)}
        </time>
      </dd>
      <dt className="text-fg-muted">Duration</dt>
      <dd className="font-mono text-fg">{formatDuration(run.durationMs)}</dd>
    </dl>
  );
}
