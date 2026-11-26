import { cn } from "@/lib/utils/cn";
import { DialogFrame, type DialogFrameProps } from "./Dialog";

export type SheetSide = "left" | "right";

const SIDE: Record<SheetSide, string> = {
  left: "inset-y-0 left-0 h-full w-full max-w-sm overflow-y-auto border-r",
  right: "inset-y-0 right-0 h-full w-full max-w-sm overflow-y-auto border-l",
};

export type SheetProps = Omit<DialogFrameProps, "panelClassName"> & {
  /** The edge the panel slides in from. Defaults to right. */
  side?: SheetSide;
};

/** A panel anchored to the left or right edge of the viewport. Same focus, dismissal and returnFocusRef rules as Dialog. */
export function Sheet({ side = "right", ...props }: SheetProps) {
  return (
    <DialogFrame
      {...props}
      data-side={side}
      panelClassName={cn("motion-safe:animate-overlay-fade", SIDE[side])}
    />
  );
}
