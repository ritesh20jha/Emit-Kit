import * as DialogPrimitive from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils/cn";
import { IconButton } from "./IconButton";

const PANEL = "fixed z-50 flex flex-col gap-4 border-border-control bg-bg p-5 text-fg shadow-overlay";

const CENTER =
  "left-1/2 top-1/2 w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-card border motion-safe:animate-dialog-in";

export interface DialogFrameProps {
  open?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
  /** The opener. Focus returns to it when the dialog closes. */
  trigger?: ReactNode;
  title: ReactNode;
  /** Under the title. Leave it out only when there is nothing to add: no description is wired then. */
  description?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  /** False blocks Escape, outside clicks and the close button, e.g. while a confirmation is running. */
  dismissible?: boolean;
  /** Places and sizes the panel. */
  panelClassName: string;
}

/** The overlay, panel, title, description and close button shared by Dialog, ConfirmDialog and Sheet. */
export function DialogFrame({
  open,
  defaultOpen,
  onOpenChange,
  trigger,
  title,
  description,
  children,
  footer,
  dismissible = true,
  panelClassName,
  ...panelProps
}: DialogFrameProps) {
  return (
    <DialogPrimitive.Root
      open={open}
      defaultOpen={defaultOpen}
      onOpenChange={(next) => {
        if (!next && !dismissible) return;
        onOpenChange?.(next);
      }}
    >
      {trigger && <DialogPrimitive.Trigger asChild>{trigger}</DialogPrimitive.Trigger>}
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-fg/40 motion-safe:animate-overlay-fade" />
        <DialogPrimitive.Content className={cn(PANEL, panelClassName)} {...panelProps}>
          <div className="flex flex-col gap-1 pr-8">
            <DialogPrimitive.Title className="text-base font-semibold text-fg">{title}</DialogPrimitive.Title>
            {description !== undefined && (
              <DialogPrimitive.Description className="text-sm text-fg-muted">{description}</DialogPrimitive.Description>
            )}
          </div>
          {children}
          {footer && <div className="flex justify-end gap-2">{footer}</div>}
          <DialogPrimitive.Close asChild>
            <IconButton label="Close" variant="ghost" size="sm" className="absolute top-3 right-3">
              <X aria-hidden="true" className="size-4" />
            </IconButton>
          </DialogPrimitive.Close>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}

export type DialogProps = Omit<DialogFrameProps, "panelClassName">;

/** A centred modal. Focus is trapped inside while it is open. */
export function Dialog(props: DialogProps) {
  return <DialogFrame {...props} panelClassName={CENTER} />;
}
