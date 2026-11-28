import { type ReactNode, useId } from "react";
import { cn } from "@/lib/utils/cn";

/** A titled block on a project page. Its region is named by the title, so a block can be found by name. */
export function Panel({ title, danger = false, children }: { title: string; danger?: boolean; children: ReactNode }) {
  const titleId = useId();
  return (
    <section
      aria-labelledby={titleId}
      className={cn("flex min-w-0 flex-col gap-3 rounded-card border bg-surface p-4 shadow-raised md:p-5", danger ? "border-status-failed/50" : "border-border")}
    >
      <h2 id={titleId} className={cn("text-sm font-semibold", danger ? "text-status-failed" : "text-fg")}>
        {title}
      </h2>
      {children}
    </section>
  );
}
