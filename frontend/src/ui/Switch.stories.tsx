import type { Meta, StoryObj } from "@storybook/react-vite";
import { Switch } from "./Switch";

const meta = {
  title: "UI/Switch",
  component: Switch,
  args: { "aria-label": "Notify on failure" },
} satisfies Meta<typeof Switch>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const States: Story = {
  render: (args) => (
    <div className="flex flex-col gap-3">
      <Switch {...args} />
      <Switch {...args} defaultChecked />
      <Switch {...args} disabled />
      <Switch {...args} disabled defaultChecked />
    </div>
  ),
};
