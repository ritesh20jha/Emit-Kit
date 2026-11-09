import { Link } from "@tanstack/react-router";
import { type KeyboardEvent, useRef } from "react";

export interface RouteTab {
  to: string;
  label: string;
}

export interface RouteTabsProps {
  tabs: RouteTab[];
  /** Accessible name of the navigation. */
  label?: string;
}

/**
 * Section tabs that are routes: each tab is a real link, so the URL and the active tab stay in step, and every link
 * stays in the tab order. Arrow keys, Home and End also move focus between the links.
 */
export function RouteTabs({ tabs, label = "Sections" }: RouteTabsProps) {
  const links = useRef<(HTMLAnchorElement | null)[]>([]);

  function moveFocus(event: KeyboardEvent<HTMLUListElement>) {
    const current = links.current.findIndex((link) => link === event.target);
    const last = tabs.length - 1;
    const target: Record<string, number> = {
      ArrowRight: current === last ? 0 : current + 1,
      ArrowLeft: current <= 0 ? last : current - 1,
      Home: 0,
      End: last,
    };
    const next = target[event.key];
    if (current < 0 || next === undefined) return;
    event.preventDefault();
    links.current[next]?.focus();
  }

  return (
    <nav aria-label={label} className="overflow-x-auto">
      <ul onKeyDown={moveFocus} className="flex w-max min-w-full gap-1 border-b border-border">
        {tabs.map((tab, index) => (
          <li key={tab.to}>
            <Link
              ref={(link) => {
                links.current[index] = link;
              }}
              to={tab.to}
              activeOptions={{ exact: true, includeSearch: false }}
              className="-mb-px inline-flex h-9 items-center border-b-2 border-transparent px-3 text-sm font-medium text-fg-muted transition-colors duration-(--duration-fast) hover:text-fg aria-[current=page]:border-accent aria-[current=page]:text-fg"
            >
              {tab.label}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
