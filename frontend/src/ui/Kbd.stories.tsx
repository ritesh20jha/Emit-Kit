import type { Meta, StoryObj } from "@storybook/react-vite";
import { Kbd } from "./Kbd";

const meta = {
  title: "UI/Kbd",
  component: Kbd,
  args: { children: "K" },
} satisfies Meta<typeof Kbd>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Combination: Story = {
  render: () => (
    <div className="flex items-center gap-1 text-sm text-fg-muted">
      <Kbd>Ctrl</Kbd>
      <span>+</span>
      <Kbd>K</Kbd>
      <span>to open the command palette</span>
    </div>
  ),
};
