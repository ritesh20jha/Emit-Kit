import * as DialogPrimitive from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import type { ReactNode, RefObject } from "react";
import { cn } from "@/lib/utils/cn";
import { IconButton } from "./IconButton";

const PANEL = "fixed z-50 flex flex-col gap-4 border-border-control bg-bg p-5 text-fg shadow-overlay";

const CENTER =
  "left-1/2 top-1/2 w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 max-h-[calc(100dvh-2rem)] overflow-y-auto rounded-card border motion-safe:animate-dialog-in";

export interface DialogFrameProps {
  open?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
  /** The opener. Focus returns to it when the dialog closes. */
  trigger?: ReactNode;
  /**
   * Takes focus when the dialog closes, in place of the opener. Set it when the dialog was opened from something
   * that unmounts on select, such as a menu item: Radix would otherwise return focus to that removed node and
   * focus would fall to <body>.
   */
  returnFocusRef?: RefObject<HTMLElement | null>;
  /**
   * Runs when the dialog closes, before focus is returned. Calling preventDefault on the event stops both the
   * returnFocusRef focus and Radix's own restore, so the callback can move focus itself.
   */
  onCloseAutoFocus?: (event: Event) => void;
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
  returnFocusRef,
  onCloseAutoFocus,
  title,
  description,
  children,
  footer,
  dismissible = true,
  panelClassName,
  ...panelProps
}: DialogFrameProps) {
  // Preventing the dismissal events stops Radix from closing, controlled or not. Blocking inside onOpenChange
  // would be too late for an uncontrolled dialog, whose internal state has already changed.
  const block = (event: { preventDefault(): void }) => {
    if (!dismissible) event.preventDefault();
  };
  return (
    <DialogPrimitive.Root open={open} defaultOpen={defaultOpen} onOpenChange={onOpenChange}>
      {trigger && <DialogPrimitive.Trigger asChild>{trigger}</DialogPrimitive.Trigger>}
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-scrim motion-safe:animate-overlay-fade" />
        <DialogPrimitive.Content
          className={cn(PANEL, panelClassName)}
          onEscapeKeyDown={block}
          onPointerDownOutside={block}
          onInteractOutside={block}
          onCloseAutoFocus={(event) => {
            onCloseAutoFocus?.(event);
            if (event.defaultPrevented) return;
            if (returnFocusRef?.current) {
              event.preventDefault();
              returnFocusRef.current.focus();
            }
          }}
          {...panelProps}
        >
          <div className="flex flex-col gap-1 pr-8">
            <DialogPrimitive.Title className="text-base font-semibold text-fg">{title}</DialogPrimitive.Title>
            {description !== undefined && (
              <DialogPrimitive.Description className="text-sm text-fg-muted">{description}</DialogPrimitive.Description>
            )}
          </div>
          {children}
          {footer && <div className="flex justify-end gap-2">{footer}</div>}
          {/* Hidden rather than disabled: the close button is the one dismissal path the events above do not cover. */}
          {dismissible && (
            <DialogPrimitive.Close asChild>
              <IconButton label="Close" variant="ghost" size="sm" className="absolute top-3 right-3">
                <X aria-hidden="true" className="size-4" />
              </IconButton>
            </DialogPrimitive.Close>
          )}
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
