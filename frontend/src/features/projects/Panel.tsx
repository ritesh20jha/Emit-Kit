import { type ReactNode, useId } from "react";
import { cn } from "@/lib/utils/cn";

/** A titled block on a project page. Its region is named by the title, so a block can be found by name. */
export function Panel({ title, danger = false, children }: { title: string; danger?: boolean; children: ReactNode }) {
  const titleId = useId();
  return (
    <section
      aria-labelledby={titleId}
      className={cn("flex min-w-0 flex-col gap-3 rounded-card border p-4", danger ? "border-status-failed/50" : "border-border")}
    >
      <h2 id={titleId} className="text-sm font-medium text-fg">
        {title}
      </h2>
      {children}
    </section>
  );
}
