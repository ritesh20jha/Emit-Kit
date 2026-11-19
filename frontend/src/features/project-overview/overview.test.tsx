import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, expect, test } from "vitest";
import { projectHandlers, run, runDetail } from "@/test/msw/handlers/projects";
import { server } from "@/test/msw/server";
import { renderRoute, stubViewport } from "@/test/renderRoute";

beforeEach(() => {
  stubViewport();
  server.use(...projectHandlers);
});

test("overview shows last run, version, spec, health and recent runs", async () => {
  renderRoute("/projects/p1");

  const lastRun = await screen.findByRole("region", { name: "Last run" });
  expect(await within(lastRun).findByText("Succeeded")).toBeInTheDocument();
  expect(within(lastRun).getByText("Manual")).toBeInTheDocument();
  expect(within(lastRun).getByRole("link", { name: "View run" })).toHaveAttribute("href", "/runs/r1");

  const version = screen.getByRole("region", { name: "Version" });
  expect(await within(version).findByText("1.4.0")).toBeInTheDocument();
  expect(within(version).getByText("Minor")).toBeInTheDocument();
  expect(within(version).getByRole("link", { name: "Pull request #18" })).toHaveAttribute(
    "href",
    "https://github.com/acme/billing-api/pull/18",
  );

  const spec = screen.getByRole("region", { name: "Spec" });
  expect(within(spec).getByText("openapi/billing.yaml")).toBeInTheDocument();
  expect(within(spec).getByText("main")).toBeInTheDocument();
  expect(await within(spec).findByText("1 error · 2 warnings · 0 info")).toBeInTheDocument();

  const health = screen.getByRole("region", { name: "Health" });
  expect(within(health).getByText("Webhook registered")).toBeInTheDocument();
  expect(await within(health).findByText("GitHub quota: 4800 of 5000 left")).toBeInTheDocument();
  expect(await within(health).findByText("AI key set")).toBeInTheDocument();

  const recent = screen.getByRole("region", { name: "Recent activity" });
  const links = await within(recent).findAllByRole("link");
  expect(links.map((link) => link.getAttribute("href"))).toEqual(["/runs/r1", "/runs/r2", "/runs/r3", "/runs/r4", "/runs/r5"]);
});

test("trigger run posts and navigates to the run", async () => {
  const user = userEvent.setup();
  let body: unknown;
  server.use(
    http.post("/api/v1/projects/:id/runs", async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(runDetail(run("r9", "QUEUED", "MANUAL", 0, null)), { status: 201 });
    }),
  );
  const router = renderRoute("/projects/p1");

  await user.click(await screen.findByRole("button", { name: "Trigger run" }));

  await waitFor(() => expect(router.state.location.pathname).toBe("/runs/r9"));
  expect(body).toEqual({ dryRun: false });
});

test("tabs are links with aria-current", async () => {
  renderRoute("/projects/p1");

  const tabs = await screen.findByRole("navigation", { name: "Sections" });
  expect(within(tabs).getAllByRole("link").map((link) => link.textContent)).toEqual([
    "Overview",
    "Runs",
    "Editor",
    "Diff",
    "Config",
    "Versions",
    "Settings",
  ]);
  expect(within(tabs).getByRole("link", { name: "Overview" })).toHaveAttribute("href", "/projects/p1");
  expect(within(tabs).getByRole("link", { name: "Overview" })).toHaveAttribute("aria-current", "page");
  expect(within(tabs).getByRole("link", { name: "Versions" })).toHaveAttribute("href", "/projects/p1/versions");
  expect(within(tabs).getByRole("link", { name: "Versions" })).not.toHaveAttribute("aria-current");
});
