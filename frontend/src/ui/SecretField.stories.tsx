import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { Button } from "./Button";
import { SecretField, type SecretFieldProps } from "./SecretField";

const meta = {
  title: "UI/SecretField",
  component: SecretField,
  args: { label: "AI key", isSet: false, placeholder: "sk-..." },
} satisfies Meta<typeof SecretField>;

export default meta;
type Story = StoryObj<typeof meta>;

export const NotSet: Story = {
  render: (args) => (
    <div className="w-80">
      <SecretField {...args} />
    </div>
  ),
};

export const KeySet: Story = {
  args: { isSet: true },
  render: (args) => (
    <div className="w-80">
      <SecretField {...args} />
    </div>
  ),
};

/**
 * Replace opens an empty input; Cancel drops the typed value and returns to "Key set". Save stands in for a
 * successful request: the consumer clears its draft and sets replacing back to false.
 */
function ReplaceFlowDemo(args: SecretFieldProps) {
  const [replacing, setReplacing] = useState(false);
  const [value, setValue] = useState<string | null>(null);
  return (
    <div className="flex w-80 flex-col gap-2">
      <SecretField
        {...args}
        replacing={replacing}
        onReplacingChange={setReplacing}
        value={value}
        onChange={setValue}
      />
      {replacing && (
        <Button
          size="sm"
          onClick={() => {
            setValue(null);
            setReplacing(false);
          }}
        >
          Save
        </Button>
      )}
      <p className="text-xs text-fg-muted">Pending replacement: {value === null ? "none (keep)" : "typed"}</p>
    </div>
  );
}

export const ReplaceFlow: Story = {
  args: { isSet: true },
  render: (args) => <ReplaceFlowDemo {...args} />,
};
