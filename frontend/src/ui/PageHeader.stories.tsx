import type { Meta, StoryObj } from "@storybook/react-vite";
import { Button } from "./Button";
import { PageHeader } from "./PageHeader";

const meta = {
  title: "UI/PageHeader",
  component: PageHeader,
  args: { title: "Runs", description: "Every generation and publish for this project.", actions: <Button>Run now</Button> },
} satisfies Meta<typeof PageHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
