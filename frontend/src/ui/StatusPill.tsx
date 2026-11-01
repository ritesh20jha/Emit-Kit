import { Circle, CircleCheck, CircleSlash, CircleX, Clock, CirclePause, LoaderCircle, type LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils/cn";

export type RunStatus = "QUEUED" | "RUNNING" | "AWAITING_APPROVAL" | "SUCCESS" | "FAILED" | "CANCELLED";

interface StatusMeta {
  label: string;
  icon: LucideIcon;
  tone: string;
  spin?: boolean;
}

const STATUS: Record<RunStatus, StatusMeta> = {
  QUEUED: { label: "Queued", icon: Clock, tone: "text-status-neutral" },
  RUNNING: { label: "Running", icon: LoaderCircle, tone: "text-status-running", spin: true },
  AWAITING_APPROVAL: { label: "Awaiting approval", icon: CirclePause, tone: "text-status-awaiting" },
  SUCCESS: { label: "Succeeded", icon: CircleCheck, tone: "text-status-success" },
  FAILED: { label: "Failed", icon: CircleX, tone: "text-status-failed" },
  CANCELLED: { label: "Cancelled", icon: CircleSlash, tone: "text-status-neutral" },
};

/** Run status as icon + text. Colour is supplementary, so the label carries the meaning on its own. */
export function StatusPill({ status, className }: { status: RunStatus; className?: string }) {
  // A status the frontend does not know yet (e.g. added by the API) shows its raw value, not a crash.
  const known: StatusMeta | undefined = STATUS[status];
  const label = known?.label ?? status;
  const Icon = known?.icon ?? Circle;
  const tone = known?.tone ?? "text-status-neutral";
  const spin = known?.spin;
  return (
    <span
      className={cn(
        "inline-flex h-6 items-center gap-1.5 rounded-control border border-border bg-bg-subtle px-2 text-xs font-medium text-fg",
        className,
      )}
    >
      <Icon data-testid="status-icon" aria-hidden="true" className={cn("size-3.5 shrink-0", tone, spin && "motion-safe:animate-spin")} />
      <span>{label}</span>
    </span>
  );
}
