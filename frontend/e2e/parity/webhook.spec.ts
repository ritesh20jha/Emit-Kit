import { expect, test } from '@playwright/test';
import { bodyOf, onlyRequest, requests, resetJournal } from './support/mockJournal';
import { login } from './support/login';
import { createExistingProject } from './support/newProject';
import { acmeOrgId, removeAcmeProject, removeProjectFor } from './support/projects';

const REPO = 'acme/billing-api';

// The delete step is the test's own, so this only removes a project left behind by a failed run.
test.afterEach(async ({ page }) => {
  await removeAcmeProject(page, REPO);
});

test('Webhook registered on create and deleted on project delete', async ({ page, request }) => {
  await login(page);
  const orgId = await acmeOrgId(page);
  await removeProjectFor(page, orgId, REPO);
  await resetJournal(request);

  await createExistingProject(page, orgId, REPO, 'openapi/billing.yaml');

  // Exactly one push webhook is registered on create. Its secret is checked for presence only; the journal redacts it.
  const hook = onlyRequest(await requests({ method: 'POST', urlPath: '/repos/acme/billing-api/hooks' }));
  expect(hook.hasSecret, 'the hook carries a secret').toBe(true);
  expect(bodyOf<{ config: { url: string } }>(hook).config.url).toMatch(/\/webhooks\/github$/);

  // Deleting goes through the settings tab's danger zone, and the typed confirmation is the repository's full name.
  await page.getByRole('navigation', { name: 'Sections' }).getByRole('link', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'Delete project' }).click();
  const dialog = page.getByRole('dialog', { name: 'Delete this project?' });
  await dialog.getByRole('textbox').fill(REPO);
  await dialog.getByRole('button', { name: 'Delete project' }).click();
  await expect(page).toHaveURL(new RegExp(`/orgs/${orgId}$`));

  const deletes = await requests({ method: 'DELETE', urlPath: '/repos/acme/billing-api/hooks/42' });
  expect(deletes.length, 'the webhook is deleted with the project').toBe(1);
});
