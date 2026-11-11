import { http, HttpResponse } from "msw";
import type { components } from "@/lib/api/generated/schema";

/** The signed-in user every test gets unless it overrides /me. */
export const defaultMe: components["schemas"]["MeResponse"] = {
  id: "u1",
  login: "octocat",
  name: "Octo Cat",
  avatarUrl: "https://example.test/avatar.png",
  orgs: [{ orgId: "org1", name: "Acme", slug: "acme", isPersonal: false, role: "ADMIN" }],
};

export const meHandler = http.get("/api/v1/me", () => HttpResponse.json(defaultMe));
