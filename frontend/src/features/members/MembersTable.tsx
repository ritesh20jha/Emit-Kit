import { ResponsiveTable, type ResponsiveColumn } from "@/ui";
import { type Member, type Role } from "./api";
import { RoleSelect } from "./RoleSelect";

const SOURCE_LABEL: Record<Member["roleSource"], string> = { SYNC: "GitHub sync", MANUAL: "Set manually" };

export interface MembersTableProps {
  members: Member[];
  canEditRoles: boolean;
  onRoleChange: (userId: string, role: Role) => void;
}

/** The member list: a table from 768 px, cards below. Roles are selects only for users who may change them. */
export function MembersTable({ members, canEditRoles, onRoleChange }: MembersTableProps) {
  const roleCell = (member: Member) =>
    canEditRoles ? (
      <RoleSelect login={member.login} role={member.role} onChange={(role) => onRoleChange(member.userId, role)} />
    ) : (
      member.role
    );

  const columns: ResponsiveColumn<Member>[] = [
    { key: "member", header: "Member", cell: (member) => <MemberName member={member} /> },
    { key: "role", header: "Role", cell: roleCell },
    { key: "source", header: "Source", cell: (member) => SOURCE_LABEL[member.roleSource] },
  ];

  return (
    <ResponsiveTable
      label="Members"
      columns={columns}
      rows={members}
      rowKey={(member) => member.userId}
      renderCard={(member) => (
        <div className="flex flex-col gap-2">
          <MemberName member={member} />
          <div className="flex items-center justify-between gap-2 text-sm">
            <span className="text-fg-muted">Role</span>
            {roleCell(member)}
          </div>
          <div className="flex items-center justify-between gap-2 text-sm">
            <span className="text-fg-muted">Source</span>
            <span>{SOURCE_LABEL[member.roleSource]}</span>
          </div>
        </div>
      )}
    />
  );
}

function MemberName({ member }: { member: Member }) {
  return (
    <div className="flex min-w-0 items-center gap-2">
      <img src={member.avatarUrl} alt="" className="size-6 shrink-0 rounded-full" />
      <span className="font-medium text-fg">{member.login}</span>
      {member.name && <span className="truncate text-fg-muted">{member.name}</span>}
    </div>
  );
}
