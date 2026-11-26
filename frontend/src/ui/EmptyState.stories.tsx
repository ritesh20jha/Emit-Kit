import type { Meta, StoryObj } from "@storybook/react-vite";
import { FolderOpen } from "lucide-react";
import { Button } from "./Button";
import { EmptyState } from "./EmptyState";

const meta = {
  title: "UI/EmptyState",
  component: EmptyState,
  args: {
    icon: FolderOpen,
    title: "No projects yet",
    description: "Create a project to generate SDKs from its spec.",
    action: <Button>New project</Button>,
  },
} satisfies Meta<typeof EmptyState>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
