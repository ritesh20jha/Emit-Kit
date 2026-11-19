import { useFormContext, useWatch } from "react-hook-form";
import { FormField, Select } from "@/ui";
import { RepoPickerField } from "./RepoPicker";
import { targetRepo, type WizardValues } from "./schemas";

const OUTPUT_OPTIONS = [
  { value: "same", label: "Same repository" },
  { value: "separate", label: "Separate repository" },
];

/** Step 3: where generated code goes, and a summary of what EmitKit will do when the project is created. */
export function OutputStep({ orgId, owner }: { orgId: string; owner: string }) {
  const { control } = useFormContext<WizardValues>();
  // Every field has a default, so the watched object is complete from the first render.
  const values = useWatch({ control }) as WizardValues;
  const target = targetRepo(values, owner);
  const separate = values.outputMode === "separate";

  const steps = [
    `Register a webhook on ${target}.`,
    ...(values.mode === "new"
      ? [`Create ${target} as a ${values.visibility} repository and commit a starter spec at ${values.specPath}.`]
      : []),
    separate
      ? `Write generated code to ${values.outputRepo || "the output repository, once chosen"}.`
      : `Write generated code into ${target}.`,
  ];

  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-lg font-semibold text-fg">Output</h2>
      <FormField name="outputMode" label="Output mode">
        <Select options={OUTPUT_OPTIONS} />
      </FormField>
      {separate && <RepoPickerField name="outputRepo" label="Output repositories" orgId={orgId} />}
      <section aria-labelledby="summary-title" className="flex flex-col gap-2 rounded-card border border-border p-4">
        <h3 id="summary-title" className="text-sm font-medium text-fg">
          What EmitKit will do
        </h3>
        <ul className="flex list-disc flex-col gap-1 pl-5 text-sm text-fg-muted">
          {steps.map((step) => (
            <li key={step}>{step}</li>
          ))}
        </ul>
      </section>
    </section>
  );
}
