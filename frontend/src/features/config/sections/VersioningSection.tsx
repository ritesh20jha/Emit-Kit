import { Panel } from "@/features/projects";
import { FormField, Select } from "@/ui";
import { choices, VERSION_LABELS, VERSION_STRATEGIES } from "../schema";

const VERSION_CHOICES = choices(VERSION_STRATEGIES, VERSION_LABELS);

export function VersioningSection() {
  return (
    <Panel title="Versioning">
      <FormField
        name="versionStrategy"
        label="Version source"
        description="EmitKit managed works out each next version from the changes. Spec version uses the spec file."
      >
        <Select options={VERSION_CHOICES} />
      </FormField>
    </Panel>
  );
}
