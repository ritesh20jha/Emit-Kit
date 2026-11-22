import { expect, type APIRequestContext } from '@playwright/test';

// The WireMock GitHub stand-in, reached on the host through its published port (deploy/compose/docker-compose.e2e.yml).
const MOCK_ADMIN = 'http://127.0.0.1:8089/__admin';

export interface JournalEntry {
  request: { method: string; url: string; absoluteUrl: string };
}

/** The requests the mock has received since the journal was last reset, matched by method and path (no query). */
export async function requests(
  request: APIRequestContext,
  match: { method: string; urlPath: string },
): Promise<JournalEntry[]> {
  const res = await request.get(`${MOCK_ADMIN}/requests`);
  expect(res.ok(), `GET /__admin/requests returned ${res.status()}`).toBeTruthy();
  const { requests: journal } = (await res.json()) as { requests: JournalEntry[] };
  return journal.filter(
    (entry) => entry.request.method === match.method && entry.request.url.split('?')[0] === match.urlPath,
  );
}

/** Empties the journal. Call it before the step whose requests a test wants to see. */
export async function resetJournal(request: APIRequestContext): Promise<void> {
  const res = await request.delete(`${MOCK_ADMIN}/requests`);
  expect(res.ok(), `DELETE /__admin/requests returned ${res.status()}`).toBeTruthy();
}
