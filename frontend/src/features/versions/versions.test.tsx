import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, test } from "vitest";
import { projectHandlers } from "@/test/msw/handlers/projects";
import { server } from "@/test/msw/server";
import { renderRoute, stubViewport } from "@/test/renderRoute";

beforeEach(() => {
  stubViewport();
  server.use(...projectHandlers);
});

test("versions row expands changelog", async () => {
  const user = userEvent.setup();
  renderRoute("/projects/p1/versions");

  const row = await screen.findByRole("button", { name: "1.4.0" });
  expect(row).toHaveAttribute("aria-expanded", "false");
  expect(screen.queryByText("Added the invoice endpoints.")).not.toBeInTheDocument();

  await user.click(row);

  expect(row).toHaveAttribute("aria-expanded", "true");
  expect(screen.getByText("Added the invoice endpoints.")).toBeInTheDocument();
  // The changelog is plain text: markup in it is shown as written, and no image element is created.
  expect(screen.getByText('<img src=x onerror="alert(1)">')).toBeInTheDocument();
  expect(document.querySelector('img[src="x"]')).toBeNull();
});
