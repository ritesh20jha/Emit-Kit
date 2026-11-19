import { useQuery } from "@tanstack/react-query";
import { projectQuery, QueryBody } from "@/features/projects";
import { HealthCard } from "./HealthCard";
import { LastRunCard } from "./LastRunCard";
import { RecentRuns } from "./RecentRuns";
import { SpecCard } from "./SpecCard";
import { VersionCard } from "./VersionCard";

/** The project at a glance: last run, version, spec, health and recent activity. */
export function ProjectOverview({ projectId }: { projectId: string }) {
  const project = useQuery(projectQuery(projectId));
  return (
    <QueryBody query={project} label="the project" noAccess="view this project">
      {(detail) => (
        <div className="grid gap-4 md:grid-cols-2">
          <div className="md:col-span-2">
            <LastRunCard project={detail} />
          </div>
          <VersionCard project={detail} />
          <SpecCard project={detail} />
          <HealthCard project={detail} />
          <div className="md:col-span-2">
            <RecentRuns project={detail} />
          </div>
        </div>
      )}
    </QueryBody>
  );
}
