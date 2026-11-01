import { type ClassValue, clsx } from "clsx";
import { extendTailwindMerge } from "tailwind-merge";

// Teach tailwind-merge our token radii, so `rounded-control` and `rounded-none` resolve against each other.
const twMerge = extendTailwindMerge({ extend: { theme: { radius: ["control", "card"] } } });

/** Joins class names and resolves Tailwind conflicts (later classes win). */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
