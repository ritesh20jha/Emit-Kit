import type { Meta, StoryObj } from "@storybook/react-vite";
import { CopyButton } from "./CopyButton";

const meta = {
  title: "UI/CopyButton",
  component: CopyButton,
  args: { value: "emitkit run --watch", label: "Copy command" },
} satisfies Meta<typeof CopyButton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
