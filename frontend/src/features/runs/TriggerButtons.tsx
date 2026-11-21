import { useNavigate } from "@tanstack/react-router";
import { runPath } from "@/features/project-overview";
import type { ProjectDetail } from "@/features/projects";
import { usePermission } from "@/lib/auth/permissions";
import { Button } from "@/ui";
import { useStartRun } from "./api";

/** Trigger run and Dry run. Both show only when the user may start a run, and both open the new run. */
export function TriggerButtons({ project }: { project: ProjectDetail }) {
  const canTrigger = usePermission("RUN_TRIGGER", project.orgId);
  const start = useStartRun(project.id);
  const navigate = useNavigate();
  if (!canTrigger) return null;
  const open = (dryRun: boolean) => start.mutate(dryRun, { onSuccess: (run) => void navigate({ to: runPath(run.id) }) });
  return (
    <div className="flex flex-wrap gap-2">
      <Button
        variant="secondary"
        loading={start.isPending && start.variables === true}
        disabled={start.isPending}
        onClick={() => open(true)}
      >
        Dry run
      </Button>
      <Button loading={start.isPending && start.variables === false} disabled={start.isPending} onClick={() => open(false)}>
        Trigger run
      </Button>
    </div>
  );
}
