import { MOCK_ADMIN } from './mockJournal';

/** Every stub a spec adds carries this metadata, so a sweep can remove stubs whose ids were never recorded. */
const TEMP_METADATA = { e2eTemp: true };

const JSON_HEADERS = { 'Content-Type': 'application/json' };

/**
 * Adds a stub to the WireMock GitHub mock through its admin API and returns the stub's id. The stub is marked as a
 * temporary e2e stub, so sweepTempStubs removes it even if its id is lost.
 */
export async function addMapping(mapping: object): Promise<string> {
  const res = await fetch(`${MOCK_ADMIN}/mappings`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ ...mapping, metadata: TEMP_METADATA }),
  });
  if (!res.ok) throw new Error(`POST /__admin/mappings returned ${res.status}`);
  const { id } = (await res.json()) as { id: string };
  return id;
}

/** Removes a stub added with addMapping. A 404 means the stub is already gone, which is the state we want. */
export async function removeMapping(id: string): Promise<void> {
  const res = await fetch(`${MOCK_ADMIN}/mappings/${id}`, { method: 'DELETE' });
  if (!res.ok && res.status !== 404) throw new Error(`DELETE /__admin/mappings/${id} returned ${res.status}`);
}

/**
 * Removes every temporary stub, whether or not its id was recorded: lists the mappings and deletes those marked with the
 * temp metadata. Runs before a spec and after each of its tests.
 */
export async function sweepTempStubs(): Promise<void> {
  const res = await fetch(`${MOCK_ADMIN}/mappings`);
  if (!res.ok) throw new Error(`GET /__admin/mappings returned ${res.status}`);
  const { mappings } = (await res.json()) as { mappings: { id: string; metadata?: { e2eTemp?: unknown } }[] };
  const temp = mappings.filter((mapping) => mapping.metadata?.e2eTemp === true);
  const results = await Promise.allSettled(temp.map((mapping) => removeMapping(mapping.id)));
  const failures = results.flatMap((result) => (result.status === 'rejected' ? [result.reason] : []));
  if (failures.length > 0) throw new AggregateError(failures, 'could not sweep the temporary mock stubs');
}
