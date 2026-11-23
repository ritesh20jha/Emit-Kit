import type { Browser, BrowserContext, Page } from '@playwright/test';
import { login } from './login';
import { createExistingProject } from './newProject';
import { acmeOrgId, projectIdOf, removeProjectFor } from './projects';
import { newSessionContext } from './session';

/** A project that one describe block shares across its tests: its admin session and its ids. */
export interface SharedProject {
  context: BrowserContext;
  page: Page;
  orgId: string;
  projectId: string;
  repo: string;
}

/**
 * Signs in as the admin in a context of its own, removes any project an earlier run left for `repo`, and creates the
 * project from the existing repo. Used in beforeAll, so the project and its runs are shared by the describe's tests.
 */
export async function openSharedProject(browser: Browser, repo: string, specPath: string): Promise<SharedProject> {
  const context = await newSessionContext(browser);
  const page = await context.newPage();
  try {
    await login(page);
    const orgId = await acmeOrgId(page);
    await removeProjectFor(page, orgId, repo);
    await createExistingProject(page, orgId, repo, specPath);
    return { context, page, orgId, projectId: projectIdOf(page), repo };
  } catch (error) {
    await context.close();
    throw error;
  }
}

/** Removes the shared project, then closes its context, even when the removal fails. Used in afterAll. */
export async function closeSharedProject(shared: SharedProject | undefined): Promise<void> {
  if (!shared) return;
  try {
    await removeProjectFor(shared.page, shared.orgId, shared.repo);
  } finally {
    await shared.context.close();
  }
}
