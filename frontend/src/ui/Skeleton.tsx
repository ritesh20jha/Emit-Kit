import type { ComponentPropsWithRef } from "react";
import { cn } from "@/lib/utils/cn";

/**
 * Placeholder block for content that is loading. Decorative: the consuming view marks its region aria-busy
 * and says what is loading.
 */
export function Skeleton({ className, ...props }: ComponentPropsWithRef<"div">) {
  return <div className={cn("rounded-control bg-bg-muted motion-safe:animate-pulse", className)} {...props} aria-hidden="true" />;
}
