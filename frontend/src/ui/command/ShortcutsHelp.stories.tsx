import type { Meta, StoryObj } from "@storybook/react-vite";
import { CommandRegistryProvider, useCommands } from "./CommandRegistry";
import { ShortcutsHelp } from "./ShortcutsHelp";
import { ShortcutsProvider } from "./ShortcutsProvider";

const noop = () => undefined;

function DemoCommands() {
  useCommands(
    [
      { id: "go-projects", group: "Go", label: "Projects", shortcut: "g p", run: noop },
      { id: "go-runs", group: "Go", label: "Runs", shortcut: "g r", run: noop },
      { id: "page-search", group: "Page", label: "Focus search", shortcut: "/", run: noop },
      { id: "page-save", group: "Editor", label: "Save spec", shortcut: "⌘S", run: noop },
      { id: "go-members", group: "Go", label: "Members", run: noop },
    ],
    [],
  );
  return null;
}

const meta = {
  title: "UI/ShortcutsHelp",
  component: ShortcutsHelp,
  // Open by default, so the baseline shows the dialog itself.
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
} satisfies Meta<typeof ShortcutsHelp>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
