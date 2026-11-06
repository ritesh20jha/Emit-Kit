import type { Meta, StoryObj } from "@storybook/react-vite";
import { NoAccessState } from "./NoAccessState";

const meta = {
  title: "UI/NoAccessState",
  component: NoAccessState,
  args: { action: "rotate secrets", admins: ["ana@acme.dev", "raj@acme.dev"] },
} satisfies Meta<typeof NoAccessState>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
