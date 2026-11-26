import { Panel } from "@/features/projects";
import { FormField, Select } from "@/ui";
import { choices, LINT_FAIL_ONS, LINT_LABELS } from "../schema";

const LINT_CHOICES = choices(LINT_FAIL_ONS, LINT_LABELS);

export function LintPolicySection() {
  return (
    <Panel title="Lint policy">
      <FormField
        name="lintFailOn"
        label="Fail the run on lint findings"
        description="Findings at this level or above stop the run."
      >
        <Select options={LINT_CHOICES} />
      </FormField>
    </Panel>
  );
}
