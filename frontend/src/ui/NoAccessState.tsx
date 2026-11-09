import { Lock } from "lucide-react";

export interface NoAccessStateProps {
  /** The blocked thing as a short phrase, e.g. "rotate secrets". */
  action: string;
  /** Org admins to ask, shown when given. */
  admins?: string[];
}

/** The user lacks the role for an action. Says what is blocked and who can unblock it. */
export function NoAccessState({ action, admins }: NoAccessStateProps) {
  return (
    <div className="flex flex-col items-center gap-2 rounded-card border border-border px-6 py-10 text-center">
      <Lock aria-hidden="true" className="size-6 text-fg-subtle" />
      <p className="text-sm text-fg">You need admin access to {action}. Ask an org admin.</p>
      {admins && admins.length > 0 && <p className="text-sm text-fg-muted">Org admins: {admins.join(", ")}</p>}
    </div>
  );
}
