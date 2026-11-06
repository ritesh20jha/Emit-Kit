import { type ComponentPropsWithRef, useId, useState } from "react";
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
   * The typed replacement, controlled. null or empty means keep the stored value. Omit it to leave the
   * input uncontrolled; then only onChange is informed of typing.
   */
  value?: string | null;
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
  const isReplacing = replacing ?? localReplacing;
  const setReplacing = (next: boolean) => {
    if (replacing === undefined) setLocalReplacing(next);
    onReplacingChange?.(next);
  };

  if (isSet && !isReplacing) {
    return (
      <div className="flex items-center justify-between gap-3">
        <span className="text-sm font-medium text-fg">{label}</span>
        <span className="flex items-center gap-3">
          <span className="text-sm text-fg-muted">Key set</span>
          <Button
            variant="secondary"
            size="sm"
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
        autoComplete="off"
        // Focus the new value when Replace opens it. A field that was never set does not steal focus.
        autoFocus={isSet}
        // Controlled only when the consumer passes value. Otherwise the DOM keeps what was typed.
        value={value === undefined ? undefined : (value ?? "")}
        onChange={(event) => onChange?.(event.target.value)}
        className={cn(FIELD_SURFACE, "h-8 px-2.5 text-sm", className)}
      />
    </div>
  );
}
