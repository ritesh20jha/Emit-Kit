import { Panel } from "@/features/projects";
import { FormField, Select } from "@/ui";
import { BREAKING_GATES, BREAKING_LABELS, choices } from "../schema";

const BREAKING_CHOICES = choices(BREAKING_GATES, BREAKING_LABELS);

export function BreakingPolicySection() {
  return (
    <Panel title="Breaking-change policy">
      <FormField
        name="breakingGate"
        label="When a run finds a breaking change"
        description="Require approval waits for an admin before the run goes on."
      >
        <Select options={BREAKING_CHOICES} />
      </FormField>
    </Panel>
  );
}
