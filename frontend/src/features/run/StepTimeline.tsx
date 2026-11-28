import { useNavigate, useRouterState } from "@tanstack/react-router";
import { Circle, CircleCheck, CircleSlash, CircleX, LoaderCircle, type LucideIcon } from "lucide-react";
import { formatDuration } from "@/features/project-overview";
import { type StepName, STEP_LABELS } from "@/features/run-logs";
import { cn } from "@/lib/utils/cn";
import type { components } from "@/lib/api/generated/schema";

type StepState = components["schemas"]["StepState"];

const STATUS: Record<StepState["status"], { label: string; icon: LucideIcon; tone: string }> = {
  PENDING: { label: "Pending", icon: Circle, tone: "text-status-neutral" },
  RUNNING: { label: "Running", icon: LoaderCircle, tone: "text-status-running" },
  DONE: { label: "Done", icon: CircleCheck, tone: "text-status-success" },
  SKIPPED: { label: "Skipped", icon: CircleSlash, tone: "text-status-neutral" },
  FAILED: { label: "Failed", icon: CircleX, tone: "text-status-failed" },
};

/** The eleven pipeline steps in order: a wrapping grid, two columns below 768 px. Unreached steps show as pending. */
export function StepTimeline({ runId, steps }: { runId: string; steps: StepState[] }) {
  const navigate = useNavigate();
  const selected = useRouterState({ select: (state) => new URLSearchParams(state.location.searchStr).get("step") });
  const byName = new Map(steps.map((step) => [step.name, step]));
  return (
    <ol aria-label="Pipeline steps" className="grid grid-cols-2 gap-1.5 md:grid-cols-[repeat(auto-fill,minmax(9.5rem,1fr))]">
      {(Object.keys(STEP_LABELS) as StepName[]).map((name) => {
        const step: StepState = byName.get(name) ?? { name, status: "PENDING", durationMs: null };
        const { label, icon: Icon, tone } = STATUS[step.status];
        const isSelected = selected === name;
        return (
          <li key={name} className="min-w-0">
            <button type="button" aria-pressed={isSelected} onClick={() => void navigate({ href: `/runs/${runId}?step=${name}` })} className={cn("flex h-full w-full items-start gap-2 rounded-control border bg-surface px-2.5 py-2 text-left transition-colors duration-(--duration-fast) hover:border-border-strong hover:bg-bg-subtle", isSelected ? "border-accent ring-1 ring-accent" : step.status === "FAILED" ? "border-status-failed/45" : step.status === "RUNNING" ? "border-status-running/45" : "border-border")}>
              <Icon aria-hidden="true" className={cn("mt-0.5 size-4 shrink-0", tone, step.status === "RUNNING" && "motion-safe:animate-spin")} />
              <span className="flex min-w-0 flex-col">
                <span className="text-sm leading-snug font-medium text-fg">{STEP_LABELS[name]}</span>
                <span className="text-xs text-fg-muted">{label} · <span className="font-mono">{formatDuration(step.durationMs)}</span></span>
              </span>
            </button>
          </li>
        );
      })}
    </ol>
  );
}
