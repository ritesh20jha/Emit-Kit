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

// Seven tabs overflow a 360px viewport: the nav scrolls sideways and keeps every link reachable.
export const ManyTabs: Story = {
  args: {
    tabs: [
      { to: "/projects/p1", label: "Overview" },
      { to: "/projects/p1/runs", label: "Runs" },
      { to: "/projects/p1/settings", label: "Settings" },
      { to: "/projects/p1/secrets", label: "Secrets" },
      { to: "/projects/p1/members", label: "Members" },
      { to: "/projects/p1/webhooks", label: "Webhooks" },
      { to: "/projects/p1/audit", label: "Audit log" },
    ],
  },
};
