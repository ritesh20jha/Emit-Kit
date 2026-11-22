/**
 * v1 feature 9: roles (admin, editor, viewer; section 3 of the v1 design).
 *
 * The mock has one fixture user, who is the only admin of acme, and a sole admin cannot demote itself. So the viewer is a
 * second GitHub user, VIEWER.login (support/identity.ts): it signs in once so GitHub sync adds it as an editor, then the
 * admin sets its role to VIEWER on the API. Its sign-ins are made possible by identity stubs that the afterEach sweeps.
 */
import { expect, test, type Browser, type Page } from '@playwright/test';
import { xsrfHeaders } from './support/api';
import { login } from './support/login';
import { createExistingProject } from './support/newProject';
import { acmeOrgId, projectIdOf, removeAcmeProject, removeProjectFor } from './support/projects';
import { requests, resetJournal } from './support/mockJournal';
import { sweepTempStubs } from './support/mockStubs';
import { addViewerIdentity, signInAsViewer, VIEWER } from './support/identity';
import { newSessionContext } from './support/session';

const REPO = 'acme/billing-api';
const SPEC_PATH = 'openapi/billing.yaml';
const SPEC_JOURNAL_PATH = '/repos/acme/billing-api/contents/openapi/billing.yaml';
// Appended to the spec by the viewer. The spec stays valid YAML, and GitHub must still see no write.
const VIEWER_EDIT = '\n# viewer edit\n';

interface MemberRow {
  userId: string;
  login: string;
  role: string;
  roleSource: string;
}

async function listMembers(page: Page, orgId: string): Promise<MemberRow[]> {
  const res = await page.request.get(`/api/v1/orgs/${orgId}/members`);
  expect(res.ok(), `GET members returned ${res.status()}`).toBeTruthy();
  return (await res.json()) as MemberRow[];
}

/**
 * Makes the viewer fixture a VIEWER of acme. It signs in once, so GitHub sync adds it to acme as an editor, and then the
 * admin page sets the role. The role is set as MANUAL, so later syncs leave it alone.
 */
async function makeViewer(browser: Browser, admin: Page, orgId: string): Promise<void> {
  const first = await newSessionContext(browser);
  try {
    await login(await first.newPage());
  } finally {
    await first.close();
  }
  const member = (await listMembers(admin, orgId)).find((row) => row.login === VIEWER.login);
  if (!member) throw new Error('the viewer fixture is not a member of acme after its sign-in');
  const res = await admin.request.patch(`/api/v1/orgs/${orgId}/members/${member.userId}`, {
    data: { role: 'VIEWER' },
    headers: await xsrfHeaders(admin),
  });
  expect(res.ok(), `PATCH member role returned ${res.status()}`).toBeTruthy();
}

test.describe('9 roles (admin/editor/viewer)', () => {
  test.describe.configure({ mode: 'serial' });

  // Identity stubs and any other temporary stub are removed, and the project too, even when one removal fails.
  test.afterEach(async ({ page }) => {
    try {
      await sweepTempStubs();
    } finally {
      await removeAcmeProject(page, REPO);
    }
  });

  test('9 roles (admin/editor/viewer): an admin changes a member role on the members page', async ({ page, browser }) => {
    await login(page);
    const orgId = await acmeOrgId(page);
    await addViewerIdentity();
    await makeViewer(browser, page, orgId);

    await page.goto(`/orgs/${orgId}/members`);
    const row = page.getByRole('row').filter({ hasText: VIEWER.login });
    await expect(row.getByText('Set manually', { exact: true }), 'the viewer role was set manually').toBeVisible();
    await page.getByRole('combobox', { name: `Role for ${VIEWER.login}` }).click();
    await page.getByRole('option', { name: 'EDITOR', exact: true }).click();

    await expect(row.getByText('Set manually', { exact: true }), 'the changed role is marked as set manually').toBeVisible();
    const member = (await listMembers(page, orgId)).find((m) => m.login === VIEWER.login);
    expect(member?.role, 'the new role is saved').toBe('EDITOR');
    expect(member?.roleSource, 'the role is a manual one').toBe('MANUAL');
  });

  test('9 roles (admin/editor/viewer): a viewer reads the spec, but cannot save it, start a run or change roles', async ({ page, browser }) => {
    await login(page);
    const orgId = await acmeOrgId(page);
    await removeProjectFor(page, orgId, REPO);
    await createExistingProject(page, orgId, REPO, SPEC_PATH);
    const projectId = projectIdOf(page);
    await addViewerIdentity();
    await makeViewer(browser, page, orgId);

    const viewer = await signInAsViewer(browser);
    try {
      const vpage = viewer.page;
      const viewerId = (await listMembers(page, orgId)).find((m) => m.login === VIEWER.login)?.userId;
      if (!viewerId) throw new Error('the viewer fixture has no membership');

      // Read: the spec comes back (SPEC_VIEW is held by every role).
      const read = await vpage.request.get(`/api/v1/projects/${projectId}/spec`);
      expect(read.status(), 'a viewer can read the spec').toBe(200);
      const { content, sha } = (await read.json()) as { content: string; sha: string };

      // Save: refused with FORBIDDEN, and GitHub is not written to.
      await resetJournal(page.request);
      const save = await vpage.request.put(`/api/v1/projects/${projectId}/spec`, {
        data: { content: content + VIEWER_EDIT, sha, generate: false },
        headers: await xsrfHeaders(vpage),
      });
      expect(save.status(), 'a viewer cannot save the spec').toBe(403);
      expect(((await save.json()) as { code?: string }).code, 'the refusal carries FORBIDDEN').toBe('FORBIDDEN');
      expect((await requests({ method: 'PUT', urlPath: SPEC_JOURNAL_PATH })).length, 'GitHub received no spec write').toBe(0);

      // Start a run: refused (RUN_TRIGGER is not a viewer's).
      const run = await vpage.request.post(`/api/v1/projects/${projectId}/runs`, {
        data: { dryRun: false },
        headers: await xsrfHeaders(vpage),
      });
      expect(run.status(), 'a viewer cannot start a run').toBe(403);

      // Change a role: refused (MEMBER_ROLE_EDIT is an admin's), and the viewer's own role stays as it was.
      const promote = await vpage.request.patch(`/api/v1/orgs/${orgId}/members/${viewerId}`, {
        data: { role: 'EDITOR' },
        headers: await xsrfHeaders(vpage),
      });
      expect(promote.status(), 'a viewer cannot change roles').toBe(403);

      // UI: the members page shows roles without any selector to a viewer.
      await vpage.goto(`/orgs/${orgId}/members`);
      await expect(vpage.getByText(VIEWER.login, { exact: true }), 'the members page loads for a viewer').toBeVisible();
      await expect(vpage.getByRole('combobox', { name: /^Role for / }), 'no role selector for a viewer').toHaveCount(0);

      // UI: Save on the editor is refused, and the edit stays unsaved.
      await vpage.goto(`/projects/${projectId}/editor`);
      const specBox = vpage.getByRole('textbox', { name: 'OpenAPI spec' });
      await expect(specBox).toBeVisible();
      await specBox.click();
      await vpage.keyboard.press('Control+End');
      await vpage.keyboard.insertText(VIEWER_EDIT);
      const refused = vpage.waitForResponse(
        (res) => res.request().method() === 'PUT' && new URL(res.url()).pathname === `/api/v1/projects/${projectId}/spec`,
      );
      await vpage.getByRole('button', { name: 'Save', exact: true }).click();
      expect((await refused).status(), 'the editor save is refused by the API').toBe(403);
      await expect(vpage.getByRole('status').filter({ hasText: 'Unsaved changes' }), 'the edit is still unsaved').toBeVisible();
    } finally {
      await viewer.context.close();
    }
  });
});
