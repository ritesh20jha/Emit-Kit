import type { Meta, StoryObj } from "@storybook/react-vite";
import { RouteTabs } from "./RouteTabs";

const meta = {
  title: "UI/RouteTabs",
  component: RouteTabs,
  // The preview router sits on /projects/p1/runs, so Runs is the active tab.
  args: {
    tabs: [
      { to: "/projects/p1", label: "Overview" },
      { to: "/projects/p1/runs", label: "Runs" },
      { to: "/projects/p1/settings", label: "Settings" },
    ],
  },
} satisfies Meta<typeof RouteTabs>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
