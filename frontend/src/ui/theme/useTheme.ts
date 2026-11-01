import { useContext } from "react";
import { ThemeContext, type ThemeContextValue } from "./ThemeProvider";

/** Reads the theme mode and the resolved theme. Must be used under ThemeProvider. */
export function useTheme(): ThemeContextValue {
  const value = useContext(ThemeContext);
  if (!value) {
    throw new Error("useTheme must be used inside a ThemeProvider");
  }
  return value;
}
