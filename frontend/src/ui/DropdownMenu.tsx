import * as DropdownMenuPrimitive from "@radix-ui/react-dropdown-menu";
import type { ComponentPropsWithRef, ReactNode } from "react";
import { cn } from "@/lib/utils/cn";

export interface DropdownMenuProps {
  /** The opener. A Button works; the menu opens on click, Enter, Space or ArrowDown. */
  trigger: ReactNode;
  align?: "start" | "center" | "end";
  defaultOpen?: boolean;
  children: ReactNode;
}

/** An action menu. Keyboard navigation and typeahead come from Radix. */
export function DropdownMenu({ trigger, align = "start", defaultOpen, children }: DropdownMenuProps) {
  return (
    <DropdownMenuPrimitive.Root defaultOpen={defaultOpen}>
      <DropdownMenuPrimitive.Trigger asChild>{trigger}</DropdownMenuPrimitive.Trigger>
      <DropdownMenuPrimitive.Portal>
        <DropdownMenuPrimitive.Content
          align={align}
          sideOffset={4}
          className="z-50 min-w-40 overflow-hidden rounded-control border border-border-control bg-bg p-1 text-fg shadow-overlay motion-safe:animate-overlay-fade"
        >
          {children}
        </DropdownMenuPrimitive.Content>
      </DropdownMenuPrimitive.Portal>
    </DropdownMenuPrimitive.Root>
  );
}

export type DropdownMenuItemProps = ComponentPropsWithRef<typeof DropdownMenuPrimitive.Item> & {
  tone?: "default" | "danger";
};

/** A menu action. The highlight (data-highlighted) marks the item that keyboard focus is on. */
export function DropdownMenuItem({ tone = "default", className, ...props }: DropdownMenuItemProps) {
  return (
    <DropdownMenuPrimitive.Item
      className={cn(
        "relative flex h-8 cursor-default items-center rounded-control px-2 text-sm select-none data-disabled:pointer-events-none data-disabled:opacity-50 data-highlighted:bg-bg-muted",
        tone === "danger" ? "text-status-failed" : "text-fg",
        className,
      )}
      {...props}
    />
  );
}

export function DropdownMenuSeparator({ className, ...props }: ComponentPropsWithRef<typeof DropdownMenuPrimitive.Separator>) {
  return <DropdownMenuPrimitive.Separator className={cn("-mx-1 my-1 h-px bg-border", className)} {...props} />;
}
