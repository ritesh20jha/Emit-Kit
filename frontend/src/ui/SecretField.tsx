import { type ComponentPropsWithRef, useEffect, useId, useRef, useState } from "react";
import { cn } from "@/lib/utils/cn";
import { Button } from "./Button";
import { FIELD_SURFACE } from "./formControl";
import { Label } from "./Label";

export type SecretFieldProps = Omit<ComponentPropsWithRef<"input">, "type" | "value" | "onChange"> & {
  /** A value is already stored server-side. It is never sent back to the page. */
  isSet: boolean;
  label: string;
  onReplace?: () => void;
  /** Called after the replacement is dropped. onChange has already been called with null. */
  onCancel?: () => void;
  /**
   * Whether the empty replacement input is open. Omit it and the field tracks this itself. Set it to
   * control the field: set false after a successful save to return to "Key set".
   */
  replacing?: boolean;
  /** Called with the new state when Replace or Cancel is clicked. Controlled mode must update `replacing`. */
  onReplacingChange?: (replacing: boolean) => void;
  /**
   * The typed replacement, controlled. Omit it to leave the input uncontrolled; then only onChange is
   * informed of typing. The value is never "": clearing the input reports null.
   */
  value?: string | null;
  /**
   * Reports the replacement. null means keep the stored value (never sent as ""); a non-empty string
   * replaces it. This field never reports "", so an explicit remove has to come from a separate control.
   */
  onChange?: (value: string | null) => void;
};

/**
 * Write-only secret input. When a value is stored, the field shows "Key set" and a Replace button,
 * and the input is not in the DOM until Replace. It needs no FormProvider: wire it with a Controller,
 * where null means "keep the stored value". It renders a native input, so it does not bind to an
 * enclosing FormField.
 */
export function SecretField({
  isSet,
  label,
  onReplace,
  onCancel,
  replacing,
  onReplacingChange,
  value,
  onChange,
  id,
  className,
  ...props
}: SecretFieldProps) {
  const [localReplacing, setLocalReplacing] = useState(false);
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const nameId = `${generatedId}-name`;
  const statusId = `${generatedId}-status`;
  const isReplacing = replacing ?? localReplacing;
  const keyView = isSet && !isReplacing;
  const replaceRef = useRef<HTMLButtonElement>(null);
  const returnFocusRef = useRef(false);
  const setReplacing = (next: boolean) => {
    if (replacing === undefined) setLocalReplacing(next);
    onReplacingChange?.(next);
  };

  // Cancel unmounts the focused button. Put focus back on Replace once the "Key set" view is mounted.
  useEffect(() => {
    if (keyView && returnFocusRef.current) {
      returnFocusRef.current = false;
      replaceRef.current?.focus();
    }
  }, [keyView]);

  if (keyView) {
    return (
      <div className="flex items-center justify-between gap-3">
        <span id={nameId} className="text-sm font-medium text-fg">
          {label}
        </span>
        <span className="flex items-center gap-3">
          <span id={statusId} className="text-sm text-fg-muted">
            Key set
          </span>
          <Button
            ref={replaceRef}
            variant="secondary"
            size="sm"
            aria-describedby={`${nameId} ${statusId}`}
            onClick={() => {
              setReplacing(true);
              onReplace?.();
            }}
          >
            Replace
          </Button>
        </span>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between gap-3">
        <Label htmlFor={inputId}>{label}</Label>
        {isSet && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              returnFocusRef.current = true;
              setReplacing(false);
              onChange?.(null);
              onCancel?.();
            }}
          >
            Cancel
          </Button>
        )}
      </div>
      <input
        {...props}
        id={inputId}
        type="password"
        // Browsers ignore autocomplete="off" on password fields, so a saved login password could be
        // filled in as the key. The attributes below come after the spread so consumers cannot undo them.
        autoComplete="new-password"
        data-1p-ignore
        data-lpignore="true"
        data-bwignore="true"
        // Focus the new value when Replace opens it. A field that was never set does not steal focus.
        autoFocus={isSet}
        // Controlled only when the consumer passes value. Otherwise the DOM keeps what was typed.
        value={value === undefined ? undefined : (value ?? "")}
        onChange={(event) => {
          const next = event.target.value;
          onChange?.(next === "" ? null : next);
        }}
        className={cn(FIELD_SURFACE, "h-8 px-2.5 text-sm", className)}
      />
    </div>
  );
}
