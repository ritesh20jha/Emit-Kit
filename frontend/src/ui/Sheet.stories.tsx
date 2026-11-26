import type { Meta, StoryObj } from "@storybook/react-vite";
import { Button } from "./Button";
import { Sheet } from "./Sheet";

const meta = {
  title: "UI/Sheet",
  component: Sheet,
  // Open by default, so the baseline shows the panel itself.
  args: {
    open: true,
    side: "right",
    title: "Filter runs",
    description: "Only runs that match every filter are listed.",
    trigger: <Button variant="secondary">Filters</Button>,
    children: <p className="text-sm text-fg-muted">Status, branch and date range.</p>,
    footer: <Button>Apply</Button>,
  },
} satisfies Meta<typeof Sheet>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Right: Story = {};

export const Left: Story = { args: { side: "left", title: "Project navigation" } };
