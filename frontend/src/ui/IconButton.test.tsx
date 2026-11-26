import { render, screen } from "@testing-library/react";
import { IconButton } from "./IconButton";

test("takes its accessible name from label", () => {
  render(
    <IconButton label="Close">
      <span aria-hidden="true">x</span>
    </IconButton>,
  );
  expect(screen.getByRole("button", { name: "Close" })).toBeInTheDocument();
});

test("disabled prop disables the button", () => {
  render(
    <IconButton label="Close" disabled>
      <span aria-hidden="true">x</span>
    </IconButton>,
  );
  expect(screen.getByRole("button", { name: "Close" })).toBeDisabled();
});

test("loading is not an IconButton prop", () => {
  const element = (
    // @ts-expect-error loading is a Button-only prop, so it must not type-check on IconButton
    <IconButton label="Close" loading>
      <span aria-hidden="true" />
    </IconButton>
  );
  expect(element).toBeTruthy();
});
