import { expect, test } from '@playwright/test';
import { login } from './support/login';
import { createExistingProject } from './support/newProject';
import { acmeOrgId, projectIdOf, removeAcmeProject, removeProjectFor } from './support/projects';

// The AI key is typed into the form, and a Playwright trace would record that request body. Traces are off for this file,
// so the key is not written to a trace archive on failure.
test.use({ trace: 'off' });

const REPO = 'acme/billing-api';
// A dummy key. It is typed into the form and must never come back: not in the rendered page, not in the API response.
const AI_KEY = 'test-ai-key-123';

test.afterEach(async ({ page }) => {
  await removeAcmeProject(page, REPO);
});

test('Config: outputs, languages, output dir, npm scope, PyPI name, version strategy, AI key', async ({ page }) => {
  await login(page);
  const orgId = await acmeOrgId(page);
  // A project left by an earlier run would make the create a conflict.
  await removeProjectFor(page, orgId, REPO);
  await createExistingProject(page, orgId, REPO, 'openapi/billing.yaml');
  const projectId = projectIdOf(page);

  // The defaults are an SDK for TypeScript, with the output directory and the managed version strategy. Change each one.
  await page.getByRole('checkbox', { name: 'CLI', exact: true }).click();
  await page.getByRole('checkbox', { name: 'Docs', exact: true }).click();
  await page.getByRole('checkbox', { name: 'Python', exact: true }).click();
  await page.getByLabel('Output directory', { exact: true }).fill('sdk-out/');
  await page.getByLabel('npm scope', { exact: true }).fill('acme-billing');
  await page.getByLabel('PyPI name', { exact: true }).fill('emitkit-billing');
  await page.getByRole('combobox', { name: 'Version source' }).click();
  await page.getByRole('option', { name: 'Spec version', exact: true }).click();
  // While the key is in the field, a failure snapshot (error-context.md, under test-results) would record it. So if the
  // save does not complete, the field is cleared before the failure is rethrown.
  const aiKey = page.getByLabel('AI key', { exact: true });
  await aiKey.fill(AI_KEY);
  try {
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByText('Configuration saved')).toBeVisible();
  } catch (error) {
    await aiKey.fill('');
    throw error;
  }

  // Reloading reads the saved config from the API, so every value shown here came back from the server.
  await page.reload();
  for (const name of ['SDK', 'CLI', 'Docs']) {
    await expect(page.getByRole('checkbox', { name, exact: true }), `${name} output is on`).toBeChecked();
  }
  await expect(page.getByRole('checkbox', { name: 'MCP server', exact: true })).not.toBeChecked();
  await expect(page.getByRole('checkbox', { name: 'TypeScript', exact: true })).toBeChecked();
  await expect(page.getByRole('checkbox', { name: 'Python', exact: true })).toBeChecked();
  await expect(page.getByRole('checkbox', { name: 'Java', exact: true })).not.toBeChecked();
  await expect(page.getByLabel('Output directory', { exact: true })).toHaveValue('sdk-out/');
  await expect(page.getByLabel('npm scope', { exact: true })).toHaveValue('acme-billing');
  await expect(page.getByLabel('PyPI name', { exact: true })).toHaveValue('emitkit-billing');
  await expect(page.getByRole('combobox', { name: 'Version source' })).toHaveText('Spec version');

  // The key is write-only: the form shows that one is set, and no input holds it after the save.
  await expect(page.getByText('Key set', { exact: true })).toBeVisible();
  await expect(page.getByLabel('AI key', { exact: true })).toHaveCount(0);
  const html = await page.content();
  expect(html.includes(AI_KEY), 'the rendered page does not carry the key').toBe(false);

  // The config API returns the flag, never the value. Only booleans are asserted, so a failure prints no key.
  const res = await page.request.get(`/api/v1/projects/${projectId}/config`);
  expect(res.ok(), `GET config returned ${res.status()}`).toBeTruthy();
  const raw = await res.text();
  const config = JSON.parse(raw) as Record<string, unknown>;
  expect(config.aiKeySet, 'the API reports the key as set').toBe(true);
  expect(raw.includes(AI_KEY), 'the config response does not carry the key').toBe(false);
});
