import { expect, test } from '@playwright/test';
import { bodyOf, onlyRequest, requests, resetJournal } from './support/mockJournal';
import { login } from './support/login';
import { createExistingProject } from './support/newProject';
import { acmeOrgId, removeProjectFor } from './support/projects';

const REPO = 'acme/billing-api';

test('Webhook registered on create and deleted on project delete', async ({ page, request }) => {
  await login(page);
  const orgId = await acmeOrgId(page);
  await removeProjectFor(page, orgId, REPO);
  await resetJournal(request);

  await createExistingProject(page, orgId, REPO, 'openapi/billing.yaml');

  // Exactly one push webhook is registered on create.
  const hook = bodyOf<{ config: { url: string; secret?: unknown } }>(
    onlyRequest(await requests(request, { method: 'POST', urlPath: '/repos/acme/billing-api/hooks' })),
  );
  expect(hook.config.url).toMatch(/\/webhooks\/github$/);
  // Only the presence of the secret is checked. Its value is never printed.
  expect(typeof hook.config.secret === 'string' && hook.config.secret.length > 0, 'the hook carries a secret').toBe(true);

  // Deleting goes through the settings tab's danger zone, and the typed confirmation is the repository's full name.
  await page.getByRole('navigation', { name: 'Sections' }).getByRole('link', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'Delete project' }).click();
  const dialog = page.getByRole('dialog', { name: 'Delete this project?' });
  await dialog.getByRole('textbox').fill(REPO);
  await dialog.getByRole('button', { name: 'Delete project' }).click();
  await expect(page).toHaveURL(new RegExp(`/orgs/${orgId}$`));

  expect(await requests(request, { method: 'DELETE', urlPath: '/repos/acme/billing-api/hooks/42' })).toHaveLength(1);
});
