import type { ReactNode } from "react";

export interface PageHeaderProps {
  title: string;
  /** A short mono label above the title, e.g. the section or the org. */
  eyebrow?: ReactNode;
  /** One line under the title. */
  description?: ReactNode;
  /** Primary actions, right-aligned; they wrap below the title on narrow screens. */
  actions?: ReactNode;
}

/** Top of a page: the h1, an optional description and the primary actions. */
export function PageHeader({ title, eyebrow, description, actions }: PageHeaderProps) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-4">
      <div className="flex min-w-0 flex-col gap-1">
        {eyebrow && <p className="font-mono text-2xs font-medium tracking-[0.06em] text-fg-subtle uppercase">{eyebrow}</p>}
        <h1 className="min-w-0 text-2xl font-semibold tracking-tight text-fg">{title}</h1>
        {description && <p className="max-w-prose text-sm text-fg-muted">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </header>
  );
}
