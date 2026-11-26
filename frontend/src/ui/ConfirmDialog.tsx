import { type ReactNode, type RefObject, useId, useState } from "react";
import { Button } from "./Button";
import { Dialog } from "./Dialog";
import { Input } from "./Input";
import { Label } from "./Label";
import type { FeedbackError } from "./toast";

export interface ConfirmDialogProps {
  open: boolean;
  onOpenChange?: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  confirmLabel: string;
  /** Label of the dismiss button. Defaults to "Cancel". */
  cancelLabel?: string;
  tone?: "default" | "danger";
  /** When set, confirm stays disabled until the user types this text exactly. */
  typedConfirmation?: string;
  /** Resolves on success, which closes the dialog. A rejection stays open and shows its message and code. */
  onConfirm: () => Promise<void>;
  /** Element that takes focus on close. Pass it when the dialog was opened from a menu item (see DialogFrame). */
  returnFocusRef?: RefObject<HTMLElement | null>;
}

interface ErrorView {
  message: string;
  code?: string;
}

/** Any object with a message (an Error included) is shown; the code is optional. Anything else gets a generic line. */
function describeError(error: unknown): ErrorView {
  if (typeof error === "object" && error !== null && "message" in error) {
    const { message, code } = error as Partial<FeedbackError>;
    if (typeof message === "string" && message) {
      return { message, code: typeof code === "string" ? code : undefined };
    }
  }
  return { message: "Something went wrong. Try again." };
}

/** Confirmation for an action that cannot be undone. Use tone "danger" for destructive actions. */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  cancelLabel = "Cancel",
  tone = "default",
  typedConfirmation,
  onConfirm,
  returnFocusRef,
}: ConfirmDialogProps) {
  const [typed, setTyped] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ErrorView | null>(null);
  // Reset the typed text and the error when the dialog closes, so a reopen starts clean.
  const [wasOpen, setWasOpen] = useState(open);
  if (open !== wasOpen) {
    setWasOpen(open);
    if (!open) {
      setTyped("");
      setError(null);
    }
  }
  const inputId = useId();
  const confirmed = typedConfirmation === undefined || typed === typedConfirmation;

  async function confirm() {
    setError(null);
    setPending(true);
    try {
      await onConfirm();
    } catch (failure) {
      setError(describeError(failure));
      setPending(false);
      return;
    }
    setPending(false);
    onOpenChange?.(false);
  }

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      dismissible={!pending}
      returnFocusRef={returnFocusRef}
      title={title}
      description={description}
      footer={
        <>
          <Button variant="secondary" disabled={pending} onClick={() => onOpenChange?.(false)}>
            {cancelLabel}
          </Button>
          <Button
            variant={tone === "danger" ? "danger" : "primary"}
            disabled={!confirmed}
            loading={pending}
            onClick={confirm}
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      {typedConfirmation !== undefined && (
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={inputId}>
            Type <code className="font-mono">{typedConfirmation}</code> to confirm
          </Label>
          <Input
            id={inputId}
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            disabled={pending}
            autoComplete="off"
            spellCheck={false}
            className="font-mono"
          />
        </div>
      )}
      {error && (
        <div role="alert" className="flex flex-col gap-0.5 text-sm">
          <p className="text-status-failed">{error.message}</p>
          {error.code && <code className="font-mono text-xs text-fg-muted">{error.code}</code>}
        </div>
      )}
    </Dialog>
  );
}
