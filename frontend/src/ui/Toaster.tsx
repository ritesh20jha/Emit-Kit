import type { CSSProperties } from "react";
import { Toaster as Sonner } from "sonner";
import { useTheme } from "./theme/useTheme";

// Sonner paints each toast from these variables, so they carry the design tokens and follow the theme.
const TOKENS = {
  "--normal-bg": "var(--color-bg)",
  "--normal-text": "var(--color-fg)",
  "--normal-border": "var(--color-border-control)",
  "--border-radius": "var(--radius-card)",
  // Sonner hard-codes a system font stack on the toaster; the inline style wins over it.
  fontFamily: "var(--font-sans)",
} as CSSProperties;

export interface ToasterProps {
  /** How long a toast stays, in milliseconds. Defaults to Sonner's 4 seconds. */
  duration?: number;
}

/** Renders toasts from `toast`. Mount it once, under ThemeProvider. */
export function Toaster({ duration }: ToasterProps) {
  const { resolved } = useTheme();
  return (
    <Sonner
      theme={resolved}
      position="bottom-right"
      closeButton
      duration={duration}
      style={TOKENS}
      toastOptions={{ classNames: { description: "!text-fg-muted" } }}
    />
  );
}
