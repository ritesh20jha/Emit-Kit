import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { components } from "@/lib/api/generated/schema";
import { renderWithProviders } from "@/test/render";
import type { Me } from "./session";
import { Can, type Permission, usePermission } from "./permissions";

type Role = components["schemas"]["Role"];

const ROLES: Role[] = ["ADMIN", "EDITOR", "VIEWER"];
const ALL_PERMISSIONS: Permission[] = [
  "ORG_VIEW", "PROJECT_VIEW", "RUN_VIEW", "SPEC_VIEW", "PROJECT_CREATE", "PROJECT_DELETE", "CONFIG_EDIT",
  "SPEC_EDIT", "RUN_TRIGGER", "RUN_CANCEL", "RUN_RERUN", "RUN_APPROVE", "MEMBER_ROLE_EDIT",
];

// Written from the v1 spec §3 table, independently of the implementation's map.
const GRANTED: Record<Role, Permission[]> = {
  ADMIN: ALL_PERMISSIONS,
  EDITOR: [
    "ORG_VIEW", "PROJECT_VIEW", "RUN_VIEW", "SPEC_VIEW",
    "PROJECT_CREATE", "SPEC_EDIT", "RUN_TRIGGER", "RUN_CANCEL", "RUN_RERUN",
  ],
  VIEWER: ["ORG_VIEW", "PROJECT_VIEW", "RUN_VIEW", "SPEC_VIEW"],
};

function meWithRole(role: Role, orgId = "org1"): Me {
  return {
    id: "u1",
    login: "octocat",
    name: null,
    avatarUrl: "https://example.test/avatar.png",
    orgs: [{ orgId, name: "Acme", slug: "acme", isPersonal: false, role }],
  };
}

function Probe({ permission, orgId = "org1" }: { permission: Permission; orgId?: string }) {
  return <output data-testid="probe">{String(usePermission(permission, orgId))}</output>;
}

const matrix = ROLES.flatMap((role) =>
  ALL_PERMISSIONS.map((permission) => [role, permission, GRANTED[role].includes(permission)] as const),
);

describe("usePermission", () => {
  it.each(matrix)("%s %s → %s", (role, permission, allowed) => {
    renderWithProviders(<Probe permission={permission} />, { me: meWithRole(role) });

    expect(screen.getByTestId("probe").textContent).toBe(String(allowed));
  });

  it("is false when the user is not a member of the org", () => {
    renderWithProviders(<Probe permission="ORG_VIEW" />, { me: meWithRole("ADMIN", "other-org") });

    expect(screen.getByTestId("probe").textContent).toBe("false");
  });

  it("is false while /me is loading, then reflects the role once it arrives", async () => {
    renderWithProviders(<Probe permission="RUN_APPROVE" />);

    expect(screen.getByTestId("probe").textContent).toBe("false");
    await waitFor(() => expect(screen.getByTestId("probe").textContent).toBe("true"));
  });
});

describe("Can", () => {
  it("renders children when the permission is granted", () => {
    renderWithProviders(
      <Can permission="SPEC_EDIT" orgId="org1" fallback={<p>read only</p>}>
        <p>editor</p>
      </Can>,
      { me: meWithRole("EDITOR") },
    );

    expect(screen.getByText("editor")).toBeInTheDocument();
    expect(screen.queryByText("read only")).toBeNull();
  });

  it("renders the fallback when the permission is denied", () => {
    renderWithProviders(
      <Can permission="SPEC_EDIT" orgId="org1" fallback={<p>read only</p>}>
        <p>editor</p>
      </Can>,
      { me: meWithRole("VIEWER") },
    );

    expect(screen.getByText("read only")).toBeInTheDocument();
    expect(screen.queryByText("editor")).toBeNull();
  });
});
