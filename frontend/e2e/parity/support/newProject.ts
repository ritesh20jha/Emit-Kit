import { expect, type Page } from '@playwright/test';

/**
 * Drives the new-project wizard for a repository that already exists: picks it on step 1, sets the spec path on step 2,
 * and creates the project on step 3. Resolves once the wizard lands on the project's config page.
 */
export async function createExistingProject(page: Page, orgId: string, repo: string, specPath: string): Promise<void> {
  await page.goto(`/orgs/${orgId}/projects/new`);
  await page.getByRole('group', { name: 'Existing repositories' }).getByRole('button', { name: repo }).click();
  await page.getByRole('button', { name: 'Next' }).click();
  await page.getByLabel('Spec path').fill(specPath);
  await page.getByRole('button', { name: 'Next' }).click();
  await page.getByRole('button', { name: 'Create project' }).click();
  await expect(page).toHaveURL(/\/projects\/[^/]+\/config/);
}

/**
 * Drives the wizard for a new repository: step 1 names it (private), step 2 keeps the default spec path, and step 3
 * creates the repository, its starter spec and the project. Resolves once the wizard lands on the project's config page.
 */
export async function createNewRepoProject(page: Page, orgId: string, name: string): Promise<void> {
  await page.goto(`/orgs/${orgId}/projects/new`);
  await page.getByRole('combobox', { name: 'Repository' }).click();
  await page.getByRole('option', { name: 'New repository' }).click();
  await page.getByLabel('Repository name').fill(name);
  await page.getByRole('button', { name: 'Next' }).click();
  await page.getByRole('button', { name: 'Next' }).click();
  await page.getByRole('button', { name: 'Create project' }).click();
  await expect(page).toHaveURL(/\/projects\/[^/]+\/config/);
}
