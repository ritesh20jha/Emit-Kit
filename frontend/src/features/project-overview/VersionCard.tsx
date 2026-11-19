import { useQuery } from "@tanstack/react-query";
import { type ProjectDetail, QueryBody, Panel } from "@/features/projects";
import { changeTypeLabel, versionsQuery } from "@/features/versions";
import { formatDate } from "@/lib/utils/dates";
import { Badge } from "@/ui";
import { recentRunsQuery } from "./api";

/** The published version with its change type, and the latest pull request one of the project's runs opened. */
export function VersionCard({ project }: { project: ProjectDetail }) {
  const versions = useQuery(versionsQuery(project.id));
  return (
    <Panel title="Version">
      <QueryBody query={versions} label="the version" noAccess="view this project">
        {([current]) =>
          current ? (
            <div className="flex flex-col gap-2 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-mono text-lg text-fg">{current.version}</span>
                <Badge>{changeTypeLabel(current.changeType)}</Badge>
              </div>
              <time dateTime={current.createdAt} className="text-fg-muted">
                {formatDate(current.createdAt)}
              </time>
              <PullRequestLink projectId={project.id} />
            </div>
          ) : (
            <p className="text-sm text-fg-muted">No version yet. The first successful run publishes one.</p>
          )
        }
      </QueryBody>
    </Panel>
  );
}

function PullRequestLink({ projectId }: { projectId: string }) {
  const runs = useQuery(recentRunsQuery(projectId));
  return (
    <QueryBody query={runs} label="the pull request" noAccess="view this project">
      {({ items }) => {
        const pr = items.find((run) => run.prUrl !== null);
        if (!pr?.prUrl) return null;
        const label = pr.prNumber === null ? "Pull request" : `Pull request #${pr.prNumber}`;
        return (
          <a href={pr.prUrl} target="_blank" rel="noopener noreferrer" className="w-fit text-fg underline-offset-4 hover:underline">
            {label}
          </a>
        );
      }}
    </QueryBody>
  );
}
