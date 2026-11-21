/** The diff page's URL search params: the two saved versions compared. An absent one takes the default. */
export interface DiffSearch {
  /** The older version's snapshot id. */
  from?: string;
  /** The newer version's snapshot id. */
  to?: string;
}

/** A snapshot id as the router reads it: a text value, or a number that a numeric id arrives as. Empty is dropped. */
function asId(value: unknown): string | undefined {
  const text = typeof value === "number" ? String(value) : value;
  return typeof text === "string" && text !== "" ? text : undefined;
}

/** Reads the search params. A stale or malformed id is dropped, so the page still opens on the default pair. */
export function parseDiffSearch(search: Record<string, unknown>): DiffSearch {
  const result: DiffSearch = {};
  const from = asId(search.from);
  const to = asId(search.to);
  if (from !== undefined) result.from = from;
  if (to !== undefined) result.to = to;
  return result;
}
