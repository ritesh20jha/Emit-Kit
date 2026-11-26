import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ChevronDown, Tag } from "lucide-react";
import { useId, useState } from "react";
import { QueryBody } from "@/features/projects";
import { cn } from "@/lib/utils/cn";
import { formatDate } from "@/lib/utils/dates";
import { Badge, EmptyState } from "@/ui";
import { changeTypeLabel, type VersionResponse, versionsQuery } from "./api";

/** Version history, newest first. Each row expands to its changelog. */
export function VersionsList({ projectId }: { projectId: string }) {
  const query = useQuery(versionsQuery(projectId));
  return (
    <div className="flex flex-col gap-3">
      <h2 className="text-sm font-medium text-fg">Version history</h2>
      <QueryBody query={query} label="the version history" noAccess="view this project">
        {(versions) =>
          versions.length === 0 ? (
            <EmptyState icon={Tag} title="No versions yet" description="The first successful run publishes a version." />
          ) : (
            <ul className="divide-y divide-border rounded-card border border-border">
              {versions.map((version) => (
                <VersionRow key={version.version} version={version} />
              ))}
            </ul>
          )
        }
      </QueryBody>
    </div>
  );
}

/** A changelog as plain text blocks, split at blank lines. It is never parsed as HTML or markdown. */
const blocksOf = (changelog: string) =>
  changelog
    .split(/\n\s*\n/)
    .map((block) => block.trim())
    .filter(Boolean);

function VersionRow({ version }: { version: VersionResponse }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const blocks = blocksOf(version.changelog);
  return (
    <li>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 text-sm">
        <button
          type="button"
          aria-expanded={open}
          aria-controls={open ? panelId : undefined}
          onClick={() => setOpen(!open)}
          className="inline-flex items-center gap-2 rounded-control font-mono text-fg"
        >
          <ChevronDown aria-hidden="true" className={cn("size-4 shrink-0 transition-transform duration-(--duration-fast)", open && "rotate-180")} />
          {version.version}
        </button>
        <Badge>{changeTypeLabel(version.changeType)}</Badge>
        <time dateTime={version.createdAt} className="text-fg-muted">
          {formatDate(version.createdAt)}
        </time>
        {/* The run page arrives with WEB-13, so the typed router does not know this path yet. */}
        <Link to={`/runs/${version.runId}` as "/"} className="ml-auto rounded-control text-fg underline-offset-4 hover:underline">
          View run
        </Link>
      </div>
      {open && (
        <div id={panelId} className="flex flex-col gap-2 border-t border-border px-4 py-3 text-sm text-fg">
          {blocks.length === 0 && <p className="text-fg-muted">No changelog for this version.</p>}
          {blocks.map((block, index) => (
            <p key={index} className="whitespace-pre-wrap">
              {block}
            </p>
          ))}
        </div>
      )}
    </li>
  );
}
