import type { Meta, StoryObj } from "@storybook/react-vite";
import { Button } from "./Button";
import { Dialog } from "./Dialog";
import { Input } from "./Input";
import { Label } from "./Label";

const meta = {
  title: "UI/Dialog",
  component: Dialog,
  // Open by default, so the baseline shows the dialog itself.
  args: {
    open: true,
    title: "Rename project",
    description: "The new name shows in the sidebar and on run pages.",
    trigger: <Button variant="secondary">Rename</Button>,
    children: (
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="story-project-name">Name</Label>
        <Input id="story-project-name" defaultValue="emitkit-api" />
      </div>
    ),
    footer: (
      <>
        <Button variant="secondary">Cancel</Button>
        <Button>Save</Button>
      </>
    ),
  },
} satisfies Meta<typeof Dialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
