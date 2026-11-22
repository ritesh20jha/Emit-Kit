import { expect, test, type Page } from '@playwright/test';
import { login } from './support/login';
import { bodyOf, onlyRequest, requests, resetJournal } from './support/mockJournal';
import { createExistingProject } from './support/newProject';
import { addMapping, removeMapping, sweepTempStubs } from './support/mockStubs';
import { acmeOrgId, projectIdOf, removeAcmeProject, removeProjectFor } from './support/projects';

const REPO = 'acme/billing-api';
const SPEC_PATH = 'openapi/billing.yaml';
const SPEC_URL = '/repos/acme/billing-api/contents/openapi/billing.yaml';
// The file the mock serves (blob sha 1477316f...), decoded.
const ORIGINAL = 'openapi: 3.0.3\ninfo:\n  title: Billing API\n  version: 1.0.0\npaths:\n  /invoices:\n    get:\n      operationId: listInvoices\n      responses:\n        "200":\n          description: The invoices.\n';
const LOADED_SHA = '1477316fd2c1468876564127fe9185f0f4a0f68f';
// What a test types at the end of the spec. It is valid YAML, so the spec stays valid after the edit.
const EDIT = '# parity edit\n';
const EDITED = `${ORIGINAL}${EDIT}`;

/** Mock stubs the conflict test added. afterEach removes them, so a failing test cannot leave the mock altered. */
const addedStubs: string[] = [];

test.describe('Spec editor: load, validate, save with conflict detection, save & generate', () => {
  // A stub from an earlier run that was never removed would break this one, so temporary stubs are swept first.
  test.beforeAll(async () => {
    await sweepTempStubs();
  });

  // Every stub this test added is removed, and the project is removed too, even when a removal fails. A failed removal is
  // rethrown after both have run, so one failure cannot skip the rest.
  test.afterEach(async ({ page }) => {
    const failures: unknown[] = [];
    try {
      const removed = await Promise.allSettled(addedStubs.splice(0).map((id) => removeMapping(id)));
      for (const result of removed) if (result.status === 'rejected') failures.push(result.reason);
      await sweepTempStubs().catch((error: unknown) => failures.push(error));
    } finally {
      await removeAcmeProject(page, REPO);
    }
    if (failures.length > 0) throw new AggregateError(failures, 'could not restore the mock');
  });

  /** Signs in, makes the project from the existing repo, and opens its spec editor. Returns the project id. */
  async function openEditor(page: Page): Promise<string> {
    await login(page);
    const orgId = await acmeOrgId(page);
    await removeProjectFor(page, orgId, REPO);
    await createExistingProject(page, orgId, REPO, SPEC_PATH);
    const projectId = projectIdOf(page);
    await page.goto(`/projects/${projectId}/editor`);
    await expect(spec(page)).toBeVisible();
    return projectId;
  }

  /** The editor (CodeMirror), named by its aria-label. */
  function spec(page: Page) {
    return page.getByRole('textbox', { name: 'OpenAPI spec' });
  }

  /** Puts the cursor at the end of the spec and types `text` there. */
  async function typeAtEnd(page: Page, text: string) {
    await spec(page).click();
    await page.keyboard.press('Control+End');
    await page.keyboard.insertText(text);
  }

  test('load', async ({ page }) => {
    await openEditor(page);
    await expect(spec(page)).toContainText('title: Billing API');
    await expect(spec(page)).toContainText('operationId: listInvoices');
  });

  // Known product bug, tracked here: the fixture is valid, but the Problems panel shows an Error on it, because the
  // browser validator's ajv compile is blocked by the page's CSP ('unsafe-eval' is not allowed). test.fail keeps the suite
  // green while the bug stands, and turns red once the panel shows no problems, so this marker is then removed.
  test.fail('load: a valid spec shows no problems', async ({ page }) => {
    await openEditor(page);
    await expect(page.getByRole('region', { name: 'Problems' }).getByText('No problems found.')).toBeVisible({ timeout: 5000 });
  });

  test('validate', async ({ page }) => {
    await openEditor(page);
    // An unclosed flow sequence is not YAML, so the browser validator and the lint both report it.
    await typeAtEnd(page, 'broken: [\n');

    const problems = page.getByRole('region', { name: 'Problems' });
    const finding = problems.getByRole('listitem').filter({ hasText: 'Flow sequence in block collection' });
    await expect(finding.getByText('Error', { exact: true })).toBeVisible();
    await expect(finding.getByRole('button', { name: /Go to line \d+/ })).toBeVisible();
    // Problems do not block saving: the edit is still unsaved, so Save is on and says so.
    await expect(page.getByRole('status').filter({ hasText: 'Unsaved changes' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();
  });

  test('save with conflict detection', async ({ page, request }) => {
    await openEditor(page);
    await typeAtEnd(page, EDIT);

    // Someone changes the file on GitHub after the editor loaded: the file now has another sha, and the write that
    // names the old sha is refused, as GitHub refuses it.
    addedStubs.push(
      await addMapping({
        priority: 1,
        request: { method: 'GET', urlPath: SPEC_URL },
        response: {
          status: 200,
          jsonBody: {
            type: 'file',
            encoding: 'base64',
            name: 'billing.yaml',
            path: SPEC_PATH,
            sha: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
            content: Buffer.from(ORIGINAL.replace('Billing API', 'Billing API (changed upstream)')).toString('base64'),
            html_url: 'https://github.com/acme/billing-api/blob/main/openapi/billing.yaml',
          },
        },
      }),
    );
    addedStubs.push(
      await addMapping({
        priority: 1,
        // Only the save on the sha the editor loaded is refused, as GitHub refuses a write on a stale sha.
        request: { method: 'PUT', urlPath: SPEC_URL, bodyPatterns: [{ matchesJsonPath: `$[?(@.sha == '${LOADED_SHA}')]` }] },
        response: {
          status: 409,
          jsonBody: {
            message: 'does not match',
            documentation_url: 'https://docs.github.com/rest/repos/contents#create-or-update-file-contents',
            status: '409',
          },
        },
      }),
    );

    await resetJournal(request);
    await page.getByRole('button', { name: 'Save', exact: true }).click();

    const dialog = page.getByRole('dialog', { name: 'Someone else changed this file.' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Reload', exact: true })).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Compare', exact: true })).toBeVisible();
    // Compare shows the server's version, which is the changed upstream file the stub served.
    await dialog.getByRole('button', { name: 'Compare', exact: true }).click();
    await expect(dialog.getByRole('textbox', { name: 'Saved version (read-only)' })).toContainText('(changed upstream)');

    // The save was sent once, with the sha the editor loaded, and it was refused: nothing was written.
    const write = onlyRequest(await requests({ method: 'PUT', urlPath: SPEC_URL }));
    expect(bodyOf<{ sha: string }>(write).sha, 'the save names the loaded sha').toBe(LOADED_SHA);
    expect(write.status, 'the refused save was not accepted').toBe(409);
    // The user's edit is still in the editor, not replaced by the server's text. The open dialog hides the editor from
    // the accessibility tree, so it is found with includeHidden.
    await expect(page.getByRole('textbox', { name: 'OpenAPI spec', includeHidden: true })).toContainText('# parity edit');
  });

  test('save & generate', async ({ page, request }) => {
    const projectId = await openEditor(page);
    await typeAtEnd(page, EDIT);

    await resetJournal(request);
    await page.getByRole('button', { name: 'Save & generate', exact: true }).click();
    await expect(page.getByText('Saved and generation started')).toBeVisible();

    // One write to the spec file, on the default branch, of the edited text.
    const write = bodyOf<{ branch: string; content: string; sha: string }>(
      onlyRequest(await requests({ method: 'PUT', urlPath: SPEC_URL })),
    );
    expect(write.branch).toBe('main');
    expect(write.sha).toBe(LOADED_SHA);
    expect(Buffer.from(write.content, 'base64').toString('utf8')).toBe(EDITED);

    // The run was created for the project. The worker is not running in the parity stack, so it stays queued.
    const res = await page.request.get(`/api/v1/projects/${projectId}/runs`);
    expect(res.ok(), `GET runs returned ${res.status()}`).toBeTruthy();
    const { items } = (await res.json()) as { items: { status: string; dryRun: boolean }[] };
    expect(items.map((run) => run.status)).toEqual(['QUEUED']);
    expect(items[0]?.dryRun).toBe(false);
  });
});
