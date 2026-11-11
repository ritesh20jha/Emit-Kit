import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";
import { server } from "@/test/msw/server";
import { api } from "./client";
import { AppError, ERROR_CODES, toAppError } from "./appError";
import { errorMessages } from "./errorMessages";

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; Max-Age=0; path=/";
});

describe("401 handling", () => {
  it("redirects to /login with the current path as next, and rejects the request", async () => {
    server.use(http.get("/api/v1/me", () => new HttpResponse(null, { status: 401 })));
    window.history.pushState({}, "", "/projects/p1/runs?status=FAILED");
    // jsdom cannot navigate, so the location is replaced with a settable stand-in.
    const location = {
      pathname: window.location.pathname,
      search: window.location.search,
      href: window.location.href,
    };
    vi.stubGlobal("location", location);

    await expect(api.GET("/me")).rejects.toBeInstanceOf(AppError);
    expect(location.href).toContain("/login?next=%2Fprojects%2Fp1%2Fruns%3Fstatus%3DFAILED");
  });
});

describe("CSRF header", () => {
  it("sends the XSRF-TOKEN cookie as X-XSRF-TOKEN on POST", async () => {
    document.cookie = "XSRF-TOKEN=tok-123; path=/";
    let seen: string | null = "not called";
    server.use(
      http.post("/api/v1/me/sync-orgs", ({ request }) => {
        seen = request.headers.get("X-XSRF-TOKEN");
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await api.POST("/me/sync-orgs");

    expect(seen).toBe("tok-123");
  });

  it("does not send the header on GET", async () => {
    document.cookie = "XSRF-TOKEN=tok-123; path=/";
    let seen: string | null = "not called";
    server.use(
      http.get("/api/v1/me", ({ request }) => {
        seen = request.headers.get("X-XSRF-TOKEN");
        return HttpResponse.json({ id: "u1" });
      }),
    );

    await api.GET("/me");

    expect(seen).toBeNull();
  });
});

describe("error mapping", () => {
  it("maps a 409 problem body to an AppError with its code and plain message", async () => {
    server.use(
      http.put(
        "/api/v1/projects/p1/spec",
        () =>
          HttpResponse.json(
            { type: "about:blank", title: "Conflict", status: 409, detail: "The spec changed", code: "SPEC_CONFLICT" },
            { status: 409 },
          ),
      ),
    );

    const { error, response } = await api.PUT("/projects/{id}/spec", {
      params: { path: { id: "p1" } },
      body: { content: "openapi: 3.1.0", generate: false, sha: "stale" },
    });
    const err = toAppError(response, error);

    expect(err).toBeInstanceOf(AppError);
    expect(err.code).toBe("SPEC_CONFLICT");
    expect(err.status).toBe(409);
    expect(err.detail).toBe("The spec changed");
    expect(err.message).toBe("Someone else changed this file. Reload to see their version.");
    expect(errorMessages[err.code]).toBe(err.message);
  });

  it("keeps fieldErrors and extra properties, and drops the standard RFC 9457 fields", () => {
    const err = toAppError(new Response(null, { status: 422 }), {
      type: "about:blank",
      title: "Invalid",
      status: 422,
      code: "VALIDATION_FAILED",
      detail: "Fix the form",
      fieldErrors: { name: "Required" },
      retryAfter: 30,
    });

    expect(err.code).toBe("VALIDATION_FAILED");
    expect(err.fieldErrors).toEqual({ name: "Required" });
    expect(err.extra).toEqual({ retryAfter: 30 });
  });

  it.each([
    ["the code is missing", { status: 500 }],
    ["the code is unknown", { status: 500, code: "SOMETHING_NEW" }],
    ["the body is not a problem object", "<html>bad gateway</html>"],
  ])("falls back to INTERNAL when %s", (_label, body) => {
    const err = toAppError(new Response(null, { status: 500 }), body);

    expect(err.code).toBe("INTERNAL");
    expect(err.status).toBe(500);
  });

  it("has exactly the 21 backend codes, each with a plain-language message", () => {
    expect([...ERROR_CODES].sort()).toEqual(
      [
        "SPEC_CONFLICT", "SPEC_NOT_FOUND", "SPEC_TOO_LARGE", "SPEC_INVALID", "LINT_FAILED",
        "BREAKING_BLOCKED", "GENERATOR_ERROR", "CHECK_FAILED", "SANDBOX_TIMEOUT", "STORAGE_ERROR",
        "VCS_CONFLICT", "VCS_AUTH", "VCS_RATE_LIMITED", "SECRETS_UNAVAILABLE", "FORBIDDEN",
        "NOT_FOUND", "VALIDATION_FAILED", "CONFLICT", "RATE_LIMITED", "NOT_IMPLEMENTED", "INTERNAL",
      ].sort(),
    );
    for (const code of ERROR_CODES) {
      expect(errorMessages[code].length).toBeGreaterThan(0);
    }
  });
});
