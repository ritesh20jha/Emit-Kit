import { expect, type APIRequestContext } from '@playwright/test';
import { MOCK_ADMIN } from './mockJournal';

/**
 * Adds a stub to the WireMock GitHub mock through its admin API and returns the stub's id. A test that adds one keeps the
 * id and removes it with removeMapping, in an afterEach, so a failed test does not leave the mock altered.
 */
export async function addMapping(request: APIRequestContext, mapping: object): Promise<string> {
  const res = await request.post(`${MOCK_ADMIN}/mappings`, { data: mapping });
  expect(res.ok(), `POST /__admin/mappings returned ${res.status()}`).toBeTruthy();
  const { id } = (await res.json()) as { id: string };
  return id;
}

/** Removes a stub added with addMapping. */
export async function removeMapping(request: APIRequestContext, id: string): Promise<void> {
  const res = await request.delete(`${MOCK_ADMIN}/mappings/${id}`);
  expect(res.ok(), `DELETE /__admin/mappings/${id} returned ${res.status()}`).toBeTruthy();
}
