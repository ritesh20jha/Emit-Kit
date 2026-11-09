import type { ComponentPropsWithRef } from "react";
import { cn } from "@/lib/utils/cn";

export type TableProps = ComponentPropsWithRef<"table"> & {
  /** Accessible name: the visually hidden caption, and the name of the scroll region. */
  label: string;
};

/** Dense table (14px, 1px rules). The wrapper scrolls sideways, so it is focusable and named. */
export function Table({ label, className, children, ref, ...props }: TableProps) {
  return (
    <div role="region" aria-label={label} tabIndex={0} className="overflow-x-auto rounded-card border border-border">
      <table ref={ref} className={cn("w-full border-collapse text-left text-sm", className)} {...props}>
        <caption className="sr-only">{label}</caption>
        {children}
      </table>
    </div>
  );
}

export function THead({ className, ...props }: ComponentPropsWithRef<"thead">) {
  return <thead className={cn("bg-bg-subtle", className)} {...props} />;
}

export function TBody({ className, ...props }: ComponentPropsWithRef<"tbody">) {
  return <tbody className={className} {...props} />;
}

export function Tr({ className, ...props }: ComponentPropsWithRef<"tr">) {
  return <tr className={cn("border-b border-border last:border-0 hover:bg-bg-subtle", className)} {...props} />;
}

/** scope="col" by default, so a screen reader announces the column header for each cell. */
export function Th({ className, scope = "col", ...props }: ComponentPropsWithRef<"th">) {
  return <th scope={scope} className={cn("h-8 px-3 text-left align-middle text-xs font-medium text-fg-muted", className)} {...props} />;
}

export function Td({ className, ...props }: ComponentPropsWithRef<"td">) {
  return <td className={cn("h-10 px-3 py-2 align-middle text-fg", className)} {...props} />;
}
