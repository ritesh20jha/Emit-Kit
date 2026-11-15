import type { ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { AppError } from "@/lib/api/appError";
import { Button, CopyButton, ErrorState, PageHeader, Skeleton } from "@/ui";
import { orgQuery, useResync } from "./api";

export interface OrgSettingsProps {
  orgId: string;
}

/** Org details and the GitHub re-sync. Every member can read these, so there is no permission state. */
export function OrgSettings({ orgId }: OrgSettingsProps) {
  const query = useQuery(orgQuery(orgId));
  const resync = useResync();

  let body: ReactNode;
  if (query.isPending) {
    body = (
      <div role="status" aria-busy="true" aria-label="Loading settings">
        <Skeleton className="h-32" />
      </div>
    );
  } else if (query.isError) {
    const feedback = query.error instanceof AppError ? query.error : { message: "Something went wrong. Try again." };
    body = <ErrorState error={feedback} onRetry={() => void query.refetch()} />;
  } else {
    const org = query.data;
    body = (
      <div className="flex flex-col gap-4">
        <dl className="grid grid-cols-[max-content_1fr] items-center gap-x-6 gap-y-3 rounded-card border border-border p-4 text-sm">
          <dt className="text-fg-muted">Slug</dt>
          <dd className="font-mono text-fg">{org.slug}</dd>
          <dt className="text-fg-muted">GitHub ID</dt>
          <dd className="flex flex-wrap items-center gap-2">
            {org.vcsOrgId ? (
              <>
                <code className="font-mono text-fg">{org.vcsOrgId}</code>
                <CopyButton value={org.vcsOrgId} label="Copy GitHub ID" />
              </>
            ) : (
              <span className="text-fg-muted">Not linked to GitHub</span>
            )}
          </dd>
          <dt className="text-fg-muted">Members</dt>
          <dd className="text-fg">{org.memberCount}</dd>
        </dl>
        <div>
          <Button variant="secondary" disabled={resync.isPending} onClick={() => resync.mutate()}>
            Re-sync from GitHub
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Settings" description="Organization details and how they are kept in step with GitHub." />
      {body}
    </div>
  );
}
