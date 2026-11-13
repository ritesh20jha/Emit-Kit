import type { ReactNode } from "react";
import type { components } from "@/lib/api/generated/schema";
import { QuotaIndicator } from "./QuotaIndicator";
import { ThemeMenu } from "./ThemeMenu";
import { UserMenu } from "./UserMenu";

type Me = components["schemas"]["MeResponse"];

export interface TopBarProps {
  user: Pick<Me, "login" | "name" | "avatarUrl">;
  /** Leading control, e.g. the drawer button on narrow screens. */
  menu?: ReactNode;
}

export function TopBar({ user, menu }: TopBarProps) {
  return (
    <header className="flex h-12 shrink-0 items-center justify-between gap-3 border-b border-border px-4">
      <div className="flex min-w-0 items-center gap-2">{menu}</div>
      <div className="flex min-w-0 items-center gap-3">
        <QuotaIndicator />
        <ThemeMenu />
        <UserMenu user={user} />
      </div>
    </header>
  );
}
