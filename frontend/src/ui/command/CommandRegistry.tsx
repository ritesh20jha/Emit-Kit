import { createContext, type ReactNode, useCallback, useContext, useEffect, useId, useMemo, useState } from "react";
import { Kbd } from "../Kbd";

export interface Command {
  id: string;
  /** Section heading in the palette and in the shortcut help. */
  group: string;
  label: string;
  /** Extra words the palette query matches, such as "team" for "Invite people". */
  keywords?: string[];
  /** Display only: shown as keys in the palette and the shortcut help. It binds nothing; bind the key with useShortcut. */
  shortcut?: string;
  run: () => void;
}

type Register = (owner: string, commands: readonly Command[]) => () => void;

const RegisterContext = createContext<Register | null>(null);
const CommandListContext = createContext<readonly Command[] | null>(null);

/** Holds the commands that components register with useCommands. Mount it once, above the palette. */
export function CommandRegistryProvider({ children }: { children: ReactNode }) {
  const [sources, setSources] = useState<ReadonlyMap<string, readonly Command[]>>(() => new Map());

  // Stable, so registering never re-renders the components that register.
  const register = useCallback<Register>((owner, commands) => {
    setSources((previous) => new Map(previous).set(owner, commands));
    return () =>
      setSources((previous) => {
        const next = new Map(previous);
        next.delete(owner);
        return next;
      });
  }, []);

  const commands = useMemo(() => [...sources.values()].flat(), [sources]);

  return (
    <RegisterContext.Provider value={register}>
      <CommandListContext.Provider value={commands}>{children}</CommandListContext.Provider>
    </RegisterContext.Provider>
  );
}

/**
 * Registers commands for the palette while the calling component is mounted. The commands re-register when a
 * value in deps changes, so list here what the run handlers close over, as with useEffect.
 */
export function useCommands(commands: readonly Command[], deps: readonly unknown[]): void {
  const register = useContext(RegisterContext);
  if (!register) throw new Error("useCommands must be used inside <CommandRegistryProvider>.");
  const owner = useId();
  // The commands list itself is not a dependency: a new array each render must not re-register.
  // eslint-disable-next-line react-hooks/exhaustive-deps -- the caller chooses the deps.
  useEffect(() => register(owner, commands), [register, owner, ...deps]);
}

/** Every registered command, in registration order. Used by the palette and the shortcut help. */
export function useRegisteredCommands(): readonly Command[] {
  const commands = useContext(CommandListContext);
  if (!commands) throw new Error("The command palette must be used inside <CommandRegistryProvider>.");
  return commands;
}

/** Commands grouped by their group name, in the order each group first appears. */
export function groupCommands(commands: readonly Command[]): Map<string, Command[]> {
  const groups = new Map<string, Command[]>();
  for (const command of commands) {
    groups.set(command.group, [...(groups.get(command.group) ?? []), command]);
  }
  return groups;
}

/** Renders a shortcut string such as "g p" or "⌘K" as keys. Space separates the keys of a sequence. */
export function ShortcutKeys({ shortcut }: { shortcut: string }) {
  return (
    <span className="flex shrink-0 items-center gap-1">
      {shortcut.split(" ").map((key, index) => (
        <Kbd key={`${index}-${key}`}>{key}</Kbd>
      ))}
    </span>
  );
}
