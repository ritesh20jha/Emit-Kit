import { Link } from "@tanstack/react-router";
import { FolderGit2, Settings, Users } from "lucide-react";
import type { components } from "@/lib/api/generated/schema";
import { Brand } from "./Brand";
import { OrgSwitcher } from "./OrgSwitcher";

type OrgMembership = components["schemas"]["OrgMembership"];

const SECTIONS = [
  { label: "Projects", path: "", icon: FolderGit2 },
  { label: "Members", path: "/members", icon: Users },
  { label: "Settings", path: "/settings", icon: Settings },
];

export interface SidebarProps {
  orgs: OrgMembership[];
  currentOrgId?: string;
  /** Runs when a link inside the navigation is followed, so a drawer can close. */
  onNavigate?: () => void;
}

/** The wordmark, the org switcher, then the sections of the current org. */
export function Sidebar({ orgs, currentOrgId, onNavigate }: SidebarProps) {
  return (
    <div className="flex flex-col gap-5 p-3">
      <Link to="/" aria-label="EmitKit home" onClick={onNavigate} className="flex h-8 items-center rounded-control px-2 self-start">
        <Brand />
      </Link>
      <div className="flex flex-col gap-1.5">
        <p className="px-2 font-mono text-2xs font-medium tracking-[0.06em] text-fg-subtle uppercase">Organization</p>
        <OrgSwitcher orgs={orgs} currentOrgId={currentOrgId} onNavigate={onNavigate} />
      </div>
      {currentOrgId && (
        <nav aria-label="Organization" onClick={onNavigate} className="flex flex-col gap-0.5">
          {SECTIONS.map((section) => (
            <Link
              key={section.label}
              // The org section routes arrive with WEB-04/05, so the typed router does not know these paths yet.
              to={`/orgs/${currentOrgId}${section.path}` as "/"}
              // Exact, so Projects (the org root) is not also current on /members and /settings.
              activeOptions={{ exact: true }}
              className="group relative flex h-8 items-center gap-2.5 rounded-control px-2 text-sm font-medium text-fg-muted transition-colors duration-(--duration-fast) hover:bg-bg-muted hover:text-fg aria-[current=page]:bg-bg-muted aria-[current=page]:text-fg"
            >
              {/* The accent tick marks the current section; the icon takes the accent too. */}
              <span aria-hidden="true" className="absolute top-1.5 bottom-1.5 -left-3 w-0.5 rounded-full bg-accent opacity-0 group-aria-[current=page]:opacity-100" />
              <section.icon aria-hidden="true" className="size-4 shrink-0 text-fg-subtle group-hover:text-fg-muted group-aria-[current=page]:text-accent" />
              {section.label}
            </Link>
          ))}
        </nav>
      )}
    </div>
  );
}
