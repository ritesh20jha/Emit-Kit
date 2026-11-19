import { Panel } from "@/features/projects";
import { Badge, ErrorState, Skeleton } from "@/ui";
import type { Problem, ProblemSeverity, ProblemsView } from "./problems";

const LABEL: Record<ProblemSeverity, string> = { error: "Error", warning: "Warning", info: "Info" };

/** The problems in the spec. A problem with a line jumps the editor to it. */
export function ProblemsPanel({ view, onJump }: { view: ProblemsView; onJump: (line: number) => void }) {
  return (
    <Panel title="Problems">
      {view.status === "pending" && (
        <div aria-busy="true" className="flex flex-col gap-2">
          <span className="sr-only">Checking the spec…</span>
          <Skeleton className="h-12" />
        </div>
      )}
      {view.status === "error" && <ErrorState error={view.error} onRetry={view.retry} />}
      {view.status === "ready" && view.problems.length === 0 && <p className="text-sm text-fg-muted">No problems found.</p>}
      {view.status === "ready" && view.problems.length > 0 && (
        <ul className="flex flex-col gap-3">
          {view.problems.map((problem) => (
            <ProblemItem key={problem.id} problem={problem} onJump={onJump} />
          ))}
        </ul>
      )}
    </Panel>
  );
}

function ProblemItem({ problem, onJump }: { problem: Problem; onJump: (line: number) => void }) {
  const { line } = problem;
  return (
    <li className="flex flex-col gap-1 text-sm">
      <div className="flex items-center gap-2">
        <Badge>{LABEL[problem.severity]}</Badge>
        {line !== null && (
          <button
            type="button"
            onClick={() => onJump(line)}
            className="font-mono text-xs text-accent underline-offset-4 hover:underline"
          >
            {`Go to line ${line}`}
          </button>
        )}
      </div>
      <p className="text-fg">{problem.message}</p>
      {problem.path && <code className="break-all font-mono text-xs text-fg-muted">{problem.path}</code>}
    </li>
  );
}
