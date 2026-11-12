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

/** Set once a 401 has started the redirect, so concurrent 401s navigate only once. */
let redirectingToLogin = false;

function redirectToLogin() {
  const { pathname, search } = window.location;
  // Already on the login page (or redirecting): a 401 there must not navigate again.
  if (redirectingToLogin || pathname === "/login" || pathname.startsWith("/login/")) return;
  redirectingToLogin = true;
  window.location.href = `/login?next=${encodeURIComponent(pathname + search)}`;
}

/** Test-only: clears the one-shot redirect flag so each test starts clean. */
export function resetLoginRedirectForTests() {
  redirectingToLogin = false;
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
    // Only a session failure ends the session. Other 401 codes (e.g. VCS_AUTH) reject without navigating.
    const code = (body as { code?: unknown } | null | undefined)?.code;
    if (code === undefined || code === "UNAUTHENTICATED") redirectToLogin();
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
