import type { Meta, StoryObj } from "@storybook/react-vite";
import { CodeBlock } from "./CodeBlock";

const meta = {
  title: "UI/CodeBlock",
  component: CodeBlock,
  args: {
    language: "yaml",
    code: "on: push\njobs:\n  generate:\n    runs-on: ubuntu-latest\n    steps:\n      - run: emitkit generate --all --output ./generated/sdk/typescript/with/a/very/long/path",
  },
} satisfies Meta<typeof CodeBlock>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
