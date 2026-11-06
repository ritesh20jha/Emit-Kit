import type { ComponentPropsWithRef } from "react";
import { cn } from "@/lib/utils/cn";
import { chain, controlAttributes, FIELD_SURFACE, useFormControl, useMergedRef } from "./formControl";

export type InputProps = ComponentPropsWithRef<"input">;

/** Text input. Inside a FormField it binds to the form field; outside, it is a plain input. */
export function Input({ className, ref, value, onChange, onBlur, ...props }: InputProps) {
  const control = useFormControl();
  const mergedRef = useMergedRef(ref, control?.fieldRef);
  return (
    <input
      {...props}
      {...controlAttributes(control, props)}
      ref={mergedRef}
      value={control ? ((control.value as string | undefined) ?? "") : value}
      onChange={control ? chain(control.onChange, onChange) : onChange}
      onBlur={chain(control?.onBlur, onBlur)}
      className={cn(FIELD_SURFACE, "h-8 px-2.5 text-sm", className)}
    />
  );
}
