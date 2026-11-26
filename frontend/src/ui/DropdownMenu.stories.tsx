import type { Meta, StoryObj } from "@storybook/react-vite";
import { Button } from "./Button";
import { DropdownMenu, DropdownMenuItem, DropdownMenuSeparator } from "./DropdownMenu";

const meta = {
  title: "UI/DropdownMenu",
  component: DropdownMenu,
  // Open by default, so the baseline shows the menu itself.
  args: {
    defaultOpen: true,
    trigger: <Button variant="secondary">Actions</Button>,
    children: (
      <>
        <DropdownMenuItem>Rename</DropdownMenuItem>
        <DropdownMenuItem>Duplicate</DropdownMenuItem>
        <DropdownMenuItem disabled>Archive</DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem tone="danger">Delete</DropdownMenuItem>
      </>
    ),
  },
} satisfies Meta<typeof DropdownMenu>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
