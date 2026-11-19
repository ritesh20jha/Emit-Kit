import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, expect, test } from "vitest";
import { projectHandlers } from "@/test/msw/handlers/projects";
import { server } from "@/test/msw/server";
import { renderRoute, stubViewport } from "@/test/renderRoute";

beforeEach(() => {
  stubViewport();
  server.use(...projectHandlers);
});

test("delete needs typed confirmation", async () => {
  const user = userEvent.setup();
  let deleteCalls = 0;
  server.use(
    http.delete("/api/v1/projects/:id", () => {
      deleteCalls += 1;
      return new HttpResponse(null, { status: 204 });
    }),
    http.get("/api/v1/orgs/:orgId/projects", () => HttpResponse.json({ items: [], nextCursor: null })),
  );
  const router = renderRoute("/projects/p1/settings");

  await user.click(await screen.findByRole("button", { name: "Delete project" }));
  const dialog = await screen.findByRole("dialog");
  const confirm = within(dialog).getByRole("button", { name: "Delete project" });
  const typed = within(dialog).getByRole("textbox");

  expect(confirm).toBeDisabled();
  await user.type(typed, "billing-api");
  expect(confirm).toBeDisabled();
  await user.clear(typed);
  await user.type(typed, "acme/billing-api");
  expect(confirm).toBeEnabled();
  expect(deleteCalls).toBe(0);

  await user.click(confirm);

  await waitFor(() => expect(router.state.location.pathname).toBe("/orgs/org1"));
  expect(deleteCalls).toBe(1);
  expect(await screen.findByText("acme/billing-api was deleted")).toBeInTheDocument();
});
