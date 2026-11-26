import { createFileRoute } from "@tanstack/react-router";
import { FileCode } from "lucide-react";
import { Suspense } from "react";
import { FilesTree, LazyFileDiff } from "@/features/run";
import { EmptyState, Skeleton } from "@/ui";

/** The file tree beside the selected file's diff. The selection lives in ?path=, so a file can be linked. */
export const Route = createFileRoute("/_app/runs/$runId/files")({
  // The router reads search values as JSON, so a path that looks like a number arrives as one.
  validateSearch: (search: Record<string, unknown>) => ({
    path: typeof search.path === "number" ? String(search.path) : typeof search.path === "string" && search.path ? search.path : undefined,
  }),
  component: FilesRoute,
});

function FilesRoute() {
  const { runId } = Route.useParams();
  const { path } = Route.useSearch();
  const navigate = Route.useNavigate();
  return (
    <div className="grid gap-4 md:grid-cols-[minmax(14rem,1fr)_2fr]">
      <FilesTree runId={runId} selected={path} onSelect={(next) => void navigate({ search: { path: next } })} />
      <div className="min-w-0">
        {path ? (
          <Suspense fallback={<Skeleton className="h-64" />}>
            <LazyFileDiff runId={runId} path={path} />
          </Suspense>
        ) : (
          <EmptyState icon={FileCode} title="Select a file" description="Choose a file in the tree to see its diff." />
        )}
      </div>
    </div>
  );
}
