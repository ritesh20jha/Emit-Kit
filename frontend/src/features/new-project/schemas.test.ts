import { expect, test } from "vitest";
import { parseNewProjectSearch } from "./schemas";

test("repo params must be owner/name, and anything else is dropped", () => {
  expect(parseNewProjectSearch({ step: 3, repo: "acme/billing-api", outputRepo: "acme/docs.v2" })).toMatchObject({
    repo: "acme/billing-api",
    outputRepo: "acme/docs.v2",
  });
  for (const bad of ["acme", "acme/billing/api", "../x", "acme/..", "./x", "acme/.", "a b/c", "a/b?c", "123"]) {
    expect(parseNewProjectSearch({ repo: bad }).repo).toBeUndefined();
    expect(parseNewProjectSearch({ outputRepo: bad }).outputRepo).toBeUndefined();
  }
});
