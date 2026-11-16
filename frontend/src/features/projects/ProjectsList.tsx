import { type UseQueryResult, useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { FolderGit2, Plus } from "lucide-react";
import { AppError } from "@/lib/api/appError";
import type { components } from "@/lib/api/generated/schema";
import { usePermission } from "@/lib/auth/permissions";
import { cn } from "@/lib/utils/cn";
import {
  Button,
  buttonClasses,
  EmptyState,
  ErrorState,
  NoAccessState,
  PageHeader,
  type ResponsiveColumn,
  ResponsiveTable,
  Skeleton,
  StatusPill,
} from "@/ui";
import { type PageProjectSummary, projectsQuery } from "./api";
import { ProjectFilters } from "./ProjectFilters";
import type { ProjectSearch, RunStatus } from "./schemas";

type ProjectSummary = components["schemas"]["ProjectSummary"];

const RELATIVE = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
const RELATIVE_UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["day", 86_400],
  ["hour", 3_600],
  ["minute", 60],
];

/** The age of a run in words: "3 hours ago", "yesterday", "now". */
function relativeTime(iso: string, now = Date.now()): string {
  const seconds = Math.round((Date.parse(iso) - now) / 1000);
  for (const [unit, size] of RELATIVE_UNITS) {
    if (Math.abs(seconds) >= size) return RELATIVE.format(Math.round(seconds / size), unit);
  }
  return RELATIVE.format(0, "second");
}

export interface ProjectsListProps {
  orgId: string;
  search: ProjectSearch;
  /** Writes the next search. Typing replaces the current history entry; choosing a filter or a page adds one. */
  onSearchChange: (next: ProjectSearch, options: { replace: boolean }) => void;
}

/** The org's projects with search, a last-run filter and cursor paging. All of it is in the URL. */
export function ProjectsList({ orgId, search, onSearchChange }: ProjectsListProps) {
  const query = useQuery(projectsQuery(orgId, search));
  const canCreate = usePermission("PROJECT_CREATE", orgId);
  const hasFilters = search.q !== undefined || search.status !== undefined;
  const filtered = hasFilters || search.cursor !== undefined;

  // A new filter starts again from the first page, since a cursor only means something for the search it came from.
  const commitQuery = (q: string) => onSearchChange({ ...search, q: q || undefined, cursor: undefined }, { replace: true });
  const changeStatus = (status: RunStatus | undefined) => onSearchChange({ ...search, status, cursor: undefined }, { replace: false });

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Projects"
        description="Repositories with an EmitKit spec, and the state of their last run."
        actions={canCreate ? <NewProjectLink orgId={orgId} /> : undefined}
      />
      <ProjectFilters q={search.q ?? ""} status={search.status} onQueryCommit={commitQuery} onStatusChange={changeStatus} />
      <Results
        orgId={orgId}
        query={query}
        canCreate={canCreate}
        filtered={filtered}
        hasFilters={hasFilters}
        onClearFilters={() => onSearchChange({}, { replace: false })}
        onNext={(cursor) => onSearchChange({ ...search, cursor }, { replace: false })}
        onFirstPage={() => onSearchChange({ ...search, cursor: undefined }, { replace: false })}
        hasCursor={search.cursor !== undefined}
      />
    </div>
  );
}

interface ResultsProps {
  orgId: string;
  query: UseQueryResult<PageProjectSummary, Error>;
  canCreate: boolean;
  filtered: boolean;
  /** A search or status filter is set, as opposed to only a cursor. */
  hasFilters: boolean;
  hasCursor: boolean;
  onClearFilters: () => void;
  onNext: (cursor: string) => void;
  onFirstPage: () => void;
}

/** The four states of the list: loading, empty, error and no permission. Rows only show once there are some. */
function Results({
  orgId,
  query,
  canCreate,
  filtered,
  hasFilters,
  hasCursor,
  onClearFilters,
  onNext,
  onFirstPage,
}: ResultsProps) {
  if (query.isError) {
    return <ListError error={query.error} onRetry={() => void query.refetch()} />;
  }
  const page = query.data;
  if (!page) return <ListSkeleton />;
  // Placeholder rows belong to the previous filter or page. They stay visible, dimmed and busy, until the response lands.
  // An empty placeholder would read as the new result, so it shows the loading state instead.
  const stale = query.isPlaceholderData;
  if (stale && page.items.length === 0) return <ListSkeleton />;
  const nextCursor = page.nextCursor;

  if (page.items.length === 0) {
    // Every empty result leads somewhere: clearing the filters, or going back to the first page.
    if (filtered && hasFilters) {
      return (
        <EmptyState
          icon={FolderGit2}
          title="No projects match these filters."
          description="Try another search, or choose a different status."
          action={<Button variant="secondary" size="sm" onClick={onClearFilters}>Clear filters</Button>}
        />
      );
    }
    if (filtered) {
      return (
        <EmptyState
          icon={FolderGit2}
          title="No projects on this page."
          description="Go back to the first page to see the rest."
          action={<Button variant="secondary" size="sm" onClick={onFirstPage}>First page</Button>}
        />
      );
    }
    return (
      <EmptyState
        icon={FolderGit2}
        title="No projects yet."
        description="Connect a repository to start."
        action={canCreate ? <NewProjectLink orgId={orgId} /> : <p className="text-sm text-fg-muted">Ask an admin or editor to connect one.</p>}
      />
    );
  }

  return (
    <div aria-busy={stale || undefined} className={cn("flex flex-col gap-3", stale && "opacity-60")}>
      <ResponsiveTable
        label="Projects"
        columns={COLUMNS}
        rows={page.items}
        rowKey={(project) => project.id}
        renderCard={(project) => <ProjectCard project={project} />}
      />
      {(hasCursor || nextCursor !== null) && (
        <div className="flex items-center justify-end gap-2">
          {hasCursor && (
            <Button variant="ghost" size="sm" disabled={stale} onClick={onFirstPage}>
              First page
            </Button>
          )}
          {nextCursor !== null && (
            <Button variant="secondary" size="sm" disabled={stale} onClick={() => onNext(nextCursor)}>
              Next page
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

function NewProjectLink({ orgId }: { orgId: string }) {
  return (
    // The project routes arrive with WEB-06/07, so the typed router does not know these paths yet.
    <Link to={`/orgs/${orgId}/projects/new` as "/"} className={buttonClasses("primary", "md")}>
      <Plus aria-hidden="true" className="size-4" />
      New project
    </Link>
  );
}

function ListSkeleton() {
  return (
    <div aria-busy="true" className="flex flex-col gap-2">
      <span className="sr-only">Loading projects…</span>
      {[0, 1, 2].map((index) => (
        <Skeleton key={index} className="h-12" />
      ))}
    </div>
  );
}

function ListError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  // A 403 means the user may not see this org's projects at all, which is a permission state, not a failure.
  if (error instanceof AppError && error.status === 403) return <NoAccessState action="view projects" />;
  const feedback = error instanceof AppError ? error : { message: "EmitKit could not load projects." };
  return <ErrorState error={feedback} onRetry={onRetry} />;
}

const COLUMNS: ResponsiveColumn<ProjectSummary>[] = [
  { key: "repository", header: "Repository", cell: (project) => <RepoLink project={project} /> },
  { key: "lastRun", header: "Last run", cell: (project) => <LastRun project={project} /> },
  { key: "version", header: "Version", cell: (project) => <Version project={project} /> },
];

function ProjectCard({ project }: { project: ProjectSummary }) {
  return (
    <div className="flex flex-col gap-2">
      <RepoLink project={project} />
      <div className="flex flex-wrap items-center justify-between gap-2">
        <LastRun project={project} />
        <Version project={project} />
      </div>
    </div>
  );
}

function RepoLink({ project }: { project: ProjectSummary }) {
  return (
    // The project routes arrive with WEB-07, so the typed router does not know this path yet.
    <Link
      to={`/projects/${project.id}` as "/"}
      className="rounded-control font-mono text-sm text-fg underline-offset-4 hover:underline"
    >
      {project.repoFullName}
    </Link>
  );
}

/** Status is an icon and a word, so it reads without colour. The time is the run's start. */
function LastRun({ project }: { project: ProjectSummary }) {
  const { lastRunStatus, lastRunAt } = project;
  if (lastRunStatus === null) return <span className="text-sm text-fg-muted">No runs yet</span>;
  return (
    <span className="inline-flex flex-wrap items-center gap-2 text-sm">
      <StatusPill status={lastRunStatus} />
      {lastRunAt !== null && (
        <time dateTime={lastRunAt} title={new Date(lastRunAt).toLocaleString()} className="text-fg-muted">
          {relativeTime(lastRunAt)}
        </time>
      )}
    </span>
  );
}

function Version({ project }: { project: ProjectSummary }) {
  if (project.currentVersion === null) return <span className="text-sm text-fg-muted">None yet</span>;
  return <span className="font-mono text-sm text-fg">{project.currentVersion}</span>;
}
