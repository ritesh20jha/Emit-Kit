import { useQuery } from "@tanstack/react-query";
import { GitCompare } from "lucide-react";
import { QueryBody } from "@/features/projects";
import { EmptyState } from "@/ui";
import { type SnapshotSummary, type SpecDiff, snapshotsQuery, specDiffQuery } from "./api";
import { DiffSummary } from "./DiffSummary";
import type { DiffSearch } from "./schemas";
import { SnapshotPicker } from "./SnapshotPicker";
import { summaryTexts } from "./spec-lines";
import { TextDiff } from "./TextDiff";

export interface SpecDiffPageProps {
  projectId: string;
  search: DiffSearch;
  /** Writes the next search. Choosing a version adds a history entry. */
  onSearchChange: (next: DiffSearch) => void;
}

/** The changes between two saved versions of the spec. The versions come from the URL and default to the last two. */
export function SpecDiffPage({ projectId, search, onSearchChange }: SpecDiffPageProps) {
  const snapshots = useQuery(snapshotsQuery(projectId));
  return (
    <QueryBody query={snapshots} label="the saved versions" noAccess="view this project">
      {(list) =>
        list.length < 2 ? (
          <EmptyState icon={GitCompare} title="Nothing to compare yet" description="A comparison needs two saved versions of the spec. Each save adds one." />
        ) : (
          <Comparison projectId={projectId} snapshots={list} search={search} onSearchChange={onSearchChange} />
        )
      }
    </QueryBody>
  );
}

function Comparison({ projectId, snapshots, search, onSearchChange }: Omit<SpecDiffPageProps, "search"> & { snapshots: SnapshotSummary[]; search: DiffSearch }) {
  const to = search.to ?? snapshots[0]?.id ?? "";
  const from = search.from ?? snapshots[1]?.id ?? "";
  const diff = useQuery(specDiffQuery(projectId, from, to));
  return (
    <div className="flex flex-col gap-4">
      <SnapshotPicker snapshots={snapshots} from={from} to={to} onChange={onSearchChange} />
      <QueryBody query={diff} label="the changes" noAccess="view this project">
        {(changes) => (changes.added.length + changes.removed.length + changes.modified.length === 0 ? <NoChanges /> : <Changes diff={changes} />)}
      </QueryBody>
    </div>
  );
}

function Changes({ diff }: { diff: SpecDiff }) {
  const texts = summaryTexts(diff);
  return (
    <div className="flex flex-col gap-6">
      <DiffSummary diff={diff} />
      <TextDiff oldText={texts.oldText} newText={texts.newText} />
    </div>
  );
}

function NoChanges() {
  return <EmptyState icon={GitCompare} title="No changes" description="These two versions describe the same operations." />;
}
