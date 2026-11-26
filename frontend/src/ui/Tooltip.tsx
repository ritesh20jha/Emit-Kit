import * as TooltipPrimitive from "@radix-ui/react-tooltip";
import type { ReactNode } from "react";

export interface TooltipProps {
  /** Short plain text. The trigger is described by it, so it should not repeat a visible label. */
  content: ReactNode;
  /** A single focusable element. Keyboard focus shows the tooltip at once; hover waits briefly. */
  children: ReactNode;
  side?: "top" | "right" | "bottom" | "left";
  defaultOpen?: boolean;
}

/** A short label for an icon or truncated text. Shows on hover and on keyboard focus. */
export function Tooltip({ content, children, side = "top", defaultOpen }: TooltipProps) {
  return (
    <TooltipPrimitive.Provider delayDuration={300}>
      <TooltipPrimitive.Root defaultOpen={defaultOpen}>
        <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
        <TooltipPrimitive.Portal>
          <TooltipPrimitive.Content
            side={side}
            sideOffset={4}
            className="z-50 max-w-xs rounded-control bg-fg px-2 py-1 text-xs font-medium text-bg motion-safe:animate-overlay-fade"
          >
            {content}
          </TooltipPrimitive.Content>
        </TooltipPrimitive.Portal>
      </TooltipPrimitive.Root>
    </TooltipPrimitive.Provider>
  );
}
