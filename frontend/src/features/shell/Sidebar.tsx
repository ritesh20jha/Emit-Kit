import { Link } from "@tanstack/react-router";
import type { components } from "@/lib/api/generated/schema";
import { OrgSwitcher } from "./OrgSwitcher";

type OrgMembership = components["schemas"]["OrgMembership"];

const SECTIONS = [
  { label: "Projects", path: "" },
  { label: "Members", path: "/members" },
  { label: "Settings", path: "/settings" },
];

export interface SidebarProps {
  orgs: OrgMembership[];
  currentOrgId?: string;
  /** Runs when a link inside the navigation is followed, so a drawer can close. */
  onNavigate?: () => void;
}

/** Org switcher on top, then the sections of the current org. */
export function Sidebar({ orgs, currentOrgId, onNavigate }: SidebarProps) {
  return (
    <div className="flex flex-col gap-4 p-3">
      <OrgSwitcher orgs={orgs} currentOrgId={currentOrgId} onNavigate={onNavigate} />
      {currentOrgId && (
        <nav aria-label="Organization" onClick={onNavigate} className="flex flex-col gap-0.5">
          {SECTIONS.map((section) => (
            <Link
              key={section.label}
              // The org section routes arrive with WEB-04/05, so the typed router does not know these paths yet.
              to={`/orgs/${currentOrgId}${section.path}` as "/"}
              // Exact, so Projects (the org root) is not also current on /members and /settings.
              activeOptions={{ exact: true }}
              className="flex h-8 items-center rounded-control px-2 text-sm text-fg-muted hover:bg-bg-muted hover:text-fg aria-[current=page]:bg-bg-muted aria-[current=page]:text-fg"
            >
              {section.label}
            </Link>
          ))}
        </nav>
      )}
    </div>
  );
}
