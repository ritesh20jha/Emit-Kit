import { createFileRoute } from "@tanstack/react-router";
import { ChangesSummary } from "@/features/run";

/** The run's diff summary, breaking changes first. */
export const Route = createFileRoute("/_app/runs/$runId/changes")({
  component: ChangesRoute,
});

function ChangesRoute() {
  const { runId } = Route.useParams();
  return <ChangesSummary runId={runId} />;
}
