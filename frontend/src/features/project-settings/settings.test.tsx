import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, expect, test } from "vitest";
import { qk } from "@/lib/api/queryKeys";
import { project, projectHandlers } from "@/test/msw/handlers/projects";
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
  // The deleted project must not stay in the cache, where the palette and a later visit could read it.
  expect(router.options.context.queryClient.getQueryState(qk.projects.one("p1"))).toBeUndefined();
});

test("a failed Re-register does not leave the page saying Registered", async () => {
  const user = userEvent.setup();
  // The failed attempt leaves the repository without its webhook, so a fresh read of the project says so.
  let registered = true;
  server.use(
    http.get("/api/v1/projects/:id", () =>
      HttpResponse.json({ ...project, webhook: { registered, webhookId: registered ? 42 : null } }),
    ),
    http.post("/api/v1/projects/:id/webhook", () => {
      registered = false;
      return HttpResponse.json({ type: "about:blank", title: "Bad Gateway", status: 502, code: "VCS_AUTH" }, { status: 502 });
    }),
  );
  renderRoute("/projects/p1/settings");
  expect(await screen.findByText(/^Registered\./)).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Re-register" }));

  expect(await screen.findByText("GitHub access was revoked or expired. Reconnect GitHub and try again.")).toBeInTheDocument();
  expect(await screen.findByText(/^Not registered\./)).toBeInTheDocument();
  expect(screen.queryByText(/^Registered\./)).not.toBeInTheDocument();
});
