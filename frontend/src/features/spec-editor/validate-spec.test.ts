import { expect, test } from "vitest";
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
