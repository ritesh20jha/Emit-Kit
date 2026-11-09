import { Command as Cmdk, defaultFilter } from "cmdk";
import { type ComponentProps, useLayoutEffect, useRef, useState } from "react";
import { cn } from "@/lib/utils/cn";
import { DialogFrame } from "../Dialog";
import { FIELD_SURFACE } from "../formControl";
import { type Command, groupCommands, ShortcutKeys, useRegisteredCommands } from "./CommandRegistry";
import { useShortcut } from "./ShortcutsProvider";

// The DialogFrame panel, top-aligned rather than centred, with no padding so the search row sits on the edge.
// Width is 100% minus 2rem, so the palette fits a 360px phone with a 16px gutter on each side.
const PANEL =
  "left-1/2 top-[max(1rem,12dvh)] w-[calc(100%-2rem)] max-w-xl -translate-x-1/2 max-h-[calc(100dvh-2rem)] gap-0 overflow-hidden rounded-card border p-0 motion-safe:animate-dialog-in";

// cmdk also scores the item's value, which is the command id. Ids are keys, not text the user can see, so the query
// is scored against the label and keywords only. Each entry in keywords is a whole label or keyword phrase, not a
// single word, so the query is scored against each phrase on its own and the best score wins. That keeps a query
// from matching letters spread across two different phrases.
const filterByLabel: NonNullable<ComponentProps<typeof Cmdk>["filter"]> = (_value, search, keywords = []) =>
  Math.max(0, ...keywords.map((word) => defaultFilter(word, search)));

const GROUP_HEADING =
  "[&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:font-medium [&_[cmdk-group-heading]]:text-fg-muted";

export interface CommandPaletteProps {
  /** Controlled open state, e.g. so a button in the shell can open it. Leave it out to toggle with mod+k. */
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
}

/**
 * The ⌘K / Ctrl+K palette. It lists the commands registered with useCommands, filters them by the typed query, and
 * runs the one the user picks. The caller registers only the commands the user may run.
 */
export function CommandPalette({ open: controlledOpen, onOpenChange }: CommandPaletteProps) {
  const commands = useRegisteredCommands();
  const [uncontrolledOpen, setUncontrolledOpen] = useState(false);
  const open = controlledOpen ?? uncontrolledOpen;

  const setOpen = (next: boolean) => {
    if (controlledOpen === undefined) setUncontrolledOpen(next);
    onOpenChange?.(next);
  };

  // Radix returns focus to a trigger on close, and the palette has none. Remember what had focus before it
  // opened, so Escape puts focus back where it was. The layout effect runs before Radix moves focus into the panel.
  const returnFocusRef = useRef<HTMLElement | null>(null);
  // The command the user chose. It runs once the palette has closed, not while it is still open: the open focus
  // trap would otherwise pull focus back from whatever the command focuses.
  const pendingCommand = useRef<Command | null>(null);
  useLayoutEffect(() => {
    if (!open) return;
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    pendingCommand.current = null;
  }, [open]);

  useShortcut("mod+k", () => setOpen(!open));

  const choose = (command: Command) => {
    pendingCommand.current = command;
    setOpen(false);
  };

  const onCloseAutoFocus = (event: Event) => {
    const command = pendingCommand.current;
    // Escape and outside clicks have no command: the DialogFrame returns focus as before.
    if (!command) return;
    pendingCommand.current = null;
    // Stops Radix restoring focus to the element that had it before the palette opened. That restore would
    // otherwise run after the command and undo its focus move.
    event.preventDefault();
    command.run();
  };

  return (
    <DialogFrame
      open={open}
      onOpenChange={setOpen}
      returnFocusRef={returnFocusRef}
      onCloseAutoFocus={onCloseAutoFocus}
      title={<span className="sr-only">Command palette</span>}
      panelClassName={PANEL}
    >
      <Cmdk label="Search commands" loop vimBindings={false} filter={filterByLabel}>
        <div className="border-b border-border p-2">
          <Cmdk.Input
            placeholder="Search projects, runs and actions"
            // pr-10 keeps the text clear of the close button, which sits in the top-right corner.
            className={cn(FIELD_SURFACE, "h-9 px-3 pr-10 text-sm")}
          />
        </div>
        <Cmdk.List aria-label="Commands" className="max-h-[min(60dvh,24rem)] overflow-y-auto overscroll-contain p-1">
          <Cmdk.Empty className="px-2 py-6 text-center text-sm text-fg-muted">No results</Cmdk.Empty>
          {[...groupCommands(commands)].map(([group, items]) => (
            <Cmdk.Group key={group} heading={group} className={GROUP_HEADING}>
              {items.map((command) => (
                <Cmdk.Item
                  key={command.id}
                  value={command.id}
                  keywords={[command.label, ...(command.keywords ?? [])]}
                  onSelect={() => choose(command)}
                  className="flex h-9 cursor-default items-center justify-between gap-3 rounded-control px-2 text-sm text-fg select-none data-[selected=true]:bg-bg-muted"
                >
                  <span className="truncate">{command.label}</span>
                  {command.shortcut && <ShortcutKeys shortcut={command.shortcut} />}
                </Cmdk.Item>
              ))}
            </Cmdk.Group>
          ))}
        </Cmdk.List>
      </Cmdk>
    </DialogFrame>
  );
}
