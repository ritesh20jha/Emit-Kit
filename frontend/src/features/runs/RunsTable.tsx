import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { History } from "lucide-react";
import { formatDuration, runPath, TRIGGER_LABEL } from "@/features/project-overview";
import { QueryBody } from "@/features/projects";
import { relativeTime } from "@/lib/utils/dates";
import { Badge, Button, EmptyState, type ResponsiveColumn, ResponsiveTable, StatusPill } from "@/ui";
import { type PageRunSummary, runsQuery } from "./api";
import type { RunSearch } from "./schemas";

type RunSummary = PageRunSummary["items"][number];

export interface RunsTableProps {
  projectId: string;
  search: RunSearch;
  onClearFilters: () => void;
  onNext: (cursor: string) => void;
  onFirstPage: () => void;
}

/** The project's runs, newest first, with Next page and First page. The four states of the list are here. */
export function RunsTable({ projectId, search, onClearFilters, onNext, onFirstPage }: RunsTableProps) {
  const query = useQuery(runsQuery(projectId, search));
  return (
    <QueryBody query={query} label="the runs" noAccess="view runs">
      {(page) =>
        page.items.length === 0 ? (
          <EmptyRuns search={search} onClearFilters={onClearFilters} onFirstPage={onFirstPage} />
        ) : (
          <Pages page={page} hasCursor={search.cursor !== undefined} onNext={onNext} onFirstPage={onFirstPage} />
        )
      }
    </QueryBody>
  );
}

function Pages({
  page,
  hasCursor,
  onNext,
  onFirstPage,
}: {
  page: PageRunSummary;
  hasCursor: boolean;
  onNext: (cursor: string) => void;
  onFirstPage: () => void;
}) {
  const nextCursor = page.nextCursor;
  return (
    <div className="flex flex-col gap-3">
      <ResponsiveTable
        label="Runs"
        columns={COLUMNS}
        rows={page.items}
        rowKey={(run) => run.id}
        renderCard={(run) => (
          <div className="flex flex-col gap-2 text-sm">
            {COLUMNS.map((column) => (
              <div key={column.key} className="flex items-center justify-between gap-3">
                <span className="text-fg-muted">{column.header}</span>
                {column.cell(run)}
              </div>
            ))}
          </div>
        )}
      />
      {(hasCursor || nextCursor !== null) && (
        <div className="flex items-center justify-end gap-2">
          {hasCursor && (
            <Button variant="ghost" size="sm" onClick={onFirstPage}>
              First page
            </Button>
          )}
          {nextCursor !== null && (
            <Button variant="secondary" size="sm" onClick={() => onNext(nextCursor)}>
              Next page
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

function EmptyRuns({
  search,
  onClearFilters,
  onFirstPage,
}: {
  search: RunSearch;
  onClearFilters: () => void;
  onFirstPage: () => void;
}) {
  if (search.status !== undefined || search.trigger !== undefined) {
    return (
      <EmptyState
        icon={History}
        title="No runs match these filters."
        description="Try another status or trigger."
        action={
          <Button variant="secondary" size="sm" onClick={onClearFilters}>
            Clear filters
          </Button>
        }
      />
    );
  }
  if (search.cursor !== undefined) {
    return (
      <EmptyState
        icon={History}
        title="No runs on this page."
        description="Go back to the first page to see the rest."
        action={
          <Button variant="secondary" size="sm" onClick={onFirstPage}>
            First page
          </Button>
        }
      />
    );
  }
  return <EmptyState icon={History} title="No runs yet" description="Trigger the first run to generate the SDKs and docs." />;
}

const COLUMNS: ResponsiveColumn<RunSummary>[] = [
  { key: "status", header: "Status", cell: (run) => <StatusPill status={run.status} /> },
  { key: "trigger", header: "Trigger", cell: (run) => <TriggerCell run={run} /> },
  {
    key: "version",
    header: "Version",
    cell: (run) => <span className="font-mono text-sm text-fg">{run.sdkVersion ?? "—"}</span>,
  },
  {
    key: "duration",
    header: "Duration",
    cell: (run) => <span className="font-mono text-sm text-fg-muted">{formatDuration(run.durationMs)}</span>,
  },
  { key: "pr", header: "Pull request", cell: (run) => <PullRequestLink run={run} /> },
  { key: "created", header: "Created", cell: (run) => <CreatedLink run={run} /> },
];

function TriggerCell({ run }: { run: RunSummary }) {
  return (
    <span className="inline-flex flex-wrap items-center gap-2 text-sm">
      <span className="text-fg-muted">{TRIGGER_LABEL[run.trigger]}</span>
      {run.dryRun && <Badge variant="accent">Dry run</Badge>}
    </span>
  );
}

function PullRequestLink({ run }: { run: RunSummary }) {
  if (run.prUrl === null || run.prNumber === null) return <span className="text-sm text-fg-muted">—</span>;
  return (
    <a
      href={run.prUrl}
      target="_blank"
      rel="noreferrer"
      aria-label={`Pull request #${run.prNumber}`}
      className="rounded-control font-mono text-sm text-fg underline-offset-4 hover:underline"
    >
      #{run.prNumber}
    </a>
  );
}

/** The run's start in words, with the exact time as the tooltip. The link opens the run. */
function CreatedLink({ run }: { run: RunSummary }) {
  return (
    <Link to={runPath(run.id)} className="rounded-control text-sm text-fg underline-offset-4 hover:underline">
      <time dateTime={run.createdAt} title={new Date(run.createdAt).toLocaleString()}>
        {relativeTime(run.createdAt)}
      </time>
    </Link>
  );
}
