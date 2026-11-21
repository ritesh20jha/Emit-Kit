import { http, HttpResponse } from "msw";

const SPEC = `openapi: 3.0.3
info:
  title: Billing API
  version: 1.4.0
paths:
  /invoices:
    get:
      operationId: listInvoices
      responses:
        "200":
          description: The invoices.
`;

/** One diff between the two snapshots: one added, one modified with a breaking change, one removed. */
export const SPEC_DIFF = {
  added: [{ method: "GET", operationId: "listInvoices", path: "/invoices" }],
  modified: [
    {
      op: { method: "POST", operationId: "createInvoice", path: "/invoices" },
      changes: [{ kind: "BODY_BECAME_REQUIRED" as const, location: "requestBody", breaking: true }],
    },
  ],
  removed: [{ method: "DELETE", operationId: "deleteInvoice", path: "/invoices/{id}" }],
};

/** The spec, its lint, its snapshots and the diff between them. */
export const specHandlers = [
  http.get("/api/v1/projects/:id/spec", () => HttpResponse.json({ sha: "a1b2c3d", format: "YAML", content: SPEC })),
  // The editor lints the text it holds. The fixture spec is clean, so the server reports no issues.
  http.post("/api/v1/lint", () => HttpResponse.json([])),
  http.get("/api/v1/projects/:id/spec/snapshots", () =>
    HttpResponse.json([
      { id: "s2", contentHash: "sha256:4f1c", createdAt: "2026-11-10T09:00:00Z", infoVersion: "1.4.0" },
      { id: "s1", contentHash: "sha256:9ab2", createdAt: "2026-11-02T09:00:00Z", infoVersion: "1.3.2" },
    ]),
  ),
  http.get("/api/v1/projects/:id/spec/diff", () => HttpResponse.json(SPEC_DIFF)),
];
