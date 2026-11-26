/**
 * v1 features 24 (linting) and 25 (spec diff viewer), sections 5.3, 5.4 and 10.4 of the v1 design.
 *
 * The lint tests that do not need a run (the endpoint, and the editor's Problems panel) run on every stack. The run's
 * Lint tab and the diff viewer need two saved snapshots, so they need the pipeline (E2E_PIPELINE=1) and share one project
 * and its two runs.
 */
import { expect, test } from '@playwright/test';
import { startRun, xsrfHeaders } from './support/api';
import { login } from './support/login';
import { createExistingProject } from './support/newProject';
import { acmeOrgId, projectIdOf, removeAcmeProject, removeProjectFor } from './support/projects';
import { requirePipeline, waitForRun } from './support/pipeline';
import { sweepTempStubs } from './support/mockStubs';
import { LINT_ERRORS, ONE_OPERATION, SPEC_PATH, TWO_OPERATIONS, addSpecStub } from './support/specFixtures';
import { closeSharedProject, openSharedProject, type SharedProject } from './support/sharedProject';

const REPO = 'acme/billing-api';
const PIPELINE_TIMEOUT = 6 * 60_000;

/** One lint finding as POST /api/v1/lint and GET /api/v1/runs/{id}/lint return it. */
interface LintIssue {
  ruleId: string;
  severity: string;
  jsonPath: string;
  message: string;
}

test.describe('24 linting', () => {
  test.afterEach(async ({ page }) => {
    await removeAcmeProject(page, REPO);
  });

  test('24 linting: the lint endpoint reports each finding with its rule and severity', async ({ page }) => {
    await login(page);
    const res = await page.request.post('/api/v1/lint', { data: { content: LINT_ERRORS }, headers: await xsrfHeaders(page) });
    expect(res.ok(), `POST lint returned ${res.status()}`).toBeTruthy();
    const issues = (await res.json()) as LintIssue[];
    expect(issues, 'an undeclared path parameter is an error').toEqual(
      expect.arrayContaining([expect.objectContaining({ ruleId: 'path-params-defined', severity: 'ERROR' })]),
    );
    expect(issues, 'a duplicate operationId is an error').toEqual(
      expect.arrayContaining([expect.objectContaining({ ruleId: 'operation-operationId-unique', severity: 'ERROR' })]),
    );
  });

  test('24 linting: the editor Problems panel shows the findings for the spec', async ({ page }) => {
    await login(page);
    const orgId = await acmeOrgId(page);
    await removeProjectFor(page, orgId, REPO);
    await createExistingProject(page, orgId, REPO, SPEC_PATH);
    await page.goto(`/projects/${projectIdOf(page)}/editor`);

    // The fixture has no tags and no summary or description, so the linter reports two warnings on its one operation. The
    // panel shows each with its own badge, so the badge is found within the item for the tags warning.
    const problems = page.getByRole('region', { name: 'Problems' });
    const tagsItem = problems.getByRole('listitem').filter({ hasText: 'operation has no tags' });
    await expect(tagsItem.getByText('operation has no tags', { exact: true }), 'the warning is listed').toBeVisible();
    await expect(tagsItem.getByText('Warning', { exact: true }), 'the warning is badged as a warning').toBeVisible();
  });
});

test.describe('24 linting and 25 spec diff viewer (pipeline)', () => {
  test.describe.configure({ mode: 'serial', timeout: 12 * 60_000 });

  let shared: SharedProject | undefined;
  let baselineRunId = '';
  let changedRunId = '';

  test.beforeAll(async ({ browser }) => {
    requirePipeline();
    if (process.env.E2E_PIPELINE !== '1') return;
    shared = await openSharedProject(browser, REPO, SPEC_PATH);
    const { page, projectId } = shared;
    // Two saved versions: the first run reads TWO_OPERATIONS, the second reads ONE_OPERATION (getInvoice removed).
    await addSpecStub(TWO_OPERATIONS);
    try {
      baselineRunId = await startRun(page, projectId, false);
      await waitForRun(page.request, projectId, 'SUCCESS', { runId: baselineRunId, timeout: PIPELINE_TIMEOUT });
    } finally {
      await sweepTempStubs();
    }
    await addSpecStub(ONE_OPERATION);
    try {
      changedRunId = await startRun(page, projectId, false);
      await waitForRun(page.request, projectId, 'SUCCESS', { runId: changedRunId, timeout: PIPELINE_TIMEOUT });
    } finally {
      await sweepTempStubs();
    }
  });

  test.afterAll(async () => {
    await sweepTempStubs();
    await closeSharedProject(shared);
  });

  test.beforeEach(() => requirePipeline());

  test('24 linting: the run lint endpoint and the run Lint tab list the spec findings', async () => {
    if (!shared) throw new Error('the shared project was not created');
    const { page } = shared;
    const res = await page.request.get(`/api/v1/runs/${changedRunId}/lint`);
    expect(res.ok(), `GET run lint returned ${res.status()}`).toBeTruthy();
    const issues = (await res.json()) as LintIssue[];
    expect(issues, 'the run recorded the operation-tags warning').toEqual(
      expect.arrayContaining([expect.objectContaining({ ruleId: 'operation-tags', severity: 'WARNING' })]),
    );

    await page.goto(`/runs/${changedRunId}/lint`);
    const table = page.getByRole('table', { name: 'Lint issues' });
    await expect(table.getByText('operation has no tags', { exact: true }), 'the Lint tab lists the finding').toBeVisible();
  });

  test('25 spec diff viewer: the diff of two saved versions lists the removed operation', async () => {
    if (!shared) throw new Error('the shared project was not created');
    const { page, projectId } = shared;
    const snapshotsRes = await page.request.get(`/api/v1/projects/${projectId}/spec/snapshots`);
    expect(snapshotsRes.ok(), `GET snapshots returned ${snapshotsRes.status()}`).toBeTruthy();
    const snapshots = (await snapshotsRes.json()) as { id: string }[];
    expect(snapshots.length, 'each run saved a snapshot').toBeGreaterThanOrEqual(2);
    // Newest first: the changed spec is the newer version, so the older one is the "from" side.
    const [newer, older] = snapshots;
    if (!newer || !older) throw new Error('expected two snapshots');

    await page.goto(`/projects/${projectId}/diff?from=${older.id}&to=${newer.id}`);
    const changes = page.getByRole('region', { name: 'Changes' });
    await expect(changes.getByRole('heading', { name: 'Removed', exact: true }), 'the removed operations are listed').toBeVisible();
    await expect(changes.getByText('GET /invoices/{id}', { exact: true }), 'the removed operation is named').toBeVisible();
  });
});
