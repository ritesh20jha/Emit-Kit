import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useRef, useState } from "react";
import type { MockInstance } from "vitest";
import { Button } from "../Button";
import { Dialog } from "../Dialog";
import {
  type Command,
  CommandPalette,
  CommandRegistryProvider,
  ShortcutsHelp,
  ShortcutsProvider,
  useCommands,
  useShortcut,
} from "./index";

// cmdk measures its list with ResizeObserver and scrolls the selected row into view. jsdom has neither.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver;
Element.prototype.scrollIntoView = () => {};

// Any console output is a failure: React and library warnings must not reach the test log.
let consoleSpies: MockInstance[] = [];
beforeEach(() => {
  consoleSpies = [vi.spyOn(console, "error"), vi.spyOn(console, "warn")];
});
afterEach(() => {
  vi.useRealTimers();
  // Capture the output, then restore every spy before asserting, so a failing assertion cannot leak a spy.
  const output = consoleSpies.flatMap((spy) => spy.mock.calls);
  for (const spy of consoleSpies) spy.mockRestore();
  consoleSpies = [];
  expect(output).toEqual([]);
});

/** Registers commands while mounted. Unmounting it (or passing null) removes them from the palette. */
function CommandSource({ commands }: { commands: Command[] }) {
  useCommands(commands, [commands]);
  return null;
}

interface HarnessProps {
  commands?: Command[] | null;
}

function Harness({ commands = [] }: HarnessProps) {
  return (
    <ShortcutsProvider>
      <CommandRegistryProvider>
        {commands && <CommandSource commands={commands} />}
        <CommandPalette />
        <ShortcutsHelp />
        <Button variant="ghost">Page action</Button>
        <input aria-label="Page field" />
      </CommandRegistryProvider>
    </ShortcutsProvider>
  );
}

function ShortcutProbe({ onGo, onSave }: { onGo?: () => void; onSave?: () => void }) {
  useShortcut("g p", () => onGo?.());
  useShortcut("mod+s", () => onSave?.());
  return <input aria-label="Page field" />;
}

function ShortcutHarness(props: { onGo?: () => void; onSave?: () => void }) {
  return (
    <ShortcutsProvider>
      <ShortcutProbe {...props} />
    </ShortcutsProvider>
  );
}

const PALETTE = { name: "Command palette" };

test("mod+k opens, typing filters, Enter runs", async () => {
  const user = userEvent.setup();
  const run = vi.fn();
  render(
    <Harness
      commands={[
        { id: "a", group: "Go", label: "Projects", run },
        { id: "b", group: "Go", label: "Members", run: vi.fn() },
      ]}
    />,
  );
  await user.keyboard("{Control>}k{/Control}");
  await screen.findByRole("dialog", PALETTE);
  await user.type(screen.getByRole("combobox"), "proj");
  expect(screen.getAllByRole("option")).toHaveLength(1);
  expect(screen.getByRole("option", { name: "Projects" })).toBeInTheDocument();
  await user.keyboard("{Enter}");
  expect(run).toHaveBeenCalledTimes(1);
  await waitFor(() => expect(screen.queryByRole("dialog", PALETTE)).not.toBeInTheDocument());
});

test("? opens shortcut help", async () => {
  const user = userEvent.setup();
  render(
    <Harness
      commands={[
        { id: "a", group: "Go", label: "Projects", shortcut: "g p", run: vi.fn() },
        { id: "b", group: "Go", label: "Members", run: vi.fn() },
      ]}
    />,
  );
  await user.keyboard("?");

  const help = await screen.findByRole("dialog", { name: "Keyboard shortcuts" });
  expect(within(help).getByRole("heading", { name: "Go" })).toBeInTheDocument();
  expect(within(help).getByText("Projects")).toBeInTheDocument();
  expect(within(help).getByText("g")).toBeInTheDocument();
  expect(within(help).getByText("p")).toBeInTheDocument();
  // Only commands that carry a shortcut are listed.
  expect(within(help).queryByText("Members")).not.toBeInTheDocument();
});

test("g p sequence fires; ignored inside inputs", async () => {
  const user = userEvent.setup();
  const onGo = vi.fn();
  render(<ShortcutHarness onGo={onGo} />);

  await user.keyboard("gp");
  expect(onGo).toHaveBeenCalledTimes(1);

  await user.type(screen.getByRole("textbox", { name: "Page field" }), "gp");
  expect(screen.getByRole("textbox", { name: "Page field" })).toHaveValue("gp");
  expect(onGo).toHaveBeenCalledTimes(1);
});

test("g p completes when the second key follows within 1 s", () => {
  vi.useFakeTimers();
  const onGo = vi.fn();
  render(<ShortcutHarness onGo={onGo} />);

  fireEvent.keyDown(document.body, { key: "g" });
  vi.advanceTimersByTime(500);
  fireEvent.keyDown(document.body, { key: "p" });
  expect(onGo).toHaveBeenCalledTimes(1);
});

test("g p does not complete when the second key comes after 1 s", () => {
  vi.useFakeTimers();
  const onGo = vi.fn();
  render(<ShortcutHarness onGo={onGo} />);

  fireEvent.keyDown(document.body, { key: "g" });
  vi.advanceTimersByTime(1001);
  fireEvent.keyDown(document.body, { key: "p" });
  expect(onGo).not.toHaveBeenCalled();
});

test("mod+s fires inside an input and prevents the browser save", () => {
  const onSave = vi.fn();
  render(<ShortcutHarness onSave={onSave} />);
  const field = screen.getByRole("textbox", { name: "Page field" });
  field.focus();

  const notPrevented = fireEvent.keyDown(field, { key: "s", ctrlKey: true });
  expect(onSave).toHaveBeenCalledTimes(1);
  expect(notPrevented).toBe(false);
});

test("mod+k toggles the palette closed when it is already open", async () => {
  const user = userEvent.setup();
  render(<Harness commands={[{ id: "a", group: "Go", label: "Projects", run: vi.fn() }]} />);
  await user.keyboard("{Control>}k{/Control}");
  await screen.findByRole("dialog", PALETTE);
  await user.keyboard("{Control>}k{/Control}");
  await waitFor(() => expect(screen.queryByRole("dialog", PALETTE)).not.toBeInTheDocument());
});

function FocusSearchHarness() {
  const searchRef = useRef<HTMLInputElement>(null);
  useShortcut("/", () => searchRef.current?.focus());
  return <input ref={searchRef} aria-label="Search" />;
}

test("/ moves focus to the search field without typing the slash into it", async () => {
  const user = userEvent.setup();
  render(
    <ShortcutsProvider>
      <FocusSearchHarness />
    </ShortcutsProvider>,
  );
  await user.keyboard("/");

  const search = screen.getByRole("textbox", { name: "Search" });
  expect(search).toHaveFocus();
  expect(search).toHaveValue("");
});

test("a repeated keydown fires nothing, so holding mod+k does not toggle the palette", () => {
  render(<Harness commands={[{ id: "a", group: "Go", label: "Projects", run: vi.fn() }]} />);

  fireEvent.keyDown(document.body, { key: "k", ctrlKey: true, repeat: true });
  expect(screen.queryByRole("dialog", PALETTE)).not.toBeInTheDocument();

  // The same key without repeat still opens the palette, so the guard is the only reason it stayed closed.
  fireEvent.keyDown(document.body, { key: "k", ctrlKey: true });
  expect(screen.getByRole("dialog", PALETTE)).toBeInTheDocument();
});

test("mod+k uses Cmd on macOS and Ctrl+K does not open the palette there", () => {
  const platform = Object.getOwnPropertyDescriptor(window.navigator, "platform");
  Object.defineProperty(window.navigator, "platform", { value: "MacIntel", configurable: true });
  try {
    render(<Harness commands={[{ id: "a", group: "Go", label: "Projects", run: vi.fn() }]} />);

    fireEvent.keyDown(document.body, { key: "k", ctrlKey: true });
    expect(screen.queryByRole("dialog", PALETTE)).not.toBeInTheDocument();

    fireEvent.keyDown(document.body, { key: "k", metaKey: true });
    expect(screen.getByRole("dialog", PALETTE)).toBeInTheDocument();
  } finally {
    if (platform) Object.defineProperty(window.navigator, "platform", platform);
    else delete (window.navigator as { platform?: string }).platform;
  }
});

test("a command that moves focus keeps it on its target after Enter", async () => {
  const user = userEvent.setup();
  const run = vi.fn(() => screen.getByLabelText("Page field").focus());
  render(<Harness commands={[{ id: "focus", group: "Go", label: "Focus page field", run }]} />);
  const target = screen.getByLabelText("Page field");

  await user.keyboard("{Control>}k{/Control}");
  await screen.findByRole("dialog", PALETTE);
  await user.keyboard("{Enter}");

  expect(run).toHaveBeenCalledTimes(1);
  await waitFor(() => expect(screen.queryByRole("dialog", PALETTE)).not.toBeInTheDocument());
  await waitFor(() => expect(target).toHaveFocus());
});

test("choosing a command with the pointer also keeps focus on its target", async () => {
  const user = userEvent.setup();
  const run = vi.fn(() => screen.getByLabelText("Page field").focus());
  render(<Harness commands={[{ id: "focus", group: "Go", label: "Focus page field", run }]} />);
  const target = screen.getByLabelText("Page field");

  await user.keyboard("{Control>}k{/Control}");
  await user.click(await screen.findByRole("option", { name: "Focus page field" }));

  expect(run).toHaveBeenCalledTimes(1);
  await waitFor(() => expect(screen.queryByRole("dialog", PALETTE)).not.toBeInTheDocument());
  await waitFor(() => expect(target).toHaveFocus());
});

function RenameHarness() {
  const [renameOpen, setRenameOpen] = useState(false);
  useCommands([{ id: "rename", group: "Actions", label: "Rename project", run: () => setRenameOpen(true) }], []);
  return (
    <>
      <CommandPalette />
      <Dialog open={renameOpen} onOpenChange={setRenameOpen} title="Rename project">
        <input aria-label="New name" />
      </Dialog>
    </>
  );
}

test("a command that opens another dialog still opens it after the palette closes", async () => {
  const user = userEvent.setup();
  render(
    <ShortcutsProvider>
      <CommandRegistryProvider>
        <RenameHarness />
      </CommandRegistryProvider>
    </ShortcutsProvider>,
  );

  await user.keyboard("{Control>}k{/Control}");
  await screen.findByRole("dialog", PALETTE);
  await user.keyboard("{Enter}");

  expect(await screen.findByRole("dialog", { name: "Rename project" })).toBeInTheDocument();
  await waitFor(() => expect(screen.queryByRole("dialog", PALETTE)).not.toBeInTheDocument());
});

test("Escape closes the palette and focus returns to the element that had it", async () => {
  const user = userEvent.setup();
  render(<Harness commands={[{ id: "a", group: "Go", label: "Projects", run: vi.fn() }]} />);
  const pageAction = screen.getByRole("button", { name: "Page action" });
  await user.click(pageAction);

  await user.keyboard("{Control>}k{/Control}");
  await screen.findByRole("dialog", PALETTE);
  await user.keyboard("{Escape}");

  await waitFor(() => expect(screen.queryByRole("dialog", PALETTE)).not.toBeInTheDocument());
  expect(pageAction).toHaveFocus();
});

test("Escape closes the palette without running a command", async () => {
  const user = userEvent.setup();
  const run = vi.fn();
  render(<Harness commands={[{ id: "a", group: "Go", label: "Projects", run }]} />);
  await user.keyboard("{Control>}k{/Control}");
  await screen.findByRole("dialog", PALETTE);
  await user.keyboard("{Escape}");

  await waitFor(() => expect(screen.queryByRole("dialog", PALETTE)).not.toBeInTheDocument());
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(run).not.toHaveBeenCalled();
});

test("typing a query with no match shows No results", async () => {
  const user = userEvent.setup();
  render(<Harness commands={[{ id: "a", group: "Go", label: "Projects", run: vi.fn() }]} />);
  await user.keyboard("{Control>}k{/Control}");
  await screen.findByRole("dialog", PALETTE);
  await user.type(screen.getByRole("combobox"), "zzz");

  expect(screen.getByText("No results")).toBeInTheDocument();
  expect(screen.queryByRole("option")).not.toBeInTheDocument();
});

test("keywords match the query as well as the label", async () => {
  const user = userEvent.setup();
  render(
    <Harness
      commands={[
        { id: "a", group: "Actions", label: "Invite people", keywords: ["team", "member"], run: vi.fn() },
        { id: "b", group: "Go", label: "Runs", run: vi.fn() },
      ]}
    />,
  );
  await user.keyboard("{Control>}k{/Control}");
  await screen.findByRole("dialog", PALETTE);
  await user.type(screen.getByRole("combobox"), "team");

  expect(screen.getAllByRole("option")).toEqual([screen.getByRole("option", { name: "Invite people" })]);
});

test("the query matches labels and keywords, not command ids", async () => {
  const user = userEvent.setup();
  render(<Harness commands={[{ id: "nav-projects", group: "Go", label: "Open dashboard", run: vi.fn() }]} />);
  await user.keyboard("{Control>}k{/Control}");
  const search = await screen.findByRole("combobox");

  await user.type(search, "nav");
  expect(screen.queryByRole("option")).not.toBeInTheDocument();
  expect(screen.getByText("No results")).toBeInTheDocument();

  await user.clear(search);
  await user.type(search, "dash");
  expect(screen.getByRole("option", { name: "Open dashboard" })).toBeInTheDocument();
});

test("the palette is a named dialog with a labelled combobox and a listbox", async () => {
  const user = userEvent.setup();
  render(<Harness commands={[{ id: "a", group: "Go", label: "Projects", run: vi.fn() }]} />);
  await user.keyboard("{Control>}k{/Control}");

  const palette = await screen.findByRole("dialog", PALETTE);
  expect(within(palette).getByRole("combobox", { name: "Search commands" })).toHaveFocus();
  expect(within(palette).getByRole("listbox")).toBeInTheDocument();
});

test("a command's shortcut is shown in its row", async () => {
  const user = userEvent.setup();
  render(<Harness commands={[{ id: "a", group: "Go", label: "Projects", shortcut: "g p", run: vi.fn() }]} />);
  await user.keyboard("{Control>}k{/Control}");

  const row = await screen.findByRole("option", { name: /Projects/ });
  expect(within(row).getByText("g")).toBeInTheDocument();
  expect(within(row).getByText("p")).toBeInTheDocument();
});

test("commands leave the palette when the component that registered them unmounts", async () => {
  const user = userEvent.setup();
  const commands: Command[] = [{ id: "a", group: "Go", label: "Projects", run: vi.fn() }];
  const { rerender } = render(<Harness commands={commands} />);
  await user.keyboard("{Control>}k{/Control}");
  expect(await screen.findByRole("option", { name: "Projects" })).toBeInTheDocument();
  await user.keyboard("{Escape}");
  await waitFor(() => expect(screen.queryByRole("dialog", PALETTE)).not.toBeInTheDocument());

  rerender(<Harness commands={null} />);
  await user.keyboard("{Control>}k{/Control}");
  await screen.findByRole("dialog", PALETTE);
  expect(screen.queryByRole("option")).not.toBeInTheDocument();
});

test("? typed into the palette search does not open the help dialog", async () => {
  const user = userEvent.setup();
  render(<Harness commands={[{ id: "a", group: "Go", label: "Projects", shortcut: "g p", run: vi.fn() }]} />);
  await user.keyboard("{Control>}k{/Control}");
  const search = await screen.findByRole("combobox");
  await user.type(search, "?");

  expect(search).toHaveValue("?");
  expect(screen.queryByRole("dialog", { name: "Keyboard shortcuts" })).not.toBeInTheDocument();
});
