import { Select, type SelectOption } from "@/ui";
import type { Role } from "./api";

const ROLE_OPTIONS: SelectOption[] = (["ADMIN", "EDITOR", "VIEWER"] as const).map((role) => ({ value: role, label: role }));

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
