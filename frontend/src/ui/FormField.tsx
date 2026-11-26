import { type ReactNode, useId, useMemo } from "react";
import { type FieldPath, type FieldValues, useController, useFormContext } from "react-hook-form";
import { FieldError } from "./FieldError";
import { FormControlContext, type FormControlState } from "./formControl";
import { Label } from "./Label";

export interface FormFieldProps<T extends FieldValues> {
  name: FieldPath<T>;
  label: string;
  description?: ReactNode;
  /** A control from this library (Input, Select, ...), or any element that reads the form context. */
  children: ReactNode;
}

/**
 * Label, control, description and error for one react-hook-form field. Requires a FormProvider.
 * The control inside reads the wiring from context, so it needs no props beyond its own.
 */
export function FormField<T extends FieldValues>({ name, label, description, children }: FormFieldProps<T>) {
  const { control } = useFormContext<T>();
  const { field, fieldState } = useController({ control, name });
  const baseId = useId();
  const id = `${baseId}-control`;
  const descriptionId = description ? `${baseId}-description` : undefined;
  const errorMessage = fieldState.error?.message;
  const errorId = errorMessage ? `${baseId}-error` : undefined;
  const describedBy = [descriptionId, errorId].filter(Boolean).join(" ") || undefined;

  const state = useMemo<FormControlState>(
    () => ({
      id,
      name: field.name,
      invalid: Boolean(errorMessage),
      describedBy,
      value: field.value,
      onChange: field.onChange,
      onBlur: field.onBlur,
      fieldRef: field.ref,
    }),
    [id, field.name, field.value, field.onChange, field.onBlur, field.ref, errorMessage, describedBy],
  );

  return (
    <FormControlContext.Provider value={state}>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={id}>{label}</Label>
        {children}
        {description && (
          <p id={descriptionId} className="text-xs text-fg-subtle">
            {description}
          </p>
        )}
        {errorMessage && <FieldError id={errorId}>{errorMessage}</FieldError>}
      </div>
    </FormControlContext.Provider>
  );
}
