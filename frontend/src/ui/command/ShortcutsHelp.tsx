import { useState } from "react";
import { Dialog } from "../Dialog";
import { groupCommands, ShortcutKeys, useRegisteredCommands } from "./CommandRegistry";
import { useShortcut } from "./ShortcutsProvider";

export interface ShortcutsHelpProps {
  /** Controlled open state. Leave it out to open with ? and close from the dialog. */
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
}

/**
 * The "?" dialog. It lists the registered commands that carry a shortcut, grouped the same way as the palette.
 */
export function ShortcutsHelp({ open: controlledOpen, onOpenChange }: ShortcutsHelpProps) {
  const commands = useRegisteredCommands();
  const [uncontrolledOpen, setUncontrolledOpen] = useState(false);
  const open = controlledOpen ?? uncontrolledOpen;

  const setOpen = (next: boolean) => {
    if (controlledOpen === undefined) setUncontrolledOpen(next);
    onOpenChange?.(next);
  };

  useShortcut("?", () => setOpen(true));

  const withShortcut = commands.filter((command) => command.shortcut !== undefined);
  const groups = groupCommands(withShortcut);

  return (
    <Dialog
      open={open}
      onOpenChange={setOpen}
      title="Keyboard shortcuts"
      description="Single-key shortcuts pause while you type in a field."
    >
      {groups.size === 0 ? (
        <p className="text-sm text-fg-muted">No shortcuts are available on this page.</p>
      ) : (
        [...groups].map(([group, items]) => (
          <section key={group} className="flex flex-col gap-1">
            <h3 className="text-xs font-medium text-fg-muted">{group}</h3>
            <ul className="flex flex-col">
              {items.map((command) => (
                <li key={command.id} className="flex items-center justify-between gap-3 py-1.5 text-sm text-fg">
                  <span>{command.label}</span>
                  {command.shortcut && <ShortcutKeys shortcut={command.shortcut} />}
                </li>
              ))}
            </ul>
          </section>
        ))
      )}
    </Dialog>
  );
}
