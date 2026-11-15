import { Select, type SelectOption } from "@/ui";
import type { Role } from "./api";

/** Keyed by Role, so a role added to the API fails to compile here until it gets an option. */
const ROLE_LABELS: Record<Role, string> = { ADMIN: "ADMIN", EDITOR: "EDITOR", VIEWER: "VIEWER" };
const ROLE_OPTIONS: SelectOption[] = (Object.keys(ROLE_LABELS) as Role[]).map((role) => ({
  value: role,
  label: ROLE_LABELS[role],
}));

export interface RoleSelectProps {
  login: string;
  role: Role;
  onChange: (role: Role) => void;
}

/** Inline role picker for one member. Named by the member's login, since the row is the context. */
export function RoleSelect({ login, role, onChange }: RoleSelectProps) {
  return (
    <Select
      aria-label={`Role for ${login}`}
      options={ROLE_OPTIONS}
      value={role}
      onValueChange={(next) => onChange(next as Role)}
      className="w-28"
    />
  );
}
