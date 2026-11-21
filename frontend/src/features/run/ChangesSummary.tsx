import { useQuery } from "@tanstack/react-query";
import { GitCompare } from "lucide-react";
import { QueryBody } from "@/features/projects";
import { cn } from "@/lib/utils/cn";
import { EmptyState } from "@/ui";
import { changeItems } from "./changeItems";
import { runQuery } from "./api";

/** The run's diff summary as a list, breaking changes first. */
export function ChangesSummary({ runId }: { runId: string }) {
  const run = useQuery(runQuery(runId));
  return (
    <QueryBody query={run} label="the changes" noAccess="view this run">
      {(detail) => {
        const items = changeItems(detail.diffSummary);
        return items.length === 0 ? (
          <EmptyState icon={GitCompare} title="No changes" description="This run's spec matches the last published version." />
        ) : (
          <ol aria-label="Changes" className="divide-y divide-border rounded-card border border-border">
            {items.map((item) => (
              <li key={item.key} className="flex flex-col gap-1 px-4 py-3 text-sm sm:flex-row sm:items-center sm:gap-3">
                <span className={cn("shrink-0 font-medium", item.breaking ? "text-status-failed" : "text-fg-muted")}>{item.label}</span>
                <code className="min-w-0 break-all font-mono text-xs text-fg">{item.title}</code>
                {item.detail && <span className="text-fg-muted">{item.detail}</span>}
              </li>
            ))}
          </ol>
        );
      }}
    </QueryBody>
  );
}
