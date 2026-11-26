import type { Meta, StoryObj } from "@storybook/react-vite";
import { ErrorState } from "./ErrorState";

const meta = {
  title: "UI/ErrorState",
  component: ErrorState,
  args: {
    error: { code: "RUN_NOT_FOUND", message: "This run no longer exists." },
    onRetry: () => {},
  },
} satisfies Meta<typeof ErrorState>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
