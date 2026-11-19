import { type ProjectDetail, Panel } from "@/features/projects";
import { CircleCheck, TriangleAlert } from "lucide-react";
import { cn } from "@/lib/utils/cn";
import { Button } from "@/ui";
import { useRegisterWebhook } from "./api";

/** Whether GitHub delivers pushes to EmitKit, with Re-register for users who may change settings. */
export function WebhookPanel({ project, canEdit }: { project: ProjectDetail; canEdit: boolean }) {
  const register = useRegisterWebhook(project.id);
  const { registered } = project.webhook;
  const StateIcon = registered ? CircleCheck : TriangleAlert;
  return (
    <Panel title="Webhook">
      <p className="flex items-start gap-2 text-sm text-fg">
        <StateIcon
          aria-hidden="true"
          className={cn("mt-0.5 size-4 shrink-0", registered ? "text-status-success" : "text-status-awaiting")}
        />
        {registered ? "Registered. Pushes to the repository start a run." : "Not registered. Pushes will not start runs until it is."}
      </p>
      {canEdit && (
        <div>
          <Button variant="secondary" loading={register.isPending} onClick={() => register.mutate()}>
            Re-register
          </Button>
        </div>
      )}
    </Panel>
  );
}
