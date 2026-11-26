import { useQueryClient } from "@tanstack/react-query";
import { AppError } from "@/lib/api/appError";
import type { components } from "@/lib/api/generated/schema";
import { signOut } from "@/lib/auth/signOut";
import { Button, DropdownMenu, DropdownMenuItem, DropdownMenuSeparator, toast } from "@/ui";

type Me = components["schemas"]["MeResponse"];

/** The signed-in user, with a menu for their account. */
export function UserMenu({ user }: { user: Pick<Me, "login" | "name" | "avatarUrl"> }) {
  const queryClient = useQueryClient();
  const onSignOut = () =>
    signOut(queryClient).catch((error: unknown) =>
      toast.error(error instanceof AppError ? error : { message: "Sign-out failed. Try again." }),
    );
  return (
    <DropdownMenu
      align="end"
      trigger={
        <Button variant="ghost" size="sm" className="min-w-0 gap-2 px-2">
          <img src={user.avatarUrl} alt="" className="size-6 shrink-0 rounded-full" />
          <span className="truncate text-fg">{user.name ?? user.login}</span>
        </Button>
      }
    >
      <p className="px-2 py-1.5 text-xs text-fg-muted">Signed in as @{user.login}</p>
      <DropdownMenuSeparator />
      <DropdownMenuItem onSelect={onSignOut}>Sign out</DropdownMenuItem>
    </DropdownMenu>
  );
}
