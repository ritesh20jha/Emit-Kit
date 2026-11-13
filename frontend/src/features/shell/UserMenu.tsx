import type { components } from "@/lib/api/generated/schema";

type Me = components["schemas"]["MeResponse"];

/**
 * The signed-in user's identity. There are no account actions yet: the API has no sign-out endpoint.
 */
export function UserMenu({ user }: { user: Pick<Me, "login" | "name" | "avatarUrl"> }) {
  return (
    <div className="flex min-w-0 items-center gap-2 text-sm text-fg">
      <img src={user.avatarUrl} alt="" className="size-6 shrink-0 rounded-full" />
      <span className="truncate">{user.name ?? user.login}</span>
    </div>
  );
}
