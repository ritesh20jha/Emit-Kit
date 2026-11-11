import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { AppError } from "@/lib/api/appError";
import { server } from "@/test/msw/server";
import { meQuery } from "./session";

// The queryFn does not read its query context, so an empty object stands in for it.
const queryFn = meQuery.queryFn as (context: never) => Promise<unknown>;
function fetchMe() {
  return queryFn({} as never);
}

describe("meQuery", () => {
  it("does not throw on an empty successful response", async () => {
    server.use(http.get("/api/v1/me", () => new HttpResponse(null, { status: 204 })));

    await expect(fetchMe()).resolves.toBeUndefined();
  });

  it("throws an AppError carrying the problem code on a failed response", async () => {
    server.use(
      http.get(
        "/api/v1/me",
        () =>
          HttpResponse.json(
            { type: "about:blank", title: "Not found", status: 404, code: "NOT_FOUND" },
            { status: 404 },
          ),
      ),
    );

    await expect(fetchMe()).rejects.toBeInstanceOf(AppError);
    await expect(fetchMe()).rejects.toMatchObject({ code: "NOT_FOUND", status: 404 });
  });
});
