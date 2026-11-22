/**
 * v1 features 32 (breaking-change gate), 35 (status lifecycle), 38 (rerun, cancel, retry) and 39 (dry run). Sections 5.1,
 * 8 and 10.6 of the v1 design.
 *
 * Each describe block shares one project and its runs (beforeAll), so a run that takes minutes is paid for once per block.
 * The describe blocks need the pipeline (E2E_PIPELINE=1, a stack started with E2E_SANDBOX_SOCKET). With E2E_REQUIRED=1 they
 * fail instead of skipping.
 */
import { expect, test } from '@playwright/test';
import { cancelRunApi, runFacts, saveConfig, startRun } from './support/api';
import { MOCK_ADMIN, requests, resetJournal } from './support/mockJournal';
import { addMapping, sweepTempStubs } from './support/mockStubs';
import { requirePipeline, waitForRun } from './support/pipeline';
import { runIdOf } from './support/projects';
import { CREATE_ONLY, ONE_OPERATION, SPEC_PATH, TWO_OPERATIONS, addSpecStub } from './support/specFixtures';
import { closeSharedProject, openSharedProject, type SharedProject } from './support/sharedProject';

const REPO = 'acme/billing-api';
// A run takes about 2.5 minutes in the sandbox, so each wait gets room for one run plus a retry, and never sleeps.
const RUN_TIMEOUT = 6 * 60_000;
const BLOCK_TIMEOUT = 15 * 60_000;

/** Clears the WireMock scenario states, so a scenario stub added later starts from its first state. */
async function resetScenarios(): Promise<void> {
  const res = await fetch(`${MOCK_ADMIN}/scenarios/reset`, { method: 'POST' });
  if (!res.ok) throw new Error(`POST /__admin/scenarios/reset returned ${res.status}`);
}

test.describe('32 breaking-change gate (REQUIRE_APPROVAL path)', () => {
  test.describe.configure({ mode: 'serial', timeout: BLOCK_TIMEOUT });

  let shared: SharedProject | undefined;

  test.beforeAll(async ({ browser }) => {
    requirePipeline();
    if (process.env.E2E_PIPELINE !== '1') return;
    shared = await openSharedProject(browser, REPO, SPEC_PATH);
    // The gate policy is a config field: a breaking change now waits for an admin instead of going ahead.
    await saveConfig(shared.page, shared.projectId, { breakingGate: 'REQUIRE_APPROVAL' });
    // The baseline: a SUCCESS run of the two-operation spec, which the next runs diff against.
    await addSpecStub(TWO_OPERATIONS);
    try {
      const runId = await startRun(shared.page, shared.projectId, false);
      await waitForRun(shared.page.request, shared.projectId, 'SUCCESS', { runId, timeout: RUN_TIMEOUT });
    } finally {
      await sweepTempStubs();
    }
  });

  test.afterEach(async () => {
    await sweepTempStubs();
  });

  test.afterAll(async () => {
    await sweepTempStubs();
    await closeSharedProject(shared);
  });

  test.beforeEach(() => requirePipeline());

  test('32 breaking-change gate (REQUIRE_APPROVAL path): a breaking run waits for approval, and approving it resumes the run', async () => {
    if (!shared) throw new Error('the shared project was not created');
    const { page, projectId } = shared;
    await addSpecStub(ONE_OPERATION);
    const runId = await startRun(page, projectId, false);
    await waitForRun(page.request, projectId, 'AWAITING_APPROVAL', { runId, timeout: RUN_TIMEOUT });

    // The run's Changes tab lists the same removed operation as the approval banner does.
    await page.goto(`/runs/${runId}/changes`);
    const changes = page.getByRole('list', { name: 'Changes' });
    await expect(changes.getByText('Removed operation', { exact: true }), 'the Changes tab labels the removal').toBeVisible();
    await expect(changes.getByText('GET /invoices/{id}', { exact: true }), 'the Changes tab names the removed operation').toBeVisible();

    await page.goto(`/runs/${runId}`);
    const banner = page.getByRole('region', { name: 'Approval needed' });
    await expect(banner.getByText('GET /invoices/{id}', { exact: true }), 'the banner names the removed operation').toBeVisible();
    await banner.getByLabel('Comment (optional)').fill('approved in the e2e run');
    await banner.getByRole('button', { name: 'Approve', exact: true }).click();

    // The approval re-queues the run, which resumes at generation and ends SUCCESS.
    await waitForRun(page.request, projectId, 'SUCCESS', { runId, timeout: RUN_TIMEOUT });
    const facts = await runFacts(page, runId);
    expect(facts.approval?.decision, 'the approval is recorded as approved').toBe('APPROVED');
    expect(facts.approval?.comment, 'the reviewer comment is recorded').toBe('approved in the e2e run');
  });

  test('35 status lifecycle (awaiting approval, cancelled on reject): a rejected breaking run ends CANCELLED', async () => {
    if (!shared) throw new Error('the shared project was not created');
    const { page, projectId } = shared;
    await resetJournal(page.request);
    // The last SUCCESS run read ONE_OPERATION, so CREATE_ONLY removes listInvoices: another breaking change.
    await addSpecStub(CREATE_ONLY);
    const runId = await startRun(page, projectId, false);
    await waitForRun(page.request, projectId, 'AWAITING_APPROVAL', { runId, timeout: RUN_TIMEOUT });

    await page.goto(`/runs/${runId}`);
    const banner = page.getByRole('region', { name: 'Approval needed' });
    await banner.getByLabel('Comment (optional)').fill('rejected in the e2e run');
    await banner.getByRole('button', { name: 'Reject', exact: true }).click();

    await waitForRun(page.request, projectId, 'CANCELLED', { runId, timeout: RUN_TIMEOUT });
    const facts = await runFacts(page, runId);
    expect(facts.approval?.decision, 'the approval is recorded as rejected').toBe('REJECTED');
    await expect(page.getByText('Cancelled', { exact: true }).first(), 'the run page shows Cancelled').toBeVisible();
    // A rejected run never reaches the commit step, so GitHub gets no pull request.
    expect((await requests({ method: 'POST', urlPath: `/repos/acme/billing-api/pulls` })).length, 'no pull request was opened').toBe(0);
  });
});

test.describe('38 rerun, cancel and retry', () => {
  test.describe.configure({ mode: 'serial', timeout: BLOCK_TIMEOUT });

  let shared: SharedProject | undefined;

  test.beforeAll(async ({ browser }) => {
    requirePipeline();
    if (process.env.E2E_PIPELINE !== '1') return;
    shared = await openSharedProject(browser, REPO, SPEC_PATH);
  });

  test.afterEach(async () => {
    await sweepTempStubs();
    await resetScenarios();
  });

  test.afterAll(async () => {
    await sweepTempStubs();
    await resetScenarios();
    await closeSharedProject(shared);
  });

  test.beforeEach(() => requirePipeline());

  test('38 rerun, cancel and retry: a run can be cancelled, and its rerun is a new run that links back to it', async () => {
    if (!shared) throw new Error('the shared project was not created');
    const { page, projectId } = shared;
    const cancelledId = await startRun(page, projectId, false);

    await page.goto(`/runs/${cancelledId}`);
    await page.getByRole('button', { name: 'Cancel run', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Cancel this run?' });
    await dialog.getByRole('button', { name: 'Yes, cancel run', exact: true }).click();
    await waitForRun(page.request, projectId, 'CANCELLED', { runId: cancelledId, timeout: RUN_TIMEOUT });
    await expect(page.getByText('Cancelled', { exact: true }).first(), 'the run page shows Cancelled').toBeVisible();

    // A finished run can be rerun: the page is reloaded so the actions reflect the finished status.
    await page.reload();
    // Rerun: the button starts a new run and opens it.
    await page.getByRole('button', { name: 'Rerun', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/runs/(?!${cancelledId}$)[^/]+$`));
    const rerunId = runIdOf(page);
    const rerun = await runFacts(page, rerunId);
    expect(rerun.parentRunId, 'the rerun links back to the run it repeats').toBe(cancelledId);
    expect(rerun.trigger, 'the rerun is recorded as a rerun').toBe('RERUN');
    expect(rerun.dryRun, 'the rerun keeps the dry-run setting of its source').toBe(false);

    // The rerun is stopped straight away: the check is the link, not a second full run.
    await cancelRunApi(page, rerunId);
    await waitForRun(page.request, projectId, 'CANCELLED', { runId: rerunId, timeout: RUN_TIMEOUT });
  });

  test('38 rerun, cancel and retry: a transient GitHub server error is retried, and the run succeeds', async () => {
    if (!shared) throw new Error('the shared project was not created');
    const { page, projectId } = shared;
    // The first tree write answers 503 (a transient VCS server error, which the worker retries after 10 s). Later
    // writes fall through to the normal mapping (the scenario moves to Recovered).
    await resetScenarios();
    await addMapping({
      scenarioName: 'git-tree-503-once',
      requiredScenarioState: 'Started',
      newScenarioState: 'Recovered',
      priority: 1,
      request: { method: 'POST', urlPath: '/repos/acme/billing-api/git/trees' },
      response: { status: 503, headers: { 'Content-Type': 'application/json' }, body: '{"message":"Service Unavailable"}' },
    });
    await resetJournal(page.request);
    const runId = await startRun(page, projectId, false);
    await waitForRun(page.request, projectId, 'SUCCESS', { runId, timeout: RUN_TIMEOUT });

    const statuses = (await requests({ method: 'POST', urlPath: '/repos/acme/billing-api/git/trees' })).map((entry) => entry.status);
    expect(statuses, 'the tree write was refused once, then accepted on the retry').toEqual(expect.arrayContaining([503, 201]));
  });
});

test.describe('39 dry run', () => {
  test.describe.configure({ mode: 'serial', timeout: BLOCK_TIMEOUT });

  let shared: SharedProject | undefined;

  test.beforeAll(async ({ browser }) => {
    requirePipeline();
    if (process.env.E2E_PIPELINE !== '1') return;
    shared = await openSharedProject(browser, REPO, SPEC_PATH);
  });

  test.afterAll(async () => {
    await closeSharedProject(shared);
  });

  test.beforeEach(() => requirePipeline());

  test('39 dry run: generates and checks the output, and commits nothing', async () => {
    if (!shared) throw new Error('the shared project was not created');
    const { page, projectId } = shared;
    await resetJournal(page.request);

    await page.goto(`/projects/${projectId}/runs`);
    await page.getByRole('button', { name: 'Dry run', exact: true }).click();
    await expect(page).toHaveURL(/\/runs\/[^/]+$/);
    const runId = runIdOf(page);
    await waitForRun(page.request, projectId, 'SUCCESS', { runId, timeout: RUN_TIMEOUT });

    const facts = await runFacts(page, runId);
    expect(facts.dryRun, 'the run is a dry run').toBe(true);
    expect(facts.prUrl, 'a dry run opens no pull request').toBeNull();

    // Nothing was written to GitHub: no branch, tree, commit or pull request.
    const writes = await Promise.all(
      [
        { method: 'POST', urlPath: '/repos/acme/billing-api/git/refs' },
        { method: 'POST', urlPath: '/repos/acme/billing-api/git/trees' },
        { method: 'POST', urlPath: '/repos/acme/billing-api/git/commits' },
        { method: 'POST', urlPath: '/repos/acme/billing-api/pulls' },
      ].map(async (match) => (await requests(match)).length),
    );
    expect(writes.reduce((sum, count) => sum + count, 0), 'a dry run writes nothing to GitHub').toBe(0);

    // The output was still generated and listed, and no version was published.
    const files = await page.request.get(`/api/v1/runs/${runId}/files`);
    expect(((await files.json()) as unknown[]).length, 'the dry run lists its generated files').toBeGreaterThan(0);
    const versions = await page.request.get(`/api/v1/projects/${projectId}/versions`);
    expect(((await versions.json()) as unknown[]).length, 'a dry run publishes no version').toBe(0);
  });
});
