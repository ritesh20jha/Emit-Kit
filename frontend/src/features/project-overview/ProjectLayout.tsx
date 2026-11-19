import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { projectQuery } from "@/features/projects";
import { Breadcrumbs, RouteTabs, Skeleton } from "@/ui";

/** The project's sections. Each is a route, so it has its own link and survives a refresh. */
function projectTabs(projectId: string) {
  const base = `/projects/${projectId}`;
  return [
    { to: base, label: "Overview" },
    { to: `${base}/runs`, label: "Runs" },
    { to: `${base}/editor`, label: "Editor" },
    { to: `${base}/diff`, label: "Diff" },
    { to: `${base}/config`, label: "Config" },
    { to: `${base}/versions`, label: "Versions" },
    { to: `${base}/settings`, label: "Settings" },
  ];
}

/** The header every project page shares: breadcrumbs, the repository and the section tabs. The page goes below. */
export function ProjectLayout({ projectId, children }: { projectId: string; children: ReactNode }) {
  const query = useQuery(projectQuery(projectId));
  const project = query.data;
  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3">
        <Breadcrumbs
          items={project ? [{ label: "Projects", to: `/orgs/${project.orgId}` }, { label: project.repoFullName }] : [{ label: "Project" }]}
        />
        {query.isPending ? (
          <Skeleton className="h-7 w-72" />
        ) : (
          <h1 className="break-all font-mono text-2xl font-semibold text-fg">{project?.repoFullName ?? "Project"}</h1>
        )}
        <RouteTabs tabs={projectTabs(projectId)} />
      </div>
      {children}
    </div>
  );
}
