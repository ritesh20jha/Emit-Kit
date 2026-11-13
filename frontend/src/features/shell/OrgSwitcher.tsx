import { useNavigate } from "@tanstack/react-router";
import { Building, ChevronsUpDown, Search } from "lucide-react";
import { useState } from "react";
import type { components } from "@/lib/api/generated/schema";
import { Button, Dialog, EmptyState, Input } from "@/ui";
import { lastOrg } from "./lastOrg";

type OrgMembership = components["schemas"]["OrgMembership"];

/** Above this many orgs the list gets a search field. */
const SEARCH_THRESHOLD = 7;

/** Switches the current org: the URL changes and the choice is remembered for the next visit. */
export function OrgSwitcher({ orgs, currentOrgId }: { orgs: OrgMembership[]; currentOrgId?: string }) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const current = orgs.find((org) => org.orgId === currentOrgId);

  const needle = query.trim().toLowerCase();
  const matches = orgs.filter((org) => org.name.toLowerCase().includes(needle));

  function select(orgId: string) {
    lastOrg.set(orgId);
    setOpen(false);
    setQuery("");
    void navigate({ href: `/orgs/${orgId}` });
  }

  return (
    <Dialog
      open={open}
      onOpenChange={setOpen}
      title="Switch organization"
      trigger={
        <Button variant="ghost" className="w-full justify-start px-2">
          <span className="truncate">{current?.name ?? "Choose organization"}</span>
          <ChevronsUpDown aria-hidden="true" className="size-4 shrink-0 text-fg-subtle" />
        </Button>
      }
    >
      {orgs.length > SEARCH_THRESHOLD && (
        <div className="relative">
          <Search aria-hidden="true" className="absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-fg-subtle" />
          <Input
            type="search"
            aria-label="Search organizations"
            placeholder="Search organizations"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            className="pl-8"
          />
        </div>
      )}
      {matches.length === 0 ? (
        <EmptyState
          icon={Building}
          title={needle ? `No organizations match "${query.trim()}"` : "No organizations yet"}
        />
      ) : (
        <ul className="flex flex-col gap-0.5">
          {matches.map((org) => (
            <li key={org.orgId}>
              <Button
                variant="ghost"
                className="w-full justify-start"
                aria-current={org.orgId === currentOrgId ? "true" : undefined}
                onClick={() => select(org.orgId)}
              >
                {org.name}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Dialog>
  );
}
