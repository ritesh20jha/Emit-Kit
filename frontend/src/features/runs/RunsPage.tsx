import { useQuery } from "@tanstack/react-query";
import { projectQuery, QueryBody } from "@/features/projects";
import { RunFilters } from "./RunFilters";
import { RunsTable } from "./RunsTable";
import { TriggerButtons } from "./TriggerButtons";
import type { RunSearch } from "./schemas";

export interface RunsPageProps {
  projectId: string;
  search: RunSearch;
  /** Writes the next search. Each filter or page change adds a history entry. */
  onSearchChange: (next: RunSearch) => void;
}

/** The project's runs with filters, paging and the trigger buttons. The buttons need the project's org, so the page waits for it. */
export function RunsPage({ projectId, search, onSearchChange }: RunsPageProps) {
  const project = useQuery(projectQuery(projectId));
  return (
    <QueryBody query={project} label="the project" noAccess="view this project">
      {(detail) => (
        <div className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <RunFilters search={search} onChange={onSearchChange} />
            <TriggerButtons project={detail} />
          </div>
          <RunsTable
            projectId={projectId}
            search={search}
            onClearFilters={() => onSearchChange({})}
            onNext={(cursor) => onSearchChange({ ...search, cursor })}
            onFirstPage={() => onSearchChange({ ...search, cursor: undefined })}
          />
        </div>
      )}
    </QueryBody>
  );
}
