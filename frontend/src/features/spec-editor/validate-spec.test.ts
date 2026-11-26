import { expect, test, vi } from "vitest";
import { validateSpecText } from "./validate-spec";

const VALID = "openapi: 3.0.3\ninfo:\n  title: Billing\n  version: 1.0.0\npaths: {}\n";

test("empty content is one problem on line 1", async () => {
  expect(await validateSpecText("  \n")).toEqual([{ message: "The spec is empty.", path: "", line: 1 }]);
});

test("a YAML syntax error is placed on the line that breaks", async () => {
  expect(await validateSpecText("openapi: 3.0.3\ninfo: [\n")).toEqual([expect.objectContaining({ path: "", line: 3 })]);
});

test("a schema error is placed on the line of the value it names", async () => {
  const content = "openapi: 3.0.3\ninfo:\n  title: Billing\n  version: 1.0.0\npaths:\n  /invoices:\n    get:\n      responses: oops\n";
  expect(await validateSpecText(content)).toEqual([
    expect.objectContaining({ path: "/paths/~1invoices/get/responses", line: 8 }),
  ]);
});

test("a valid spec has no problems", async () => {
  expect(await validateSpecText(VALID)).toEqual([]);
});

test("an external $ref is never fetched, and the spec still validates", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}"));
  const content = `${VALID}`.replace("paths: {}", "paths:\n  /invoices:\n    $ref: https://example.invalid/invoices.yaml");
  try {
    expect(await validateSpecText(content)).toEqual([]);
    expect(fetch).not.toHaveBeenCalled();
  } finally {
    fetch.mockRestore();
  }
});

test("an alias bomb is one problem, not a thrown error", async () => {
  // Each level holds ten aliases of the level below, so the document expands far past the alias limit.
  const levels = ["l0: &l0 [1, 2, 3]", ...[1, 2, 3, 4].map((n) => `l${n}: &l${n} [${Array(10).fill(`*l${n - 1}`).join(", ")}]`)];
  const issues = await validateSpecText(`${VALID}${levels.join("\n")}\n`);
  expect(issues).toEqual([expect.objectContaining({ message: expect.stringMatching(/alias/i) })]);
});
