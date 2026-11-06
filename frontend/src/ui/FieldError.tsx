import type { ComponentPropsWithRef } from "react";
import { cn } from "@/lib/utils/cn";

export type FieldErrorProps = ComponentPropsWithRef<"p">;

/** Validation message for a field. FormField links it to the control through aria-describedby. */
export function FieldError({ className, ref, ...props }: FieldErrorProps) {
  return <p ref={ref} className={cn("text-xs text-status-failed", className)} {...props} />;
}
