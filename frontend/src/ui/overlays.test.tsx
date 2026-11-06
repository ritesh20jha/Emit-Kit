import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { Button } from "./Button";
import { ConfirmDialog } from "./ConfirmDialog";
import { Dialog } from "./Dialog";
import { DropdownMenu, DropdownMenuItem } from "./DropdownMenu";
import { Sheet } from "./Sheet";
import { Toaster } from "./Toaster";
import { toast } from "./toast";
import { Tooltip } from "./Tooltip";
import { ThemeProvider } from "./theme/ThemeProvider";
import type { MockInstance } from "vitest";

// Radix popper measures its content with ResizeObserver, which jsdom does not implement.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver;

// Any console output is a failure: Radix and React warnings must not reach the test log.
let consoleSpies: MockInstance[] = [];
beforeEach(() => {
  consoleSpies = [vi.spyOn(console, "error"), vi.spyOn(console, "warn")];
});
afterEach(() => {
  for (const spy of consoleSpies) {
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  }
  toast.dismiss();
});

function TrapHarness() {
  const [open, setOpen] = useState(false);
  return (
    <>
    <Dialog
      open={open}
      onOpenChange={setOpen}
      title="Edit project"
      description="Changes apply to the next run."
      trigger={<Button variant="secondary">Open dialog</Button>}
      footer={
        <>
          <Button variant="secondary">First</Button>
          <Button>Last</Button>
        </>
      }
    >
      <p>Body text</p>
    </Dialog>
    <Button variant="ghost">Page action</Button>
    </>
  );
}

test("dialog traps focus and returns it on close", async () => {
  const user = userEvent.setup();
  render(<TrapHarness />);
  const opener = screen.getByRole("button", { name: "Open dialog" });
  await user.click(opener);

  const dialog = await screen.findByRole("dialog", { name: "Edit project" });
  await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));

  // Tab keeps focus inside the dialog for more presses than it has tab stops.
  for (let i = 0; i < 6; i++) {
    await user.tab();
    expect(dialog.contains(document.activeElement)).toBe(true);
  }

  // Tab from the last stop wraps to the first, and Shift+Tab from the first wraps to the last.
  const stops = within(dialog).getAllByRole("button");
  const [first] = stops;
  const last = stops.at(-1) as HTMLElement;
  last.focus();
  await user.tab();
  expect(first).toHaveFocus();
  await user.tab({ shift: true });
  expect(last).toHaveFocus();

  // Focus that lands on the page behind (a script, or a click that reaches it) is pulled back in.
  // The page behind is aria-hidden while the dialog is open, so the query opts in to hidden elements.
  screen.getByRole("button", { name: "Page action", hidden: true }).focus();
  expect(dialog.contains(document.activeElement)).toBe(true);

  await user.keyboard("{Escape}");
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  expect(opener).toHaveFocus();
});

test("Escape closes the dialog", async () => {
  const user = userEvent.setup();
  render(<TrapHarness />);
  await user.click(screen.getByRole("button", { name: "Open dialog" }));
  expect(await screen.findByRole("dialog", { name: "Edit project" })).toBeInTheDocument();

  await user.keyboard("{Escape}");
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
});

test("typed confirmation enables the button only on exact match", async () => {
  render(<ConfirmDialog open title="Delete project" typedConfirmation="acme/api" confirmLabel="Delete" onConfirm={vi.fn()} />);
  const confirm = screen.getByRole("button", { name: "Delete" });
  expect(confirm).toBeDisabled();
  await userEvent.type(screen.getByRole("textbox"), "acme/ap");
  expect(confirm).toBeDisabled();
  await userEvent.type(screen.getByRole("textbox"), "i");
  expect(confirm).toBeEnabled();
});

test("ConfirmDialog closes when onConfirm succeeds", async () => {
  const user = userEvent.setup();
  const onOpenChange = vi.fn();
  const onConfirm = vi.fn().mockResolvedValue(undefined);
  render(
    <ConfirmDialog open onOpenChange={onOpenChange} title="Cancel run" description="The run stops." confirmLabel="Stop" onConfirm={onConfirm} />,
  );
  await user.click(screen.getByRole("button", { name: "Stop" }));
  expect(onConfirm).toHaveBeenCalledTimes(1);
  await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
});

test("ConfirmDialog stays open and shows the message and code when onConfirm fails", async () => {
  const user = userEvent.setup();
  const onOpenChange = vi.fn();
  const onConfirm = vi.fn().mockRejectedValue({ code: "SPEC_CONFLICT", message: "Someone else changed this file." });
  render(
    <ConfirmDialog open onOpenChange={onOpenChange} title="Overwrite spec" confirmLabel="Overwrite" onConfirm={onConfirm} />,
  );
  await user.click(screen.getByRole("button", { name: "Overwrite" }));

  const alert = await screen.findByRole("alert");
  expect(within(alert).getByText("Someone else changed this file.")).toBeInTheDocument();
  expect(within(alert).getByText("SPEC_CONFLICT")).toBeInTheDocument();
  expect(onOpenChange).not.toHaveBeenCalledWith(false);
  expect(screen.getByRole("dialog")).toBeInTheDocument();
});

test("pending ConfirmDialog shows loading and blocks dismissal until onConfirm settles", async () => {
  const user = userEvent.setup();
  const onOpenChange = vi.fn();
  let settle!: () => void;
  const onConfirm = vi.fn(() => new Promise<void>((resolve) => (settle = resolve)));
  render(
    <ConfirmDialog
      open
      onOpenChange={onOpenChange}
      title="Delete project"
      confirmLabel="Delete"
      typedConfirmation="acme/api"
      onConfirm={onConfirm}
    />,
  );
  await user.type(screen.getByRole("textbox"), "acme/api");
  await user.click(screen.getByRole("button", { name: "Delete" }));

  expect(screen.getByRole("button", { name: "Delete" })).toHaveAttribute("aria-busy", "true");
  await user.keyboard("{Escape}");
  expect(onOpenChange).not.toHaveBeenCalled();
  expect(screen.getByRole("dialog")).toBeInTheDocument();

  await act(async () => settle());
  await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
});

function ReopenHarness() {
  const [open, setOpen] = useState(true);
  return (
    <>
      <Button onClick={() => setOpen(true)}>Reopen</Button>
      <ConfirmDialog
        open={open}
        onOpenChange={setOpen}
        title="Delete project"
        confirmLabel="Delete"
        typedConfirmation="acme/api"
        onConfirm={vi.fn()}
      />
    </>
  );
}

test("typed text resets when the ConfirmDialog is reopened", async () => {
  const user = userEvent.setup();
  render(<ReopenHarness />);
  await user.type(screen.getByRole("textbox"), "acme");
  await user.keyboard("{Escape}");
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

  await user.click(screen.getByRole("button", { name: "Reopen" }));
  expect(await screen.findByRole("textbox")).toHaveValue("");
});

test("toast.error shows the message and the code in mono", async () => {
  render(
    <ThemeProvider>
      <Toaster />
    </ThemeProvider>,
  );
  act(() => toast.error({ code: "SPEC_CONFLICT", message: "Someone else changed this file." }));

  expect(await screen.findByText("Someone else changed this file.")).toBeInTheDocument();
  const code = screen.getByText("SPEC_CONFLICT");
  expect(code.tagName).toBe("CODE");
  expect(code).toHaveClass("font-mono");
});

test("toast.success shows the message", async () => {
  render(
    <ThemeProvider>
      <Toaster />
    </ThemeProvider>,
  );
  act(() => toast.success("Synced"));
  expect(await screen.findByText("Synced")).toBeInTheDocument();
});

test("DropdownMenu opens from the keyboard and selecting an item fires its handler", async () => {
  const user = userEvent.setup();
  const onRename = vi.fn();
  render(
    <DropdownMenu trigger={<Button variant="secondary">Actions</Button>}>
      <DropdownMenuItem onSelect={onRename}>Rename</DropdownMenuItem>
      <DropdownMenuItem tone="danger">Delete</DropdownMenuItem>
    </DropdownMenu>,
  );
  const trigger = screen.getByRole("button", { name: "Actions" });
  trigger.focus();
  await user.keyboard("{Enter}");

  const rename = await screen.findByRole("menuitem", { name: "Rename" });
  await waitFor(() => expect(rename).toHaveFocus());
  await user.keyboard("{Enter}");

  expect(onRename).toHaveBeenCalledTimes(1);
  await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
});

test("Tooltip shows on keyboard focus and describes its trigger", async () => {
  const user = userEvent.setup();
  render(
    <Tooltip content="Copy commit SHA">
      <Button variant="ghost">Copy</Button>
    </Tooltip>,
  );
  await user.tab();

  const tooltip = await screen.findByRole("tooltip");
  expect(tooltip).toHaveTextContent("Copy commit SHA");
  expect(screen.getByRole("button", { name: "Copy" })).toHaveAccessibleDescription("Copy commit SHA");
});

test("Sheet renders on the requested side with its title", async () => {
  render(
    <Sheet open side="left" title="Filters" description="Narrow the run list">
      <p>Status, branch and date.</p>
    </Sheet>,
  );
  const sheet = await screen.findByRole("dialog", { name: "Filters" });
  expect(sheet).toHaveAttribute("data-side", "left");
  expect(sheet).toHaveAccessibleDescription("Narrow the run list");
});
