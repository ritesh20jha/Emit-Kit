import { fireEvent, render, screen } from "@testing-library/react";
import { Button } from "./Button";

test("loading button keeps its label for screen readers and is busy", () => {
  render(<Button loading>Save</Button>);
  expect(screen.getByRole("button", { name: "Save" })).toHaveAttribute("aria-busy", "true");
});

test("loading button stays focusable, so keyboard focus is not lost", () => {
  render(<Button loading>Save</Button>);
  const button = screen.getByRole("button", { name: "Save" });
  button.focus();
  expect(document.activeElement).toBe(button);
});

test("loading button does not call onClick", () => {
  const onClick = vi.fn();
  render(<Button loading onClick={onClick}>Save</Button>);
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  expect(onClick).not.toHaveBeenCalled();
});
