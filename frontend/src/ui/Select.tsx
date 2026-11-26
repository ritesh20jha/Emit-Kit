import * as SelectPrimitive from "@radix-ui/react-select";
import { Check, ChevronDown } from "lucide-react";
import type { ComponentPropsWithRef } from "react";
import { cn } from "@/lib/utils/cn";
import { chain, controlAttributes, FIELD_SURFACE, useFormControl, useMergedRef } from "./formControl";

export interface SelectOption {
  /** Must not be empty: Radix reserves "" for "no selection". */
  value: string;
  label: string;
  disabled?: boolean;
}

export type SelectProps = Omit<ComponentPropsWithRef<typeof SelectPrimitive.Trigger>, "value" | "defaultValue"> & {
  options: readonly SelectOption[];
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  placeholder?: string;
  /** Submitted with the form. Ignored inside a FormField, which supplies its own name. */
  name?: string;
};

/** Single-choice select (Radix). Inside a FormField it binds to the form field. */
export function Select({
  options,
  value,
  defaultValue,
  onValueChange,
  placeholder,
  name,
  disabled,
  className,
  ref,
  onBlur,
  ...props
}: SelectProps) {
  const control = useFormControl();
  const mergedRef = useMergedRef(ref, control?.fieldRef);
  // The name goes to the Root (Radix renders the native field there), not the trigger button.
  const { name: fieldName, ...attributes } = controlAttributes(control, props);
  return (
    <SelectPrimitive.Root
      value={control ? ((control.value as string | undefined) ?? "") : value}
      defaultValue={control ? undefined : defaultValue}
      onValueChange={control ? chain(control.onChange, onValueChange) : onValueChange}
      name={control ? fieldName : name}
      disabled={disabled}
    >
      <SelectPrimitive.Trigger
        {...props}
        {...attributes}
        ref={mergedRef}
        disabled={disabled}
        onBlur={chain(control?.onBlur, onBlur)}
        className={cn(
          FIELD_SURFACE,
          "inline-flex h-8 cursor-pointer items-center justify-between gap-2 px-2.5 text-left text-sm data-[placeholder]:text-fg-subtle",
          className,
        )}
      >
        <SelectPrimitive.Value placeholder={placeholder} />
        <SelectPrimitive.Icon asChild>
          <ChevronDown aria-hidden="true" className="size-4 shrink-0 text-fg-subtle" />
        </SelectPrimitive.Icon>
      </SelectPrimitive.Trigger>
      <SelectPrimitive.Portal>
        <SelectPrimitive.Content
          position="popper"
          sideOffset={4}
          className="z-50 min-w-(--radix-select-trigger-width) overflow-hidden rounded-control border border-border-strong bg-bg"
        >
          <SelectPrimitive.Viewport className="p-1">
            {options.map((option) => (
              <SelectPrimitive.Item
                key={option.value}
                value={option.value}
                disabled={option.disabled}
                className="relative flex h-8 cursor-default items-center rounded-control py-1 pr-8 pl-2 text-sm text-fg outline-none select-none data-disabled:opacity-50 data-highlighted:bg-bg-muted"
              >
                <SelectPrimitive.ItemText>{option.label}</SelectPrimitive.ItemText>
                <SelectPrimitive.ItemIndicator className="absolute right-2 inline-flex">
                  <Check aria-hidden="true" className="size-4 text-accent" />
                </SelectPrimitive.ItemIndicator>
              </SelectPrimitive.Item>
            ))}
          </SelectPrimitive.Viewport>
        </SelectPrimitive.Content>
      </SelectPrimitive.Portal>
    </SelectPrimitive.Root>
  );
}
