import { createContext, type Ref, useCallback, useContext } from "react";

/** What a FormField hands to the control it wraps. Controls read it with useFormControl. */
export interface FormControlState {
  id: string;
  name: string;
  invalid: boolean;
  /** Ids of the rendered description and error, if any. */
  describedBy?: string;
  value: unknown;
  onChange: (...args: unknown[]) => void;
  onBlur: (...args: unknown[]) => void;
  fieldRef: (instance: unknown) => void;
}

/** Surface shared by text-like controls (Input, Textarea, Select trigger). */
export const FIELD_SURFACE =
  "w-full min-w-0 rounded-control border border-border-control bg-bg text-fg placeholder:text-fg-subtle transition-colors duration-(--duration-fast) disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-status-failed";

export const FormControlContext = createContext<FormControlState | null>(null);

/** The FormField wiring for the nearest control, or null when the control is used outside a FormField. */
export function useFormControl(): FormControlState | null {
  return useContext(FormControlContext);
}

/** Writes a node into a callback or object ref. */
function assignRef<T>(ref: Ref<T> | undefined, node: T | null) {
  if (typeof ref === "function") ref(node);
  else if (ref) ref.current = node;
}

/** Merges a consumer ref with the one react-hook-form needs (used to focus the field on error). */
export function useMergedRef<T>(own: Ref<T> | undefined, fieldRef: ((instance: unknown) => void) | undefined) {
  return useCallback(
    (node: T | null) => {
      fieldRef?.(node);
      assignRef(own, node);
    },
    [own, fieldRef],
  );
}

/** Calls the FormField's handler and then the consumer's own. Returns undefined when neither is set. */
export function chain<A extends unknown[]>(
  first: ((...args: A) => void) | undefined,
  second: ((...args: A) => void) | undefined,
): ((...args: A) => void) | undefined {
  if (!first && !second) return undefined;
  return (...args: A) => {
    first?.(...args);
    second?.(...args);
  };
}

interface AriaOwn {
  id?: string;
  "aria-invalid"?: boolean | "true" | "false" | "grammar" | "spelling";
  "aria-describedby"?: string;
}

/**
 * The attributes a control takes from its FormField. Empty outside a FormField, so the consumer's
 * own attributes (spread before this) stand. Inside, the FormField owns id and name, and the
 * description and error ids are appended to any the consumer passed.
 */
export function controlAttributes(control: FormControlState | null, own: AriaOwn): AriaOwn & { name?: string } {
  if (!control) return {};
  return {
    id: control.id,
    name: control.name,
    "aria-invalid": control.invalid || own["aria-invalid"],
    "aria-describedby": [own["aria-describedby"], control.describedBy].filter(Boolean).join(" ") || undefined,
  };
}
