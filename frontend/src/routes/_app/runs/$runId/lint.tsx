import { createFileRoute } from "@tanstack/react-router";
import { LintTable } from "@/features/run";

/** The lint issues found in the run's spec. */
export const Route = createFileRoute("/_app/runs/$runId/lint")({
  component: LintRoute,
});

function LintRoute() {
  const { runId } = Route.useParams();
  return <LintTable runId={runId} />;
}
