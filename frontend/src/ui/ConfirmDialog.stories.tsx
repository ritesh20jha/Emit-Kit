import type { Meta, StoryObj } from "@storybook/react-vite";
import { Button } from "./Button";
import { ConfirmDialog } from "./ConfirmDialog";

const meta = {
  title: "UI/ConfirmDialog",
  component: ConfirmDialog,
  // Open by default, so the baseline shows the dialog itself.
  args: {
    open: true,
    title: "Delete project",
    description: "This removes the spec, its runs and every generated artifact. It cannot be undone.",
    confirmLabel: "Delete project",
    tone: "danger",
    typedConfirmation: "acme/api",
    onConfirm: () => Promise.resolve(),
  },
  render: (args) => (
    <>
      <Button variant="secondary">Delete project</Button>
      <ConfirmDialog {...args} />
    </>
  ),
} satisfies Meta<typeof ConfirmDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Danger: Story = {};

export const Default: Story = {
  args: {
    title: "Cancel run",
    description: "The run stops after the current step.",
    confirmLabel: "Cancel run",
    tone: "default",
    typedConfirmation: undefined,
  },
};
