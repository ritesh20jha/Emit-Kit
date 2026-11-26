import { createFileRoute } from "@tanstack/react-router";
import { lazy, Suspense } from "react";
import { parseDiffSearch } from "@/features/spec-diff/schemas";
import { Skeleton } from "@/ui";

// The page loads on first visit, so the diff code is not in the project shell's bundle.
const SpecDiffPage = lazy(() => import("@/features/spec-diff").then((module) => ({ default: module.SpecDiffPage })));

/** The changes between two saved versions of the spec. The versions are in the URL, so a comparison can be bookmarked. */
export const Route = createFileRoute("/_app/projects/$id/diff")({
  validateSearch: parseDiffSearch,
  component: DiffRoute,
});

function DiffRoute() {
  const { id } = Route.useParams();
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  return (
    <Suspense fallback={<Skeleton className="h-48" />}>
      <SpecDiffPage projectId={id} search={search} onSearchChange={(next) => void navigate({ search: next })} />
    </Suspense>
  );
}
