import { screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, expect, test } from "vitest";
import { projectHandlers } from "@/test/msw/handlers/projects";
import { server } from "@/test/msw/server";
import { renderRoute, stubViewport } from "@/test/renderRoute";

const SNAPSHOTS = [
  { id: "s3", contentHash: "a1b2c3d4e5", createdAt: "2026-11-10T09:00:00Z", infoVersion: "1.4.0" },
  { id: "s2", contentHash: "f6e5d4c3b2", createdAt: "2026-11-05T09:00:00Z", infoVersion: "1.3.0" },
  { id: "s1", contentHash: "0a9b8c7d6e", createdAt: "2026-11-01T09:00:00Z", infoVersion: null },
];

const DIFF = {
  added: [{ method: "get", operationId: "listInvoices", path: "/invoices" }],
  removed: [],
  modified: [
    {
      op: { method: "post", operationId: "createInvoice", path: "/invoices" },
      changes: [{ breaking: false, kind: "DESCRIPTION_CHANGED", location: "requestBody" }],
    },
    {
      op: { method: "get", operationId: "getInvoice", path: "/invoices/{id}" },
      changes: [{ breaking: true, kind: "RESPONSE_PROPERTY_REMOVED", location: "responses.200.total" }],
    },
  ],
};

beforeEach(() => {
  stubViewport();
  server.use(
    ...projectHandlers,
    http.get("/api/v1/projects/:id/spec/snapshots", () => HttpResponse.json(SNAPSHOTS)),
    http.get("/api/v1/projects/:id/spec/diff", () => HttpResponse.json(DIFF)),
  );
});

test("diff page reads from/to from the URL", async () => {
  let requested = new URLSearchParams();
  server.use(
    http.get("/api/v1/projects/:id/spec/diff", ({ request }) => {
      requested = new URL(request.url).searchParams;
      return HttpResponse.json(DIFF);
    }),
  );
  renderRoute("/projects/p1/diff?from=s1&to=s2");

  // The first render also loads the lazy page module, which is slow on a busy machine.
  await screen.findByRole("region", { name: "Changes" }, { timeout: 3000 });
  expect(requested.get("from")).toBe("s1");
  expect(requested.get("to")).toBe("s2");
});

test("breaking changes listed first", async () => {
  renderRoute("/projects/p1/diff");

  const changes = await screen.findByRole("region", { name: "Changes" });
  const headings = within(changes).getAllByRole("heading").map((heading) => heading.textContent);
  expect(headings).toEqual(["Breaking changes", "Added", "Modified"]);
  const firstItem = within(changes).getAllByRole("listitem")[0];
  expect(firstItem).toHaveTextContent("getInvoice");
  expect(firstItem).toHaveTextContent("Breaking");
});

test("fewer than two saved versions show an empty state", async () => {
  server.use(http.get("/api/v1/projects/:id/spec/snapshots", () => HttpResponse.json([SNAPSHOTS[0]])));
  renderRoute("/projects/p1/diff");

  expect(await screen.findByRole("heading", { name: "Nothing to compare yet" })).toBeInTheDocument();
});
