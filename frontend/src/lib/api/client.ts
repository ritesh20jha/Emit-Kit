import createClient, { type Middleware } from "openapi-fetch";
import { toAppError } from "./appError";
import type { paths } from "./generated/schema";

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

/** Reads a cookie by name. Returns undefined when it is absent. */
function readCookie(name: string): string | undefined {
  const prefix = `${name}=`;
  const match = document.cookie.split("; ").find((cookie) => cookie.startsWith(prefix));
  return match === undefined ? undefined : decodeURIComponent(match.slice(prefix.length));
}

function redirectToLogin() {
  const next = window.location.pathname + window.location.search;
  window.location.href = `/login?next=${encodeURIComponent(next)}`;
}

const sessionMiddleware: Middleware = {
  onRequest({ request }) {
    if (!SAFE_METHODS.has(request.method)) {
      const token = readCookie("XSRF-TOKEN");
      if (token !== undefined) request.headers.set("X-XSRF-TOKEN", token);
    }
  },
  async onResponse({ response }) {
    if (response.status !== 401) return;
    const body: unknown = await response.clone().json().catch(() => undefined);
    redirectToLogin();
    throw toAppError(response, body);
  },
};

/**
 * The typed API client. A 401 redirects to login and rejects; other failures come back as `error`.
 * The base is absolute because Node's Request (used by the test runner) rejects relative URLs.
 * The app is served from the same origin as its API, so this is the same URL in the browser.
 */
export const api = createClient<paths>({
  baseUrl: `${window.location.origin}/api/v1`,
  credentials: "include",
  // Looked up per call, not captured at import, so MSW can patch fetch after this module loads.
  fetch: (request) => globalThis.fetch(request),
});
api.use(sessionMiddleware);
