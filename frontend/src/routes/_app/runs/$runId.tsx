import { createFileRoute, Outlet } from "@tanstack/react-router";
import { RunPage } from "@/features/run";

/** One run. Its header, approval banner and step timeline are shared; logs, files, lint and changes are child routes. */
export const Route = createFileRoute("/_app/runs/$runId")({
  component: RunRoute,
});

function RunRoute() {
  const { runId } = Route.useParams();
  return <RunPage runId={runId}><Outlet /></RunPage>;
}
