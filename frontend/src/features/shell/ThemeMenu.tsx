import { Monitor, type LucideIcon, Moon, Sun } from "lucide-react";
import { DropdownMenu, DropdownMenuItem, IconButton, type ThemeMode, useTheme } from "@/ui";

const OPTIONS: Record<ThemeMode, { label: string; icon: LucideIcon }> = {
  system: { label: "System", icon: Monitor },
  light: { label: "Light", icon: Sun },
  dark: { label: "Dark", icon: Moon },
};

/** Follow the system, or force light or dark. The icon shows the current choice. */
export function ThemeMenu() {
  const { mode, setMode } = useTheme();
  const { label, icon: CurrentIcon } = OPTIONS[mode];
  return (
    <DropdownMenu
      align="end"
      trigger={
        <IconButton label={`Theme: ${label}`} variant="ghost" size="sm">
          <CurrentIcon aria-hidden="true" className="size-4" />
        </IconButton>
      }
    >
      {(Object.keys(OPTIONS) as ThemeMode[]).map((option) => {
        const { label: optionLabel, icon: Icon } = OPTIONS[option];
        return (
          <DropdownMenuItem key={option} onSelect={() => setMode(option)}>
            <Icon aria-hidden="true" className="mr-2 size-4" />
            {optionLabel}
          </DropdownMenuItem>
        );
      })}
    </DropdownMenu>
  );
}
