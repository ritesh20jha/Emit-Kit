import * as SwitchPrimitive from "@radix-ui/react-switch";
import type { ComponentPropsWithRef } from "react";
import { cn } from "@/lib/utils/cn";
import { chain, controlAttributes, useFormControl, useMergedRef } from "./formControl";

export type SwitchProps = ComponentPropsWithRef<typeof SwitchPrimitive.Root>;

/** On/off switch (Radix, role="switch"). Space toggles it when focused. Inside a FormField it binds to a boolean. */
export function Switch({ className, ref, checked, onCheckedChange, onBlur, name, ...props }: SwitchProps) {
  const control = useFormControl();
  const mergedRef = useMergedRef(ref, control?.fieldRef);
  return (
    <SwitchPrimitive.Root
      {...props}
      {...controlAttributes(control, props)}
      name={control ? control.name : name}
      ref={mergedRef}
      checked={control ? Boolean(control.value) : checked}
      onCheckedChange={chain(control ? (next: boolean) => control.onChange(next) : undefined, onCheckedChange)}
      onBlur={chain(control?.onBlur, onBlur)}
      // No outline-none here: the global :focus-visible ring (app.css) is the keyboard focus indicator.
      className={cn(
        "inline-flex h-5 w-9 shrink-0 cursor-pointer items-center rounded-full border border-border-strong bg-bg-muted transition-colors duration-(--duration-fast) disabled:cursor-not-allowed disabled:opacity-50 data-[state=checked]:border-accent data-[state=checked]:bg-accent aria-invalid:border-status-failed",
        className,
      )}
    >
      <SwitchPrimitive.Thumb className="pointer-events-none block size-4 translate-x-0.5 rounded-full bg-bg transition-transform duration-(--duration-fast) data-[state=checked]:translate-x-[18px] data-[state=checked]:bg-accent-fg" />
    </SwitchPrimitive.Root>
  );
}
