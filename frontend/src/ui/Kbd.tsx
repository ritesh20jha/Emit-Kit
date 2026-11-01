import type { ComponentPropsWithoutRef } from "react";
import { cn } from "@/lib/utils/cn";

export function Kbd({ className, ...props }: ComponentPropsWithoutRef<"kbd">) {
  return (
    <kbd
      className={cn(
        "inline-flex h-5 min-w-5 items-center justify-center rounded-control border border-border-strong bg-bg-subtle px-1 font-mono text-xs text-fg-muted",
        className,
      )}
      {...props}
    />
  );
}
