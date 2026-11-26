const RELATIVE = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
const RELATIVE_UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["day", 86_400],
  ["hour", 3_600],
  ["minute", 60],
];

/** The age of a moment in words: "3 hours ago", "yesterday", "now". */
export function relativeTime(iso: string, now = Date.now()): string {
  const seconds = Math.round((Date.parse(iso) - now) / 1000);
  for (const [unit, size] of RELATIVE_UNITS) {
    if (Math.abs(seconds) >= size) return RELATIVE.format(Math.round(seconds / size), unit);
  }
  return RELATIVE.format(0, "second");
}

/** A calendar date in words, such as "Nov 10, 2026". */
export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en", { dateStyle: "medium" });
}
