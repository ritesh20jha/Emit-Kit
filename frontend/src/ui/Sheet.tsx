import { cn } from "@/lib/utils/cn";
import { DialogFrame, type DialogFrameProps } from "./Dialog";

export type SheetSide = "left" | "right" | "top" | "bottom";

const SIDE: Record<SheetSide, string> = {
  left: "inset-y-0 left-0 h-full w-full max-w-sm overflow-y-auto border-r",
  right: "inset-y-0 right-0 h-full w-full max-w-sm overflow-y-auto border-l",
  top: "inset-x-0 top-0 max-h-[80vh] w-full overflow-y-auto border-b",
  bottom: "inset-x-0 bottom-0 max-h-[80vh] w-full overflow-y-auto border-t",
};

export type SheetProps = Omit<DialogFrameProps, "panelClassName"> & {
  /** The edge the panel slides in from. */
  side?: SheetSide;
};

/** A panel anchored to one edge of the viewport. Same focus and dismissal rules as Dialog. */
export function Sheet({ side = "right", ...props }: SheetProps) {
  return (
    <DialogFrame
      {...props}
      data-side={side}
      panelClassName={cn("motion-safe:animate-overlay-fade", SIDE[side])}
    />
  );
}
