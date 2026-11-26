import { expect, test } from '@playwright/test';
import { login } from './support/login';
import { requests, resetJournal } from './support/mockJournal';

test("GitHub login, org sync, personal org, org switcher", async ({ page, request }) => {
  await resetJournal(request);
  await login(page);

  // Login starts the org sync, which reads the memberships from GitHub.
  await expect.poll(async () => (await requests({ method: 'GET', urlPath: '/user/memberships/orgs' })).length).toBeGreaterThan(0);

  // The org ids come from the API the shell reads, so the test does not hardcode them.
  const meRes = await page.request.get('/api/v1/me');
  expect(meRes.ok()).toBeTruthy();
  const me = (await meRes.json()) as { orgs: { orgId: string; name: string; isPersonal: boolean }[] };
  const personal = me.orgs.find((org) => org.isPersonal);
  const acme = me.orgs.find((org) => org.name === 'acme');
  if (!personal || !acme) throw new Error(`expected the personal and acme orgs, got ${JSON.stringify(me.orgs)}`);

  // The switcher's trigger shows the current org (the personal one after a first login).
  // The sidebar (the complementary landmark) holds the switcher; the top bar's user menu shares the name.
  const sidebar = page.getByRole('complementary');
  await sidebar.getByRole('button', { name: personal.name, exact: true }).click();
  const switcher = page.getByRole('dialog', { name: 'Switch organization' });
  await expect(switcher.getByRole('button', { name: personal.name, exact: true })).toBeVisible();
  await expect(switcher.getByRole('button', { name: 'acme', exact: true })).toBeVisible();

  await switcher.getByRole('button', { name: 'acme', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/orgs/${acme.orgId}$`));
});
