import type { Meta, StoryObj } from "@storybook/react-vite";
import { Badge } from "./Badge";

const meta = {
  title: "UI/Badge",
  component: Badge,
  args: { children: "v1.2.0" },
} satisfies Meta<typeof Badge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Variants: Story = {
  render: (args) => (
    <div className="flex items-center gap-2">
      <Badge {...args} variant="neutral" />
      <Badge {...args} variant="accent" />
    </div>
  ),
};
