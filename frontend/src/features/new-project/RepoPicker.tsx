import { type UseQueryResult, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useController, useFormContext } from "react-hook-form";
import { failureFeedback } from "@/lib/api/appError";
import { Badge, ErrorState, FieldError, Input, Skeleton } from "@/ui";
import { type RepoOption, reposQuery } from "./api";
import { CHECK_DELAY_MS, useDebouncedValue } from "./debounce";
import type { WizardValues } from "./schemas";

export interface RepoPickerProps {
  orgId: string;
  /** The group's accessible name, e.g. "Existing repositories". */
  label: string;
  /** The full name of the chosen repository, or "" for none. */
  value: string;
  onChoose: (repo: RepoOption) => void;
}

/** A searchable list of the repositories the user can see. Each row shows its visibility and last push. */
export function RepoPicker({ orgId, label, value, onChoose }: RepoPickerProps) {
  const [text, setText] = useState("");
  const q = useDebouncedValue(text.trim(), CHECK_DELAY_MS);
  const query = useQuery(reposQuery(orgId, q));

  return (
    <div role="group" aria-label={label} className="flex flex-col gap-2">
      <Input
        type="search"
        aria-label="Search repositories"
        placeholder="Search repositories"
        value={text}
        onChange={(event) => setText(event.target.value)}
        // Enter would submit the wizard; in this box it has nothing to do.
        onKeyDown={(event) => {
          if (event.key === "Enter") event.preventDefault();
        }}
      />
      <RepoRows query={query} q={q} value={value} onChoose={onChoose} />
    </div>
  );
}

/** Loading, error, empty and ready. The list keeps its previous rows while a new search loads. */
function RepoRows({
  query,
  q,
  value,
  onChoose,
}: {
  query: UseQueryResult<RepoOption[], Error>;
  q: string;
  value: string;
  onChoose: (repo: RepoOption) => void;
}) {
  if (query.isPending) {
    return (
      <div aria-busy="true" className="flex flex-col gap-2">
        <span className="sr-only">Loading repositories…</span>
        <Skeleton className="h-10" />
        <Skeleton className="h-10" />
      </div>
    );
  }
  if (query.isError) {
    return <ErrorState error={failureFeedback(query.error)} onRetry={() => void query.refetch()} />;
  }
  if (query.data.length === 0) {
    return <p className="text-sm text-fg-muted">{q ? `No repositories match “${q}”.` : "No repositories yet."}</p>;
  }
  return (
    <ul className="flex max-h-72 flex-col gap-1 overflow-y-auto">
      {query.data.map((repo) => (
        <li key={repo.fullName}>
          <button
            type="button"
            aria-pressed={repo.fullName === value}
            onClick={() => onChoose(repo)}
            className="flex w-full flex-wrap items-center justify-between gap-2 rounded-control border border-border px-3 py-2 text-left hover:bg-bg-muted aria-pressed:border-accent aria-pressed:bg-accent/10"
          >
            <span className="font-mono text-sm text-fg">{repo.fullName}</span>
            <span className="flex items-center gap-2 text-xs text-fg-muted">
              <Badge>{repo.isPrivate ? "Private" : "Public"}</Badge>
              {repo.pushedAt && <time dateTime={repo.pushedAt}>pushed {new Date(repo.pushedAt).toLocaleDateString()}</time>}
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}

export interface RepoPickerFieldProps {
  name: "repo" | "outputRepo";
  label: string;
  orgId: string;
  onChoose?: (repo: RepoOption) => void;
}

/** A RepoPicker bound to a form field: the choice is written to the field, and the field's error sits under the list. */
export function RepoPickerField({ name, label, orgId, onChoose }: RepoPickerFieldProps) {
  const { control, clearErrors } = useFormContext<WizardValues>();
  const { field, fieldState } = useController({ control, name });
  return (
    <div className="flex flex-col gap-1.5">
      <RepoPicker
        orgId={orgId}
        label={label}
        value={field.value}
        onChoose={(repo) => {
          field.onChange(repo.fullName);
          clearErrors(name);
          onChoose?.(repo);
        }}
      />
      {fieldState.error && <FieldError>{fieldState.error.message}</FieldError>}
    </div>
  );
}
