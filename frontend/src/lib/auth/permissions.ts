import type { ReactNode } from "react";
import type { components } from "@/lib/api/generated/schema";
import { useMe } from "./session";

type Role = components["schemas"]["Role"];

export type Permission =
  | "ORG_VIEW"
  | "PROJECT_VIEW"
  | "RUN_VIEW"
  | "SPEC_VIEW"
  | "PROJECT_CREATE"
  | "PROJECT_DELETE"
  | "CONFIG_EDIT"
  | "SPEC_EDIT"
  | "RUN_TRIGGER"
  | "RUN_CANCEL"
  | "RUN_RERUN"
  | "RUN_APPROVE"
  | "MEMBER_ROLE_EDIT";

/** Copied from the backend's role table (v1 spec §3). The permissions test checks every role against it. */
const ROLE_PERMISSIONS: Record<Role, readonly Permission[]> = {
  ADMIN: [
    "ORG_VIEW", "PROJECT_VIEW", "RUN_VIEW", "SPEC_VIEW", "PROJECT_CREATE", "PROJECT_DELETE", "CONFIG_EDIT",
    "SPEC_EDIT", "RUN_TRIGGER", "RUN_CANCEL", "RUN_RERUN", "RUN_APPROVE", "MEMBER_ROLE_EDIT",
  ],
  EDITOR: [
    "ORG_VIEW", "PROJECT_VIEW", "RUN_VIEW", "SPEC_VIEW", "PROJECT_CREATE", "SPEC_EDIT",
    "RUN_TRIGGER", "RUN_CANCEL", "RUN_RERUN",
  ],
  VIEWER: ["ORG_VIEW", "PROJECT_VIEW", "RUN_VIEW", "SPEC_VIEW"],
};

/** Whether the signed-in user may do `p` in `orgId`. False for non-members and while /me is loading. */
export function usePermission(p: Permission, orgId: string): boolean {
  const { data } = useMe();
  const role = data?.orgs.find((org) => org.orgId === orgId)?.role;
  return role !== undefined && ROLE_PERMISSIONS[role].includes(p);
}

/** Renders `children` when the user has `permission` in `orgId`, otherwise `fallback`. */
export function Can({
  permission,
  orgId,
  children,
  fallback = null,
}: {
  permission: Permission;
  orgId: string;
  children: ReactNode;
  fallback?: ReactNode;
}): ReactNode {
  return usePermission(permission, orgId) ? children : fallback;
}
