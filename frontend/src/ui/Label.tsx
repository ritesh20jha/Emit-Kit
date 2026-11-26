import type { ComponentPropsWithRef } from "react";
import { cn } from "@/lib/utils/cn";

export type LabelProps = ComponentPropsWithRef<"label">;

export function Label({ className, ref, ...props }: LabelProps) {
  return <label ref={ref} className={cn("text-sm font-medium text-fg", className)} {...props} />;
}
