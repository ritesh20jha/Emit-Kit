import type { Meta, StoryObj } from "@storybook/react-vite";
import { Settings, X } from "lucide-react";
import { IconButton } from "./IconButton";

const meta = {
  title: "UI/IconButton",
  component: IconButton,
  args: { label: "Settings", children: <Settings aria-hidden="true" className="size-4" /> },
} satisfies Meta<typeof IconButton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Variants: Story = {
  render: (args) => (
    <div className="flex items-center gap-2">
      <IconButton {...args} variant="ghost" />
      <IconButton {...args} variant="secondary" />
      <IconButton {...args} variant="danger" label="Close">
        <X aria-hidden="true" className="size-4" />
      </IconButton>
    </div>
  ),
};

export const Sizes: Story = {
  render: (args) => (
    <div className="flex items-center gap-2">
      <IconButton {...args} size="sm" variant="secondary" />
      <IconButton {...args} size="md" variant="secondary" />
    </div>
  ),
};
