import type { ComponentPropsWithRef } from "react";
import { cn } from "@/lib/utils/cn";
import { chain, controlAttributes, FIELD_SURFACE, useFormControl, useMergedRef } from "./formControl";

export type TextareaProps = ComponentPropsWithRef<"textarea">;

/** Multi-line text. Inside a FormField it binds to the form field; outside, it is a plain textarea. */
export function Textarea({ className, ref, value, onChange, onBlur, ...props }: TextareaProps) {
  const control = useFormControl();
  const mergedRef = useMergedRef(ref, control?.fieldRef);
  return (
    <textarea
      {...props}
      {...controlAttributes(control, props)}
      ref={mergedRef}
      value={control ? ((control.value as string | undefined) ?? "") : value}
      onChange={control ? chain(control.onChange, onChange) : onChange}
      onBlur={chain(control?.onBlur, onBlur)}
      className={cn(FIELD_SURFACE, "min-h-20 px-2.5 py-2 text-sm", className)}
    />
  );
}
