import { render, screen } from "@testing-library/react";
import { Button } from "./Button";

test("loading button keeps its label for screen readers and is busy", () => {
  render(<Button loading>Save</Button>);
  expect(screen.getByRole("button", { name: "Save" })).toHaveAttribute("aria-busy", "true");
});
