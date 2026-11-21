import { useQuery, useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useEffect } from "react";
import { projectQuery, QueryBody } from "@/features/projects";
import { qk } from "@/lib/api/queryKeys";
import { Breadcrumbs, RouteTabs } from "@/ui";
import { ApprovalBanner } from "./ApprovalBanner";
import { type RunDetail, runQuery } from "./api";
import { RunHeader } from "./RunHeader";
import { StepTimeline } from "./StepTimeline";

/** The run's header, approval banner, step timeline and section tabs. The section (a child route) goes below. */
export function RunPage({ runId, children }: { runId: string; children: ReactNode }) {
  const run = useQuery(runQuery(runId, true));
  return (
    <QueryBody query={run} label="the run" noAccess="view this run">
      {(detail) => <RunLayout run={detail}>{children}</RunLayout>}
    </QueryBody>
  );
}

/** The project supplies the org, which decides what the user may do on this run. Nothing below shows until it loads, so no action is judged without an org. */
function RunLayout({ run, children }: { run: RunDetail; children: ReactNode }) {
  const project = useQuery(projectQuery(run.projectId));
  const queryClient = useQueryClient();
  useEffect(() => {
    for (const key of [qk.runs.files(run.id), qk.runs.lint(run.id)]) void queryClient.invalidateQueries({ queryKey: key });
  }, [queryClient, run.id, run.status]);
  const base = `/runs/${run.id}`;
  return (
    <QueryBody query={project} label="the project" noAccess="view this project">
      {({ orgId, repoFullName }) => (
        <div className="flex flex-col gap-6">
          <div className="flex flex-col gap-3">
            <Breadcrumbs items={[{ label: "Projects", to: `/orgs/${orgId}` }, { label: repoFullName, to: `/projects/${run.projectId}` }, { label: "Run" }]} />
            <RunHeader run={run} orgId={orgId} />
          </div>
          <ApprovalBanner run={run} orgId={orgId} />
          <StepTimeline runId={run.id} steps={run.steps} />
          <RouteTabs
            label="Run sections"
            tabs={[
              { to: `${base}/logs`, label: "Logs", alsoActiveAt: [base] },
              { to: `${base}/files`, label: "Files" },
              { to: `${base}/lint`, label: "Lint" },
              { to: `${base}/changes`, label: "Changes" },
            ]}
          />
          {children}
        </div>
      )}
    </QueryBody>
  );
}
