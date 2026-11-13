import { useVirtualizer } from "@tanstack/react-virtual";
import { ArrowDown, Copy, ScrollText } from "lucide-react";
import { type ReactNode, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { EventStreamStatus } from "@/lib/sse/useEventStream";
import { cn } from "@/lib/utils/cn";
import { Button, EmptyState, ErrorState, IconButton, Skeleton, toast } from "@/ui";
import { filterLines, type LogFilters, type LogLine, NO_FILTERS, STEP_LABELS, type StepName } from "./logFilters";
import { LogToolbar } from "./LogToolbar";

const ROW_HEIGHT = 24;
/** How close to the bottom counts as following the log. */
const AT_BOTTOM_PX = 16;

const LEVEL_CLASS: Record<string, string> = {
  INFO: "text-fg-muted",
  WARN: "text-status-awaiting",
  ERROR: "text-status-failed",
};

export interface LogViewProps {
  lines: LogLine[];
  /** From the stream. Shows the reconnect banner, and the loading or error state when no lines exist yet. */
  status?: EventStreamStatus;
  /** Controlled filters. Without them the view keeps its own. */
  filters?: LogFilters;
  onFiltersChange?: (next: LogFilters) => void;
  /** Retries the stream from the error state. */
  onRetry?: () => void;
}

/**
 * A virtualized, read-only log. Only the rows on screen are in the DOM, so a 100k-line log stays responsive.
 * Follows the newest line until the reader scrolls up; then "Jump to latest" brings them back.
 */
export function LogView({ lines, status, filters, onFiltersChange, onRetry }: LogViewProps) {
  const [ownFilters, setOwnFilters] = useState(NO_FILTERS);
  const active = filters ?? ownFilters;
  const { step, level, q } = active;
  const visible = useMemo(() => filterLines(lines, { step, level, q }), [lines, step, level, q]);

  const scrollRef = useRef<HTMLDivElement>(null);
  const [following, setFollowing] = useState(true);
  // useVirtualizer returns functions the compiler cannot memoize. Only the filter pass above is memoized by hand.
  // eslint-disable-next-line react-hooks/incompatible-library -- see the comment above the hook call.
  const virtualizer = useVirtualizer({
    count: visible.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 10,
  });

  useLayoutEffect(() => {
    const element = scrollRef.current;
    if (following && element) element.scrollTop = element.scrollHeight;
  }, [following, visible.length]);

  function handleScroll() {
    const element = scrollRef.current;
    if (!element) return;
    setFollowing(element.scrollHeight - element.scrollTop - element.clientHeight <= AT_BOTTOM_PX);
  }

  const hasLines = lines.length > 0;
  let body: ReactNode;
  if (status === "error") {
    body = <LogError onRetry={onRetry} />;
  } else if (!hasLines && status === "connecting") {
    body = <LoadingLines />;
  } else if (!hasLines) {
    body = (
      <EmptyState icon={ScrollText} title="No log lines yet" description="Lines appear here as the run writes them." />
    );
  } else {
    body = (
      <>
        <LogToolbar
          filters={active}
          onChange={onFiltersChange ?? setOwnFilters}
          onDownload={() => downloadLines(lines)}
        />
        <p role="status" aria-live="polite" className="sr-only">
          {announceStep(lines)}
        </p>
        {visible.length === 0 ? (
          <p className="rounded-card border border-border px-4 py-8 text-center text-sm text-fg-muted">
            No lines match these filters.
          </p>
        ) : (
          <div className="relative">
            <div
              ref={scrollRef}
              role="grid"
              aria-label="Log lines"
              aria-rowcount={visible.length}
              tabIndex={0}
              onScroll={handleScroll}
              className="h-[min(70vh,40rem)] overflow-auto rounded-card border border-border bg-bg-subtle font-mono text-xs"
            >
              <div role="rowgroup" className="relative" style={{ height: virtualizer.getTotalSize() }}>
                {virtualizer.getVirtualItems().map((item) => {
                  const line = visible[item.index];
                  if (!line) return null;
                  return (
                    <LogRow
                      key={line.seq}
                      line={line}
                      rowIndex={item.index}
                      start={item.start}
                      query={q}
                    />
                  );
                })}
              </div>
            </div>
            {!following && (
              <Button
                variant="secondary"
                size="sm"
                className="absolute right-3 bottom-3 shadow-overlay"
                onClick={() => setFollowing(true)}
              >
                <ArrowDown aria-hidden="true" className="size-3.5" />
                Jump to latest
              </Button>
            )}
          </div>
        )}
      </>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {status === "reconnecting" && (
        <p role="status" className="rounded-control border border-border bg-bg-subtle px-3 py-2 text-sm text-status-awaiting">
          Reconnecting…
        </p>
      )}
      {body}
    </div>
  );
}

function LogRow({ line, rowIndex, start, query }: { line: LogLine; rowIndex: number; start: number; query: string }) {
  return (
    <div
      role="row"
      aria-rowindex={rowIndex + 1}
      className="absolute top-0 left-0 flex w-full items-center gap-3 px-3 whitespace-pre"
      style={{ height: ROW_HEIGHT, transform: `translateY(${start}px)` }}
    >
      <span role="gridcell" className="shrink-0 tabular-nums text-fg-subtle">
        {line.ts.slice(11, 23)}
      </span>
      <span role="gridcell" className={cn("w-12 shrink-0 font-medium", LEVEL_CLASS[line.level])}>
        {line.level}
      </span>
      <span role="gridcell" className="w-40 shrink-0 truncate text-fg-muted">
        {line.step ? STEP_LABELS[line.step] : ""}
      </span>
      <span role="gridcell" className="min-w-0 flex-1 truncate text-fg">
        {highlight(line.message, query)}
      </span>
      <span role="gridcell" className="shrink-0">
        <IconButton label={`Copy line ${line.seq}`} size="sm" onClick={() => copyLine(line)}>
          <Copy aria-hidden="true" className="size-3.5" />
        </IconButton>
      </span>
    </div>
  );
}

function LoadingLines() {
  return (
    <div aria-busy="true" className="flex flex-col gap-2 rounded-card border border-border p-4">
      <span className="sr-only">Loading logs…</span>
      {[0, 1, 2, 3, 4, 5].map((index) => (
        <Skeleton key={index} className="h-3" style={{ width: `${90 - index * 8}%` }} />
      ))}
    </div>
  );
}

function LogError({ onRetry }: { onRetry?: () => void }) {
  const message = "The log stream could not be opened.";
  if (!onRetry) return <p role="alert" className="rounded-card border border-border px-6 py-10 text-center text-fg">{message}</p>;
  return <ErrorState error={{ message }} onRetry={onRetry} />;
}

/** Names the step only when it changes: the live text changes only then, so a busy log is not read out line by line. */
function announceStep(lines: LogLine[]): string {
  for (let index = lines.length - 1; index >= 0; index--) {
    const step: StepName | null | undefined = lines[index]?.step;
    if (step) return `Now running ${STEP_LABELS[step]}`;
  }
  return "";
}

function highlight(text: string, query: string): ReactNode {
  const needle = query.trim();
  if (!needle) return text;
  // With one capture group, split puts every match at an odd index.
  const parts = text.split(new RegExp(`(${escapeRegExp(needle)})`, "i"));
  return parts.map((part, index) =>
    index % 2 === 1 ? (
      <mark key={index} className="rounded-control bg-status-awaiting/25 text-fg">
        {part}
      </mark>
    ) : (
      part
    ),
  );
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function formatLine(line: LogLine): string {
  return `${line.ts} ${line.level} ${line.step ?? "-"} ${line.message}`;
}

async function copyLine(line: LogLine) {
  try {
    await navigator.clipboard.writeText(formatLine(line));
    toast.success("Line copied");
  } catch {
    toast.error({ message: "The line could not be copied" });
  }
}

function downloadLines(lines: LogLine[]) {
  const href = URL.createObjectURL(new Blob([`${lines.map(formatLine).join("\n")}\n`], { type: "text/plain" }));
  const anchor = document.createElement("a");
  anchor.href = href;
  anchor.download = "run-log.txt";
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(href), 0);
}
