import { Menu } from "lucide-react";
import { useState } from "react";
import { IconButton, Sheet } from "@/ui";
import { Sidebar, type SidebarProps } from "./Sidebar";

/** Below 768 px the sidebar lives in a left-edge Sheet, opened from a menu button. */
export function MobileDrawer(props: SidebarProps) {
  const [open, setOpen] = useState(false);
  return (
    <Sheet
      open={open}
      onOpenChange={setOpen}
      side="left"
      title="Navigation"
      trigger={
        <IconButton label="Open navigation" variant="ghost">
          <Menu aria-hidden="true" className="size-4" />
        </IconButton>
      }
    >
      <Sidebar {...props} onNavigate={() => setOpen(false)} />
    </Sheet>
  );
}
