import { CircleAlert } from "lucide-react";
import { Button } from "./Button";
import type { FeedbackError } from "./toast";

export interface ErrorStateProps {
  error: FeedbackError;
  onRetry: () => void;
}

/** A failed load. The message is the title, the code sits under it in mono for support. The icon and text carry it, not colour. */
export function ErrorState({ error, onRetry }: ErrorStateProps) {
  return (
    <div role="alert" className="flex flex-col items-center gap-2 rounded-card border border-border px-6 py-10 text-center">
      <CircleAlert aria-hidden="true" className="size-6 text-status-failed" />
      <p className="font-medium text-fg">{error.message}</p>
      {error.code && <code className="font-mono text-xs text-fg-muted">{error.code}</code>}
      <Button variant="secondary" size="sm" className="mt-2" onClick={onRetry}>Retry</Button>
    </div>
  );
}
