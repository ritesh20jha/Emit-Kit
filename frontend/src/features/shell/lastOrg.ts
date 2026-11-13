import type { components } from "@/lib/api/generated/schema";

type OrgMembership = components["schemas"]["OrgMembership"];

export const LAST_ORG_KEY = "emitkit.lastOrg";

/** The org the user last opened, kept per browser. Storage can be blocked (private mode), so every access is guarded. */
export const lastOrg = {
  get(): string | undefined {
    try {
      return window.localStorage.getItem(LAST_ORG_KEY) ?? undefined;
    } catch {
      return undefined;
    }
  },
  set(orgId: string) {
    try {
      window.localStorage.setItem(LAST_ORG_KEY, orgId);
    } catch {
      // Remembering is a convenience: without storage the user just lands on the personal org next time.
    }
  },
};

/** The org to open by default: the remembered one while it is still a membership, else the personal org. */
export function defaultOrgId(orgs: OrgMembership[], remembered: string | undefined): string | undefined {
  const match = orgs.find((org) => org.orgId === remembered);
  return (match ?? orgs.find((org) => org.isPersonal) ?? orgs[0])?.orgId;
}
