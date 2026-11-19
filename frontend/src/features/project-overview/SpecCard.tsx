import { useQuery } from "@tanstack/react-query";
import { type ProjectDetail, QueryBody, Panel } from "@/features/projects";
import { recentRunsQuery, runQuery } from "./api";

/** The spec's path and branch, and the lint counts of the last run. */
export function SpecCard({ project }: { project: ProjectDetail }) {
  const runs = useQuery(recentRunsQuery(project.id));
  return (
    <Panel title="Spec">
      <dl className="grid grid-cols-[max-content_1fr] items-center gap-x-6 gap-y-2 text-sm">
        <dt className="text-fg-muted">Path</dt>
        <dd className="break-all font-mono text-fg">{project.specPath}</dd>
        <dt className="text-fg-muted">Branch</dt>
        <dd className="font-mono text-fg">{project.defaultBranch}</dd>
        <dt className="text-fg-muted">Lint</dt>
        <dd>
          <QueryBody query={runs} label="the lint counts" noAccess="view this project">
            {({ items: [last] }) => (last ? <LintOf runId={last.id} /> : <span className="text-fg-muted">No runs yet</span>)}
          </QueryBody>
        </dd>
      </dl>
    </Panel>
  );
}

function LintOf({ runId }: { runId: string }) {
  const run = useQuery(runQuery(runId));
  return (
    <QueryBody query={run} label="the lint counts" noAccess="view this project">
      {({ lint: { errors, warnings, infos } }) => (
        <span className="text-fg">{`${plural(errors, "error")} · ${plural(warnings, "warning")} · ${infos} info`}</span>
      )}
    </QueryBody>
  );
}

const plural = (count: number, word: string) => `${count} ${word}${count === 1 ? "" : "s"}`;
