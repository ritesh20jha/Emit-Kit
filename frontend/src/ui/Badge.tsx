import type { ComponentPropsWithoutRef } from "react";
import { cn } from "@/lib/utils/cn";

export type BadgeVariant = "neutral" | "accent";

const VARIANT: Record<BadgeVariant, string> = {
  neutral: "border-border bg-bg-subtle text-fg-muted",
  accent: "border-accent/35 bg-accent/8 text-accent",
};

export type BadgeProps = ComponentPropsWithoutRef<"span"> & { variant?: BadgeVariant };

export function Badge({ variant = "neutral", className, ...props }: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex h-5 items-center rounded-control border px-1.5 font-mono text-2xs font-medium tracking-[0.04em]",
        VARIANT[variant],
        className,
      )}
      {...props}
    />
  );
}
