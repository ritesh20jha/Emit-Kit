import { Checkbox, FieldError } from "@/ui";

export interface CheckChoice<T extends string> {
  value: T;
  label: string;
}

export interface CheckGroupProps<T extends string> {
  /** Names the group to screen readers. The panel title is the visible name. */
  legend: string;
  choices: readonly CheckChoice<T>[];
  value: readonly T[];
  onChange: (next: T[]) => void;
  error?: string;
}

/** A set of checkboxes over a list of values. The list keeps the order of the choices, whatever the click order. */
export function CheckGroup<T extends string>({ legend, choices, value, onChange, error }: CheckGroupProps<T>) {
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="sr-only">{legend}</legend>
      <div className="flex flex-wrap gap-x-5 gap-y-2">
        {choices.map((choice) => (
          <label key={choice.value} className="flex items-center gap-2 text-sm text-fg">
            <Checkbox
              checked={value.includes(choice.value)}
              onCheckedChange={(checked) =>
                onChange(
                  choices
                    .map((candidate) => candidate.value)
                    .filter((candidate) => (candidate === choice.value ? checked === true : value.includes(candidate))),
                )
              }
            />
            {choice.label}
          </label>
        ))}
      </div>
      {error && <FieldError>{error}</FieldError>}
    </fieldset>
  );
}
