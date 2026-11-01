import type { Meta, StoryObj } from "@storybook/react-vite";
import { type RunStatus, StatusPill } from "./StatusPill";

const STATUSES: RunStatus[] = ["QUEUED", "RUNNING", "AWAITING_APPROVAL", "SUCCESS", "FAILED", "CANCELLED"];

const meta = {
  title: "UI/StatusPill",
  component: StatusPill,
  args: { status: "SUCCESS" },
} satisfies Meta<typeof StatusPill>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Single: Story = {};

export const AllStatuses: Story = {
  render: () => (
    <div className="flex flex-wrap items-center gap-2">
      {STATUSES.map((status) => (
        <StatusPill key={status} status={status} />
      ))}
    </div>
  ),
};
