import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { History } from "lucide-react";
import { type ProjectDetail, QueryBody, Panel } from "@/features/projects";
import { relativeTime } from "@/lib/utils/dates";
import { EmptyState, StatusPill } from "@/ui";
import { recentRunsQuery } from "./api";
import { formatDuration, runPath, TRIGGER_LABEL } from "./format";

/** The last few runs, newest first. Each one links to its run page. */
export function RecentRuns({ project }: { project: ProjectDetail }) {
  const runs = useQuery(recentRunsQuery(project.id));
  return (
    <Panel title="Recent activity">
      <QueryBody query={runs} label="recent runs" noAccess="view this project">
        {({ items }) =>
          items.length === 0 ? (
            <EmptyState icon={History} title="No runs yet" description="Trigger the first run to generate the SDKs and docs." />
          ) : (
            <ul className="divide-y divide-border text-sm">
              {items.map((run) => (
                <li key={run.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 py-2">
                  <Link to={runPath(run.id)} className="rounded-control text-fg underline-offset-4 hover:underline">
                    <time dateTime={run.createdAt}>{relativeTime(run.createdAt)}</time>
                  </Link>
                  <StatusPill status={run.status} />
                  <span className="text-fg-muted">{TRIGGER_LABEL[run.trigger]}</span>
                  <span className="ml-auto font-mono text-fg-muted">{formatDuration(run.durationMs)}</span>
                </li>
              ))}
            </ul>
          )
        }
      </QueryBody>
    </Panel>
  );
}
