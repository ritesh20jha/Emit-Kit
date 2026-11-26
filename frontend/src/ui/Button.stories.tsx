import type { Meta, StoryObj } from "@storybook/react-vite";
import { Button } from "./Button";

const meta = {
  title: "UI/Button",
  component: Button,
  args: { children: "Save changes" },
} satisfies Meta<typeof Button>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Variants: Story = {
  render: (args) => (
    <div className="flex flex-wrap items-center gap-2">
      <Button {...args} variant="primary" />
      <Button {...args} variant="secondary" />
      <Button {...args} variant="ghost" />
      <Button {...args} variant="danger" />
    </div>
  ),
};

export const Sizes: Story = {
  render: (args) => (
    <div className="flex items-center gap-2">
      <Button {...args} size="sm" />
      <Button {...args} size="md" />
    </div>
  ),
};

export const States: Story = {
  render: (args) => (
    <div className="flex flex-wrap items-center gap-2">
      <Button {...args} loading />
      <Button {...args} variant="secondary" loading />
      <Button {...args} disabled />
      <Button {...args} variant="secondary" disabled />
    </div>
  ),
};
