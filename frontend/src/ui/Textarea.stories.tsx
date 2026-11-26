import type { Meta, StoryObj } from "@storybook/react-vite";
import { Textarea } from "./Textarea";

const meta = {
  title: "UI/Textarea",
  component: Textarea,
  args: { placeholder: "Describe the change" },
} satisfies Meta<typeof Textarea>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const States: Story = {
  render: (args) => (
    <div className="flex w-72 flex-col gap-3">
      <Textarea {...args} />
      <Textarea {...args} aria-invalid="true" defaultValue="Too short" />
      <Textarea {...args} disabled defaultValue="read only" />
    </div>
  ),
};
