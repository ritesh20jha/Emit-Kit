import * as CheckboxPrimitive from "@radix-ui/react-checkbox";
import { Check } from "lucide-react";
import type { ComponentPropsWithRef } from "react";
import { cn } from "@/lib/utils/cn";
import { chain, controlAttributes, useFormControl, useMergedRef } from "./formControl";

export type CheckboxProps = ComponentPropsWithRef<typeof CheckboxPrimitive.Root>;

/** Checkbox (Radix). Inside a FormField it binds to a boolean form value. */
export function Checkbox({ className, ref, checked, onCheckedChange, onBlur, name, ...props }: CheckboxProps) {
  const control = useFormControl();
  const mergedRef = useMergedRef(ref, control?.fieldRef);
  return (
    <CheckboxPrimitive.Root
      {...props}
      {...controlAttributes(control, props)}
      name={control ? control.name : name}
      ref={mergedRef}
      checked={control ? Boolean(control.value) : checked}
      onCheckedChange={chain(
        control ? (next: CheckboxPrimitive.CheckedState) => control.onChange(next === true) : undefined,
        onCheckedChange,
      )}
      onBlur={chain(control?.onBlur, onBlur)}
      // No outline-none here: the global :focus-visible ring (app.css) is the keyboard focus indicator.
      className={cn(
        "inline-flex size-4 shrink-0 items-center justify-center rounded-[4px] border border-border-strong bg-bg transition-colors duration-(--duration-fast) disabled:cursor-not-allowed disabled:opacity-50 data-[state=checked]:border-accent data-[state=checked]:bg-accent data-[state=indeterminate]:border-accent data-[state=indeterminate]:bg-accent aria-invalid:border-status-failed",
        className,
      )}
    >
      <CheckboxPrimitive.Indicator className="inline-flex text-accent-fg">
        <Check aria-hidden="true" className="size-3.5" />
      </CheckboxPrimitive.Indicator>
    </CheckboxPrimitive.Root>
  );
}
