import type { ReactNode } from "react";

export interface PageHeaderProps {
  title: string;
  /** One line under the title. */
  description?: ReactNode;
  /** Primary actions, right-aligned; they wrap below the title on narrow screens. */
  actions?: ReactNode;
}

/** Top of a page: the h1, an optional description and the primary actions. */
export function PageHeader({ title, description, actions }: PageHeaderProps) {
  return (
    <header className="flex flex-wrap items-start justify-between gap-4">
      <div className="flex min-w-0 flex-col gap-1">
        <h1 className="break-words text-2xl font-semibold text-fg">{title}</h1>
        {description && <p className="text-sm text-fg-muted">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </header>
  );
}
