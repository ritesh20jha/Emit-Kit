import { Controller, useFormContext } from "react-hook-form";
import { Panel } from "@/features/projects";
import { FormField, Input } from "@/ui";
import { CheckGroup } from "../CheckGroup";
import { type ConfigValues, choices, OUTPUT_KINDS, OUTPUT_LABELS } from "../schema";

const OUTPUT_CHOICES = choices(OUTPUT_KINDS, OUTPUT_LABELS);

/** What to generate, and the directory in the repository the files go to. */
export function OutputsSection() {
  const { control } = useFormContext<ConfigValues>();
  return (
    <Panel title="Outputs">
      <Controller
        control={control}
        name="outputs"
        render={({ field, fieldState }) => (
          <CheckGroup
            legend="Outputs"
            choices={OUTPUT_CHOICES}
            value={field.value}
            onChange={field.onChange}
            error={fieldState.error?.message}
          />
        )}
      />
      <FormField
        name="outputDir"
        label="Output directory"
        description="Relative to the repository root. Ends with a slash, such as generated/."
      >
        <Input className="font-mono" />
      </FormField>
    </Panel>
  );
}
