import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { MockInstance } from "vitest";
import { Button } from "../Button";
import { type Command, CommandPalette, CommandRegistryProvider, ShortcutsHelp, ShortcutsProvider, useCommands, useShortcut } from "./index";

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
  for (const spy of consoleSpies) {
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  }
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

test("mod+s fires inside an input and prevents the browser save", async () => {
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
