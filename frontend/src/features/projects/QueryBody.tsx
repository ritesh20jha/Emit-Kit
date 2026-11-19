import type { UseQueryResult } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { AppError, failureFeedback } from "@/lib/api/appError";
import { ErrorState, NoAccessState, Skeleton } from "@/ui";

/**
 * The loading, error and no-permission states of one query. Once data is in, `children` renders it, and an empty
 * result is the child's to show. A 403 is a permission state, not a failure.
 */
export function QueryBody<T>({
  query,
  label,
  noAccess,
  children,
}: {
  query: UseQueryResult<T, Error>;
  /** Named to the screen reader while loading: "the last run". */
  label: string;
  /** The blocked action for the no-access state: "view this project". */
  noAccess: string;
  children: (data: T) => ReactNode;
}) {
  if (query.isError) {
    if (query.error instanceof AppError && query.error.status === 403) return <NoAccessState action={noAccess} />;
    return <ErrorState error={failureFeedback(query.error)} onRetry={() => void query.refetch()} />;
  }
  if (query.isPending) {
    return (
      <div aria-busy="true" className="flex flex-col gap-2">
        <span className="sr-only">Loading {label}…</span>
        <Skeleton className="h-12" />
      </div>
    );
  }
  return <>{children(query.data)}</>;
}
