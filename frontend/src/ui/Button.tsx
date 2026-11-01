import { LoaderCircle } from "lucide-react";
import type { ComponentPropsWithRef } from "react";
import { cn } from "@/lib/utils/cn";

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
export type ButtonSize = "sm" | "md";

const BASE =
  "relative inline-flex shrink-0 items-center justify-center rounded-control font-medium select-none transition-colors duration-(--duration-fast) disabled:pointer-events-none disabled:opacity-50";

const VARIANT: Record<ButtonVariant, string> = {
  primary: "bg-accent text-accent-fg hover:bg-accent/90",
  secondary: "border border-border-strong bg-bg text-fg hover:bg-bg-muted",
  ghost: "text-fg hover:bg-bg-muted",
  danger: "bg-status-failed text-bg hover:bg-status-failed/90",
};

const SIZE: Record<ButtonSize, string> = {
  sm: "h-7 px-2.5 text-xs",
  md: "h-8 px-3 text-sm",
};

/** Classes shared by Button and IconButton. */
export function buttonClasses(variant: ButtonVariant, size: ButtonSize): string {
  return cn(BASE, VARIANT[variant], SIZE[size]);
}

export type ButtonProps = ComponentPropsWithRef<"button"> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Shows a spinner over the label. The label stays in the accessible name and the width does not change. */
  loading?: boolean;
};

export function Button({
  variant = "primary",
  size = "md",
  loading = false,
  disabled,
  type = "button",
  className,
  children,
  onClick,
  ref,
  ...props
}: ButtonProps) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled}
      // aria-disabled, not disabled: a native disabled button drops keyboard focus mid-action.
      aria-disabled={loading || undefined}
      aria-busy={loading || undefined}
      onClick={(event) => {
        if (loading) {
          event.preventDefault();
          return;
        }
        onClick?.(event);
      }}
      className={cn(buttonClasses(variant, size), loading && "cursor-progress", className)}
      {...props}
    >
      <span className={cn("inline-flex items-center gap-2", loading && "opacity-0")}>{children}</span>
      {loading && <LoaderCircle aria-hidden="true" className="absolute inset-0 m-auto size-4 motion-safe:animate-spin" />}
    </button>
  );
}
