import type { ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Users } from "lucide-react";
import { AppError } from "@/lib/api/appError";
import { usePermission } from "@/lib/auth/permissions";
import { EmptyState, ErrorState, NoAccessState, PageHeader, Skeleton } from "@/ui";
import { membersQuery, useChangeRole } from "./api";
import { MembersTable } from "./MembersTable";

export interface MembersPageProps {
  orgId: string;
}

/** Members of one org with the four states. Only admins can change roles; everyone else reads them. */
export function MembersPage({ orgId }: MembersPageProps) {
  const query = useQuery(membersQuery(orgId));
  const canEditRoles = usePermission("MEMBER_ROLE_EDIT", orgId);
  const changeRole = useChangeRole(orgId);

  let body: ReactNode;
  if (query.isPending) {
    body = (
      <div role="status" aria-busy="true" aria-label="Loading members" className="flex flex-col gap-2">
        <Skeleton className="h-10" />
        <Skeleton className="h-10" />
        <Skeleton className="h-10" />
      </div>
    );
  } else if (query.isError) {
    const feedback = query.error instanceof AppError ? query.error : { message: "Something went wrong. Try again." };
    body = <ErrorState error={feedback} onRetry={() => void query.refetch()} />;
  } else if (query.data.length === 0) {
    body = (
      <EmptyState
        icon={Users}
        title="No members yet"
        description="Members appear here once GitHub sync adds them to this organization."
      />
    );
  } else {
    const admins = query.data.filter((member) => member.role === "ADMIN").map((member) => member.login);
    body = (
      <div className="flex flex-col gap-4">
        {!canEditRoles && <NoAccessState action="change member roles" admins={admins} />}
        <MembersTable
          members={query.data}
          canEditRoles={canEditRoles}
          onRoleChange={(userId, role) => changeRole.mutate({ userId, role })}
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Members" description="People with access to this organization." />
      {body}
    </div>
  );
}
