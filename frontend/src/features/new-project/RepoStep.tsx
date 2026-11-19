import { useEffect } from "react";
import { useFormContext, useWatch } from "react-hook-form";
import { FormField, Input, Select } from "@/ui";
import { RepoPickerField } from "./RepoPicker";
import type { WizardValues } from "./schemas";

const MODE_OPTIONS = [
  { value: "existing", label: "Existing repository" },
  { value: "new", label: "New repository" },
];

const VISIBILITY_OPTIONS = [
  { value: "private", label: "Private" },
  { value: "public", label: "Public" },
];

/** Step 1: an existing repository from the picker, or a new one by name. */
export function RepoStep({ orgId, takenMessage }: { orgId: string; takenMessage?: string }) {
  const { control, setValue, setError, clearErrors, getFieldState } = useFormContext<WizardValues>();
  const mode = useWatch({ control, name: "mode" });

  // The live name check reports through the field, the same way a failed validation does.
  useEffect(() => {
    if (takenMessage) setError("name", { type: "taken", message: takenMessage });
    else if (getFieldState("name").error?.type === "taken") clearErrors("name");
  }, [takenMessage, setError, clearErrors, getFieldState]);

  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-lg font-semibold text-fg">Repository</h2>
      <FormField name="mode" label="Repository">
        <Select options={MODE_OPTIONS} />
      </FormField>
      {mode === "existing" ? (
        <RepoPickerField
          name="repo"
          label="Existing repositories"
          orgId={orgId}
          // The branch starts as the repository's own default; the spec step lets it change.
          onChoose={(repo) => setValue("branch", repo.defaultBranch)}
        />
      ) : (
        <>
          <FormField name="name" label="Repository name" description="Created under the organization on GitHub.">
            <Input autoComplete="off" spellCheck={false} />
          </FormField>
          <FormField name="visibility" label="Visibility">
            <Select options={VISIBILITY_OPTIONS} />
          </FormField>
        </>
      )}
    </section>
  );
}
