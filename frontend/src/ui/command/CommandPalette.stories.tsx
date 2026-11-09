import type { Meta, StoryObj } from "@storybook/react-vite";
import { CommandPalette } from "./CommandPalette";
import { CommandRegistryProvider, useCommands } from "./CommandRegistry";
import { ShortcutsProvider } from "./ShortcutsProvider";

const noop = () => undefined;

function DemoCommands() {
  useCommands(
    [
      { id: "go-projects", group: "Go", label: "Projects", shortcut: "g p", run: noop },
      { id: "go-runs", group: "Go", label: "Runs", shortcut: "g r", run: noop },
      { id: "go-members", group: "Go", label: "Members", run: noop },
      { id: "act-trigger", group: "Actions", label: "Trigger run", keywords: ["generate"], run: noop },
      { id: "act-editor", group: "Actions", label: "Open editor", run: noop },
      { id: "act-switch-org", group: "Actions", label: "Switch org", keywords: ["workspace"], run: noop },
    ],
    [],
  );
  return null;
}

const meta = {
  title: "UI/CommandPalette",
  component: CommandPalette,
  // Open by default, so the baseline shows the palette itself.
  args: { open: true },
  decorators: [
    (Story) => (
      <ShortcutsProvider>
        <CommandRegistryProvider>
          <DemoCommands />
          <Story />
        </CommandRegistryProvider>
      </ShortcutsProvider>
    ),
  ],
} satisfies Meta<typeof CommandPalette>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
