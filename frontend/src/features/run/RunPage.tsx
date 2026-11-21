import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { projectQuery, QueryBody } from "@/features/projects";
import { Breadcrumbs, RouteTabs } from "@/ui";
import { ApprovalBanner } from "./ApprovalBanner";
import { type RunDetail, runQuery } from "./api";
import { RunHeader } from "./RunHeader";
import { StepTimeline } from "./StepTimeline";

/** The run's header, approval banner, step timeline and section tabs. The section (a child route) goes below. */
export function RunPage({ runId, children }: { runId: string; children: ReactNode }) {
  const run = useQuery(runQuery(runId));
  return (
    <QueryBody query={run} label="the run" noAccess="view this run">
      {(detail) => <RunLayout run={detail}>{children}</RunLayout>}
    </QueryBody>
  );
}

/** The project supplies the org, which decides what the user may do on this run. */
function RunLayout({ run, children }: { run: RunDetail; children: ReactNode }) {
  const project = useQuery(projectQuery(run.projectId));
  const orgId = project.data?.orgId ?? "";
  const crumbs = project.data
    ? [{ label: "Projects", to: `/orgs/${orgId}` }, { label: project.data.repoFullName, to: `/projects/${run.projectId}` }, { label: "Run" }]
    : [{ label: "Project" }];
  const base = `/runs/${run.id}`;
  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3">
        <Breadcrumbs items={crumbs} />
        <RunHeader run={run} orgId={orgId} />
      </div>
      <ApprovalBanner run={run} orgId={orgId} />
      <StepTimeline runId={run.id} steps={run.steps} />
      <RouteTabs
        label="Run sections"
        tabs={[
          { to: base, label: "Logs" },
          { to: `${base}/files`, label: "Files" },
          { to: `${base}/lint`, label: "Lint" },
          { to: `${base}/changes`, label: "Changes" },
        ]}
      />
      {children}
    </div>
  );
}
