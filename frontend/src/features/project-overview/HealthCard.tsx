import { useQuery } from "@tanstack/react-query";
import { CircleCheck, TriangleAlert } from "lucide-react";
import { type ProjectDetail, QueryBody, Panel } from "@/features/projects";
import { vcsQuotaQuery } from "@/features/shell";
import { cn } from "@/lib/utils/cn";
import { configQuery } from "./api";

/** Webhook state, the GitHub quota, and whether an AI key is set when the project uses AI. */
export function HealthCard({ project }: { project: ProjectDetail }) {
  const quota = useQuery(vcsQuotaQuery);
  const config = useQuery(configQuery(project.id));
  const { registered } = project.webhook;
  const StateIcon = registered ? CircleCheck : TriangleAlert;
  return (
    <Panel title="Health">
      <div className="flex flex-col gap-2 text-sm text-fg">
        <p className="flex items-center gap-2">
          <StateIcon
            aria-hidden="true"
            className={cn("size-4 shrink-0", registered ? "text-status-success" : "text-status-awaiting")}
          />
          {registered ? "Webhook registered" : "Webhook not registered"}
        </p>
        <QueryBody query={quota} label="the GitHub quota" noAccess="view this project">
          {(current) => <p>{`GitHub quota: ${current.remaining} of ${current.limit} left`}</p>}
        </QueryBody>
        <QueryBody query={config} label="the AI settings" noAccess="view this project">
          {(current) => current.aiProvider !== null && <p>{current.aiKeySet ? "AI key set" : "AI key not set"}</p>}
        </QueryBody>
      </div>
    </Panel>
  );
}
