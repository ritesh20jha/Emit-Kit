import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { Select, type SelectProps } from "./Select";

const OPTIONS = [
  { value: "eu", label: "Europe" },
  { value: "us", label: "United States" },
  { value: "apac", label: "Asia Pacific", disabled: true },
];

const meta = {
  title: "UI/Select",
  component: Select,
  args: { options: OPTIONS, placeholder: "Choose a region", "aria-label": "Region" },
} satisfies Meta<typeof Select>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

function ControlledSelect(args: SelectProps) {
  const [value, setValue] = useState("eu");
  return (
    <div className="flex w-64 flex-col gap-2">
      <Select {...args} value={value} onValueChange={setValue} />
      <p className="text-xs text-fg-muted">Selected: {value}</p>
    </div>
  );
}

export const Controlled: Story = {
  render: (args) => <ControlledSelect {...args} />,
};

export const Invalid: Story = {
  render: (args) => (
    <div className="w-64">
      <Select {...args} aria-invalid="true" />
    </div>
  ),
};
