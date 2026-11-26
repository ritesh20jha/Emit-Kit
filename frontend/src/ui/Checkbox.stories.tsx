import type { Meta, StoryObj } from "@storybook/react-vite";
import { Checkbox } from "./Checkbox";

const meta = {
  title: "UI/Checkbox",
  component: Checkbox,
  args: { "aria-label": "Accept the terms" },
} satisfies Meta<typeof Checkbox>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const States: Story = {
  render: (args) => (
    <div className="flex flex-col gap-3">
      <Checkbox {...args} />
      <Checkbox {...args} defaultChecked />
      <Checkbox {...args} checked="indeterminate" />
      <Checkbox {...args} aria-invalid="true" />
      <Checkbox {...args} disabled />
      <Checkbox {...args} disabled defaultChecked />
    </div>
  ),
};
