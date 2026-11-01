import type { ReactNode } from "react";
import { cn } from "@/lib/utils/cn";
import { type ButtonProps, type ButtonSize, buttonClasses } from "./Button";

const SQUARE: Record<ButtonSize, string> = {
  sm: "size-7 px-0",
  md: "size-8 px-0",
};

export type IconButtonProps = Omit<ButtonProps, "children" | "aria-label" | "loading"> & {
  /** Accessible name. Required, because an icon alone has no text for screen readers. */
  label: string;
  children: ReactNode;
};

export function IconButton({
  label,
  variant = "ghost",
  size = "md",
  type = "button",
  className,
  children,
  ...props
}: IconButtonProps) {
  return (
    <button
      type={type}
      aria-label={label}
      className={cn(buttonClasses(variant, size), SQUARE[size], className)}
      {...props}
    >
      {children}
    </button>
  );
}
