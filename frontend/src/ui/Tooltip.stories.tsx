import type { Meta, StoryObj } from "@storybook/react-vite";
import { Copy } from "lucide-react";
import { IconButton } from "./IconButton";
import { Tooltip } from "./Tooltip";

const meta = {
  title: "UI/Tooltip",
  component: Tooltip,
  // Open by default, so the baseline shows the tooltip itself.
  args: {
    defaultOpen: true,
    side: "right",
    content: "Copy commit SHA",
    children: (
      <IconButton label="Copy commit SHA" variant="ghost">
        <Copy aria-hidden="true" className="size-4" />
      </IconButton>
    ),
  },
} satisfies Meta<typeof Tooltip>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
