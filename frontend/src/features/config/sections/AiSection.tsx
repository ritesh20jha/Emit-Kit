import { useState } from "react";
import { Controller, useFormContext } from "react-hook-form";
import { Panel } from "@/features/projects";
import { Button, FormField, Input, SecretField, Select } from "@/ui";
import { AI_PROVIDERS, type ConfigValues, choices, PROVIDER_LABELS } from "../schema";

const PROVIDER_CHOICES = choices(AI_PROVIDERS, PROVIDER_LABELS);

/**
 * The AI provider, model and key. The key is write-only: the page never holds it, so the field shows "Key set"
 * until Replace. Remove key is undoable until the save.
 */
export function AiSection({ isSet }: { isSet: boolean }) {
  const { control } = useFormContext<ConfigValues>();
  return (
    <Panel title="AI">
      <FormField name="aiProvider" label="Provider">
        <Select options={PROVIDER_CHOICES} />
      </FormField>
      <FormField name="aiModel" label="Model" description="Leave blank for the provider's default model.">
        <Input className="font-mono" />
      </FormField>
      <Controller
        control={control}
        name="aiApiKey"
        render={({ field }) => <ApiKey isSet={isSet} value={field.value} onChange={field.onChange} />}
      />
    </Panel>
  );
}

/** null keeps the stored key, "" removes it, and a typed value replaces it. The field never reports "". */
function ApiKey({
  isSet,
  value,
  onChange,
}: {
  isSet: boolean;
  value: string | null;
  onChange: (value: string | null) => void;
}) {
  const [replacing, setReplacing] = useState(false);
  if (isSet && value === "") {
    return (
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm text-fg-muted">The key will be removed when you save.</p>
        <Button variant="ghost" size="sm" onClick={() => onChange(null)}>
          Undo
        </Button>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-2">
      <SecretField
        isSet={isSet}
        label="AI key"
        replacing={replacing}
        onReplacingChange={setReplacing}
        value={value}
        onChange={onChange}
      />
      {isSet && !replacing && (
        <div>
          <Button variant="ghost" size="sm" onClick={() => onChange("")}>
            Remove key
          </Button>
        </div>
      )}
    </div>
  );
}
