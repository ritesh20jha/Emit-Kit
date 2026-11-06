import type { Meta, StoryObj } from "@storybook/react-vite";
import { Input } from "./Input";

const meta = {
  title: "UI/Input",
  component: Input,
  args: { placeholder: "emitkit-sdk" },
} satisfies Meta<typeof Input>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const States: Story = {
  render: (args) => (
    <div className="flex w-72 flex-col gap-3">
      <Input {...args} />
      <Input {...args} aria-invalid="true" defaultValue="emit kit" />
      <Input {...args} disabled defaultValue="read only" />
    </div>
  ),
};
