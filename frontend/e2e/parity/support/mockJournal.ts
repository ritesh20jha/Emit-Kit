import { expect, type APIRequestContext } from '@playwright/test';

// The WireMock GitHub stand-in, reached on the host through its published port (deploy/compose/docker-compose.e2e.yml).
const MOCK_ADMIN = 'http://127.0.0.1:8089/__admin';

/** A journaled request as the specs see it: only the fields they use, with any secret redacted. */
export interface JournalEntry {
  request: { method: string; url: string; absoluteUrl: string; body?: string };
  /** True when the JSON body carried a non-empty `secret`. The value is replaced by "[redacted]" in `body`. */
  hasSecret: boolean;
}

interface RawEntry {
  request: { method: string; url: string; absoluteUrl: string; body?: string };
}

/**
 * Replaces every non-empty `secret` value in a JSON body with "[redacted]", and reports whether there was one. The
 * value is never returned, so a failing expectation cannot print it.
 */
function redact(body: string): { body: string; hasSecret: boolean } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    // Not JSON, so no secret field can be read out of it. Only JSON bodies carry one in this mock.
    return { body, hasSecret: false };
  }
  let hasSecret = false;
  const walk = (node: unknown): unknown => {
    if (Array.isArray(node)) return node.map(walk);
    if (node !== null && typeof node === 'object') {
      const out: Record<string, unknown> = {};
      for (const [key, value] of Object.entries(node)) {
        if (key === 'secret' && typeof value === 'string' && value.length > 0) {
          hasSecret = true;
          out[key] = '[redacted]';
        } else {
          out[key] = walk(value);
        }
      }
      return out;
    }
    return node;
  };
  return { body: JSON.stringify(walk(parsed)), hasSecret };
}

/**
 * The requests the mock has received since the journal was last reset, matched by method and path (no query). The
 * journal is read with fetch rather than the request fixture, so the trace does not record the admin response.
 */
export async function requests(match: { method: string; urlPath: string }): Promise<JournalEntry[]> {
  const res = await fetch(`${MOCK_ADMIN}/requests`);
  if (!res.ok) throw new Error(`GET /__admin/requests returned ${res.status}`);
  const { requests: journal } = (await res.json()) as { requests: RawEntry[] };
  return journal
    .filter((entry) => entry.request.method === match.method && entry.request.url.split('?')[0] === match.urlPath)
    .map(({ request: raw }) => {
      // Only the fields below leave this module: headers, the journal's other fields and the raw secret never do.
      const entry = { method: raw.method, url: raw.url, absoluteUrl: raw.absoluteUrl };
      if (raw.body === undefined) return { request: entry, hasSecret: false };
      const redacted = redact(raw.body);
      return { request: { ...entry, body: redacted.body }, hasSecret: redacted.hasSecret };
    });
}

/** The one journaled request in `entries`. The test fails unless there is exactly one; only the count is printed. */
export function onlyRequest(entries: JournalEntry[]): JournalEntry {
  expect(entries.length, 'expected exactly one matching request').toBe(1);
  const [entry] = entries;
  if (!entry) throw new Error('expected exactly one matching request');
  return entry;
}

/** The JSON body of a journaled request, redacted. A request without a body is an error, so a missing field fails. */
export function bodyOf<T>(entry: JournalEntry): T {
  if (entry.request.body === undefined) throw new Error(`${entry.request.url} was journaled without a body`);
  return JSON.parse(entry.request.body) as T;
}

/** Empties the journal. Call it before the step whose requests a test wants to see. */
export async function resetJournal(request: APIRequestContext): Promise<void> {
  const res = await request.delete(`${MOCK_ADMIN}/requests`);
  expect(res.ok(), `DELETE /__admin/requests returned ${res.status()}`).toBeTruthy();
}
