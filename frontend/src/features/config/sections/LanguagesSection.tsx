import { Controller, useFormContext } from "react-hook-form";
import { Panel } from "@/features/projects";
import { CheckGroup } from "../CheckGroup";
import { type ConfigValues, choices, LANGUAGE_LABELS, SDK_LANGUAGES } from "../schema";

const LANGUAGE_CHOICES = choices(SDK_LANGUAGES, LANGUAGE_LABELS);

/** The languages the SDK is generated for. Shown only while the SDK output is on. */
export function LanguagesSection() {
  const { control } = useFormContext<ConfigValues>();
  return (
    <Panel title="SDK languages">
      <Controller
        control={control}
        name="sdkLanguages"
        render={({ field, fieldState }) => (
          <CheckGroup
            legend="SDK languages"
            choices={LANGUAGE_CHOICES}
            value={field.value}
            onChange={field.onChange}
            error={fieldState.error?.message}
          />
        )}
      />
    </Panel>
  );
}
