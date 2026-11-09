import { Check, CircleAlert, Copy } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "./Button";

type CopyState = "idle" | "copied" | "failed";

const MESSAGE: Record<CopyState, string> = { idle: "", copied: "Copied", failed: "Copy failed" };

export interface CopyButtonProps {
  value: string;
  /** Visible and accessible name of the button. */
  label?: string;
}

/**
 * Copies value. The outcome is shown as text and announced by a polite live region that stays in the DOM, so the
 * announcement is not lost when it appears. A failed copy is reported, never thrown.
 */
export function CopyButton({ value, label = "Copy" }: CopyButtonProps) {
  const [state, setState] = useState<CopyState>("idle");
  const resetTimer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(resetTimer.current), []);

  async function copy() {
    let next: CopyState = "copied";
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      next = "failed";
    }
    setState(next);
    window.clearTimeout(resetTimer.current);
    resetTimer.current = window.setTimeout(() => setState("idle"), 2000);
  }

  const Icon = state === "copied" ? Check : state === "failed" ? CircleAlert : Copy;
  return (
    <span className="inline-flex items-center gap-2">
      <Button variant="ghost" size="sm" onClick={copy}>
        <Icon aria-hidden="true" className="size-3.5" />
        {label}
      </Button>
      <span role="status" className="text-xs text-fg-muted">{MESSAGE[state]}</span>
    </span>
  );
}
