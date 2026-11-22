import { expect, type Page } from '@playwright/test';

/** The id of the acme organization the signed-in user is a member of, read from the API the shell uses. */
export async function acmeOrgId(page: Page): Promise<string> {
  const res = await page.request.get('/api/v1/me');
  expect(res.ok()).toBeTruthy();
  const me = (await res.json()) as { orgs: { orgId: string; name: string }[] };
  const acme = me.orgs.find((org) => org.name === 'acme');
  if (!acme) throw new Error(`expected the acme org, got ${JSON.stringify(me.orgs.map((org) => org.name))}`);
  return acme.orgId;
}

/** The XSRF header the API wants on a mutation, taken from the cookie the signed-in browser holds. */
async function xsrfHeaders(page: Page): Promise<Record<string, string>> {
  const cookie = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN');
  if (!cookie) throw new Error('no XSRF-TOKEN cookie: sign in before a mutation');
  return { 'X-XSRF-TOKEN': cookie.value };
}

/**
 * Deletes the org's project for `repoFullName`, if an earlier run left one behind. The stack keeps its database between
 * runs, and a repository can have one project per organization. Goes through the API, as the UI's delete does.
 */
export async function removeProjectFor(page: Page, orgId: string, repoFullName: string): Promise<void> {
  const repoName = repoFullName.split('/')[1] ?? repoFullName;
  const res = await page.request.get(`/api/v1/orgs/${orgId}/projects`, { params: { q: repoName, limit: 100 } });
  expect(res.ok(), `GET projects returned ${res.status()}`).toBeTruthy();
  const { items } = (await res.json()) as { items: { id: string; repoFullName: string }[] };
  for (const project of items.filter((item) => item.repoFullName === repoFullName)) {
    const del = await page.request.delete(`/api/v1/projects/${project.id}`, { headers: await xsrfHeaders(page) });
    expect(del.ok(), `DELETE project returned ${del.status()}`).toBeTruthy();
  }
}

/**
 * Removes the acme project for `repo` once a test is over, so the next run starts clean even if this one failed midway.
 * A test that never signed in has no session and nothing to remove.
 */
export async function removeAcmeProject(page: Page, repo: string): Promise<void> {
  const signedIn = (await page.context().cookies()).some((cookie) => cookie.name === 'XSRF-TOKEN');
  if (!signedIn) return;
  await removeProjectFor(page, await acmeOrgId(page), repo);
}

/** The id of the project whose config or editor page the browser is on, read from its URL. */
export function projectIdOf(page: Page): string {
  const id = /\/projects\/([^/]+)\/(?:config|editor|runs)/.exec(new URL(page.url()).pathname)?.[1];
  if (!id) throw new Error(`no project id in ${new URL(page.url()).pathname}`);
  return id;
}
