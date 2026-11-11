import { screen } from "@testing-library/react";
import { useLocation } from "@tanstack/react-router";
import { describe, expect, it } from "vitest";
import { qk } from "@/lib/api/queryKeys";
import type { Me } from "@/lib/auth/session";
import { renderWithProviders } from "./render";

function Where() {
  const { pathname } = useLocation();
  return <p>{pathname}</p>;
}

describe("renderWithProviders", () => {
  it("mounts the UI inside a router at the given route", async () => {
    renderWithProviders(<Where />, { route: "/projects/p1/runs?status=FAILED" });

    expect(await screen.findByText("/projects/p1/runs")).toBeInTheDocument();
  });

  it("seeds the /me query when me is given", () => {
    const me: Me = { id: "u9", login: "seeded", name: null, avatarUrl: "", orgs: [] };

    const { queryClient } = renderWithProviders(<p>seeded</p>, { me });

    expect(queryClient.getQueryData(qk.me)).toEqual(me);
  });
});
