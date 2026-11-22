import { expect, test } from '@playwright/test';
import { createExistingProject, createNewRepoProject } from './support/newProject';
import { bodyOf, onlyRequest, requests, resetJournal } from './support/mockJournal';
import { login } from './support/login';
import { acmeOrgId, removeProjectFor } from './support/projects';

const EXISTING_REPO = 'acme/billing-api';
const NEW_REPO = 'acme/billing-starter';

test.describe('Create project from existing repo, and with a new repo + starter spec', () => {
  test('existing repo', async ({ page, request }) => {
    await login(page);
    const orgId = await acmeOrgId(page);
    // A project left by an earlier run would make the create a conflict.
    await removeProjectFor(page, orgId, EXISTING_REPO);
    await resetJournal(request);

    await createExistingProject(page, orgId, EXISTING_REPO, 'openapi/billing.yaml');

    // The spec is read from the host, and linking an existing repository creates none.
    const specReads = await requests(request, { method: 'GET', urlPath: '/repos/acme/billing-api/contents/openapi/billing.yaml' });
    expect(specReads.length, 'the spec is read from the repository').toBeGreaterThan(0);
    expect(await requests(request, { method: 'POST', urlPath: '/user/repos' })).toHaveLength(0);
    expect(await requests(request, { method: 'POST', urlPath: '/orgs/acme/repos' })).toHaveLength(0);

    await page.goto(`/orgs/${orgId}`);
    await expect(page.getByRole('link', { name: EXISTING_REPO, exact: true })).toBeVisible();
  });

  test('new repo + starter spec', async ({ page, request }) => {
    await login(page);
    const orgId = await acmeOrgId(page);
    await removeProjectFor(page, orgId, NEW_REPO);
    await resetJournal(request);

    await createNewRepoProject(page, orgId, 'billing-starter');

    // The repository is created private, with the name the wizard typed.
    const created = onlyRequest(await requests(request, { method: 'POST', urlPath: '/orgs/acme/repos' }));
    expect(bodyOf<{ name: string; private: boolean }>(created)).toMatchObject({ name: 'billing-starter', private: true });

    // The starter spec is committed at the default spec path, on the branch the host reports as the default.
    const write = bodyOf<{ branch: string; content: string }>(
      onlyRequest(await requests(request, { method: 'PUT', urlPath: '/repos/acme/billing-starter/contents/openapi.yaml' })),
    );
    expect(write.branch).toBe('main');
    expect(Buffer.from(write.content, 'base64').toString('utf8')).toContain('title: "billing-starter"');

    await page.goto(`/orgs/${orgId}`);
    await expect(page.getByRole('link', { name: NEW_REPO, exact: true })).toBeVisible();
  });
});
