import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef } from "react";

/** A multi-key sequence such as "g p" gives up after this long between keys. */
const SEQUENCE_TIMEOUT_MS = 1000;

/** Shortcuts that still fire while focus is in a text field. Every other shortcut waits until the user leaves it. */
const EDITABLE_BINDINGS = new Set(["mod+s", "mod+k"]);

/** Input, textarea, select, contenteditable, and the CodeMirror editor that the spec editor uses. */
const EDITABLE_TARGET = 'input, textarea, select, [contenteditable]:not([contenteditable="false"]), .cm-editor';

const MODIFIER_KEYS = new Set(["Shift", "Control", "Alt", "Meta", "AltGraph", "CapsLock"]);

interface Step {
  /** True for a "mod+" step: Cmd on macOS, Ctrl elsewhere. */
  mod: boolean;
  /** Lower case, as KeyboardEvent.key is compared lower cased. */
  key: string;
}

interface Binding {
  /** Normalised keys, e.g. "mod+k" or "g p". */
  keys: string;
  steps: readonly Step[];
  run: () => void;
}

interface ShortcutsValue {
  add(keys: string, run: () => void): () => void;
}

const ShortcutsContext = createContext<ShortcutsValue | null>(null);

function isMacPlatform(): boolean {
  if (typeof navigator === "undefined") return false;
  return /Mac|iPhone|iPad|iPod/.test(navigator.platform || navigator.userAgent);
}

/** Parses "g p", "/", "?", "mod+k". A "mod" step may only be a single key, never part of a sequence. */
function parseKeys(keys: string): Step[] {
  const steps = keys
    .trim()
    .split(/\s+/)
    .map((token): Step => {
      const parts = token.toLowerCase().split("+");
      const key = parts.pop() ?? "";
      if (!key || parts.some((modifier) => modifier !== "mod")) {
        throw new Error(`Unsupported shortcut "${keys}". Use "mod+<key>" or a key sequence such as "g p".`);
      }
      return { mod: parts.includes("mod"), key };
    });
  if (steps.length > 1 && steps.some((step) => step.mod)) {
    throw new Error(`Shortcut "${keys}" cannot combine "mod" with a sequence.`);
  }
  return steps;
}

function stepMatches(step: Step, event: KeyboardEvent): boolean {
  if (event.key.toLowerCase() !== step.key) return false;
  if (step.mod) {
    const modDown = isMacPlatform() ? event.metaKey : event.ctrlKey;
    return modDown && !event.altKey && !event.shiftKey;
  }
  // Plain keys never fire with a modifier held. Shift stays allowed, because "?" needs it.
  return !event.ctrlKey && !event.metaKey && !event.altKey;
}

/** True when the first steps of a sequence are the keys already typed. */
function startsWith(steps: readonly Step[], typed: readonly string[]): boolean {
  return typed.every((key, index) => steps[index]?.key === key);
}

function matchingBindings(bindings: readonly Binding[], typed: readonly string[], event: KeyboardEvent): Binding[] {
  return bindings.filter((binding) => {
    const step = binding.steps[typed.length];
    return (
      binding.steps.length === typed.length + 1 && step !== undefined && startsWith(binding.steps, typed) && stepMatches(step, event)
    );
  });
}

function isEditableTarget(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest(EDITABLE_TARGET) !== null;
}

/**
 * Owns the one window keydown listener and the sequence buffer. Mount it once, above every component that calls
 * useShortcut.
 */
export function ShortcutsProvider({ children }: { children: ReactNode }) {
  const bindings = useRef(new Set<Binding>());
  // Plain keys typed so far towards a multi-key sequence, and the timer that drops them.
  const typed = useRef<string[]>([]);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const resetSequence = useCallback(() => {
    typed.current = [];
    clearTimeout(timer.current);
    timer.current = undefined;
  }, []);

  const add = useCallback((keys: string, run: () => void) => {
    const steps = parseKeys(keys);
    const binding: Binding = { keys: steps.map((step) => (step.mod ? `mod+${step.key}` : step.key)).join(" "), steps, run };
    bindings.current.add(binding);
    return () => {
      bindings.current.delete(binding);
    };
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      // A held key auto-repeats, and a repeat must not fire again: held mod+k would otherwise toggle the palette.
      if (event.repeat || event.isComposing || MODIFIER_KEYS.has(event.key)) return;

      // Typing in a field never continues a sequence started elsewhere.
      const editable = isEditableTarget(event.target);
      const candidates = [...bindings.current].filter((binding) => !editable || EDITABLE_BINDINGS.has(binding.keys));
      if (editable) resetSequence();

      const key = event.key.toLowerCase();
      const plain = !event.ctrlKey && !event.metaKey && !event.altKey;

      let prefix: string[] = editable ? [] : typed.current;
      let fired = matchingBindings(candidates, prefix, event);
      // A key that does not continue the pending sequence starts over, so it may begin a new one.
      if (fired.length === 0 && prefix.length > 0) {
        prefix = [];
        fired = matchingBindings(candidates, prefix, event);
      }

      if (fired.length > 0) {
        resetSequence();
        // Stops the key's default action, such as typing "/" into a field that the handler has just focused.
        // The browser's own mod shortcuts, such as Ctrl+S saving the page, are stopped the same way.
        event.preventDefault();
        for (const binding of fired) binding.run();
        return;
      }

      const continuesSequence =
        plain &&
        candidates.some(
          (binding) =>
            binding.steps.length > prefix.length + 1 &&
            startsWith(binding.steps, prefix) &&
            binding.steps[prefix.length]?.key === key,
        );
      if (continuesSequence) {
        typed.current = [...prefix, key];
        clearTimeout(timer.current);
        timer.current = setTimeout(() => {
          typed.current = [];
          timer.current = undefined;
        }, SEQUENCE_TIMEOUT_MS);
      } else {
        resetSequence();
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      resetSequence();
    };
  }, [resetSequence]);

  const value = useMemo<ShortcutsValue>(() => ({ add }), [add]);
  return <ShortcutsContext.Provider value={value}>{children}</ShortcutsContext.Provider>;
}

/**
 * Runs handler when the keys are pressed. Keys are "g p" for a sequence (1 s between keys), "/" or "?" for a
 * single key, or "mod+k" for Cmd+K on macOS and Ctrl+K elsewhere. Single keys do not fire while focus is in a text
 * field, a select or the code editor; "mod+s" and "mod+k" do.
 */
export function useShortcut(keys: string, handler: () => void): void {
  const shortcuts = useContext(ShortcutsContext);
  if (!shortcuts) throw new Error("useShortcut must be used inside <ShortcutsProvider>.");
  // The latest handler runs without re-registering the key on every render.
  const handlerRef = useRef(handler);
  useEffect(() => {
    handlerRef.current = handler;
  });
  useEffect(() => shortcuts.add(keys, () => handlerRef.current()), [shortcuts, keys]);
}
