import { Search } from "lucide-react";
import type { ReactNode } from "react";
import type { components } from "@/lib/api/generated/schema";
import { Kbd } from "@/ui";
import { Brand } from "./Brand";
import { QuotaIndicator } from "./QuotaIndicator";
import { ThemeMenu } from "./ThemeMenu";
import { UserMenu } from "./UserMenu";

type Me = components["schemas"]["MeResponse"];

export interface TopBarProps {
  user: Pick<Me, "login" | "name" | "avatarUrl">;
  /** Leading control, e.g. the drawer button on narrow screens. */
  menu?: ReactNode;
  /** Opens the command palette. Without it the search affordance is not shown. */
  onSearch?: () => void;
}

const isMac = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform);

export function TopBar({ user, menu, onSearch }: TopBarProps) {
  return (
    <header className="sticky top-0 z-30 flex h-14 shrink-0 items-center justify-between gap-3 border-b border-border bg-bg/85 px-4 backdrop-blur-sm md:px-6">
      <div className="flex min-w-0 flex-1 items-center gap-2">
        {menu}
        {/* The rail carries the wordmark from 768 px up; below it the bar does. */}
        {menu && <Brand />}
        {onSearch && (
          <button
            type="button"
            onClick={onSearch}
            aria-keyshortcuts={isMac ? "Meta+K" : "Control+K"}
            className="ml-auto hidden h-9 w-full max-w-sm items-center gap-2 rounded-control border border-border-strong bg-surface pr-1.5 pl-2.5 text-sm text-fg-subtle shadow-raised transition-colors duration-(--duration-fast) hover:border-border-control hover:text-fg-muted sm:flex md:mr-auto md:ml-0"
          >
            <Search aria-hidden="true" className="size-4 shrink-0" />
            <span className="flex-1 truncate text-left">Search projects, runs and commands</span>
            <span aria-hidden="true" className="flex items-center gap-0.5">
              <Kbd>{isMac ? "⌘" : "Ctrl"}</Kbd>
              <Kbd>K</Kbd>
            </span>
          </button>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-3">
        <QuotaIndicator />
        <ThemeMenu />
        <UserMenu user={user} />
      </div>
    </header>
  );
}
