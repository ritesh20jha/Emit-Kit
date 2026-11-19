import { useEffect, useState } from "react";

/** Quiet time after the last keystroke before a name or path is checked against GitHub. */
export const CHECK_DELAY_MS = 300;

/** The value once it has stopped changing for `delay` ms. The first value is returned at once. */
export function useDebouncedValue<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(timer);
  }, [value, delay]);
  return debounced;
}
