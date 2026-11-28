import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

export interface EmptyStateProps {
  icon: LucideIcon;
  title: string;
  description?: ReactNode;
  /** Usually one button that starts the first action, e.g. "New project". */
  action?: ReactNode;
}

/** A view with no data yet: what is missing, why, and the action that fills it. */
export function EmptyState({ icon: Icon, title, description, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center gap-2 rounded-card border border-dashed border-border-strong bg-surface/60 px-6 py-12 text-center">
      <span className="mb-1 inline-flex size-10 items-center justify-center rounded-card border border-border bg-surface shadow-raised">
        <Icon aria-hidden="true" className="size-5 text-fg-muted" />
      </span>
      <h2 className="text-base font-medium text-fg">{title}</h2>
      {description && <p className="max-w-sm text-sm text-fg-muted">{description}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}
