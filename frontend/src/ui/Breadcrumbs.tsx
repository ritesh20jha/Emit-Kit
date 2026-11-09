import { Link } from "@tanstack/react-router";
import { ChevronRight } from "lucide-react";

export interface BreadcrumbItem {
  label: string;
  /** Route the item links to. Items without one, and the last item, are plain text. */
  to?: string;
}

export interface BreadcrumbsProps {
  /** Path from the top of the app to the current page. The last item is the current page. */
  items: BreadcrumbItem[];
}

export function Breadcrumbs({ items }: BreadcrumbsProps) {
  return (
    <nav aria-label="Breadcrumb">
      <ol className="flex flex-wrap items-center gap-1.5 text-sm text-fg-muted">
        {items.map((item, index) => {
          const current = index === items.length - 1;
          return (
            <li key={`${index}-${item.label}`} className="flex items-center gap-1.5">
              {current || !item.to ? (
                <span aria-current={current ? "page" : undefined} className={current ? "font-medium text-fg" : undefined}>{item.label}</span>
              ) : (
                <Link to={item.to} className="rounded-control underline-offset-4 hover:text-fg hover:underline">{item.label}</Link>
              )}
              {!current && <ChevronRight aria-hidden="true" className="size-3.5 shrink-0" />}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
