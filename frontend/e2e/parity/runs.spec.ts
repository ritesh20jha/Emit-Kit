import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { login } from './support/login';
import { bodyOf, onlyRequest, requests, resetJournal } from './support/mockJournal';
import { createExistingProject } from './support/newProject';
import { deliverPush, listRuns, pushEvent, requirePipeline, waitForRun, webhookSecret } from './support/pipeline';
import { acmeOrgId, projectIdOf, removeAcmeProject, removeProjectFor, runIdOf } from './support/projects';

const REPO = 'acme/billing-api';
const SPEC_PATH = 'openapi/billing.yaml';
// A run takes about 2.5 minutes in the sandbox (the toolchain compiles the SDKs), so each pipeline test gets 6 minutes.
// Waits inside a test are expect.poll calls with their own timeouts, never sleeps.
const PIPELINE_TIMEOUT = 6 * 60_000;

/**
 * Signs in and makes the project from the existing repo, so its push webhook is registered on create. Returns the
 * project id. Each test that calls it removes the project again in afterEach.
 */
async function createProject(page: Page, request: APIRequestContext): Promise<string> {
  await login(page);
  const orgId = await acmeOrgId(page);
  await removeProjectFor(page, orgId, REPO);
  await resetJournal(request);
  await createExistingProject(page, orgId, REPO, SPEC_PATH);
  return projectIdOf(page);
}

/** Starts a run from the project's runs page, the way a user does, and returns the run id once the run page opens. */
async function triggerRunFromUi(page: Page, projectId: string): Promise<string> {
  await page.goto(`/projects/${projectId}/runs`);
  await page.getByRole('button', { name: 'Trigger run', exact: true }).click();
  await expect(page).toHaveURL(/\/runs\/[^/]+$/);
  return runIdOf(page);
}

test.describe('Manual run and webhook run (push to the default branch touching the spec)', () => {
  // Each test starts its own run and removes its project afterwards, so the tests do not depend on each other's order.
  test.beforeEach(() => requirePipeline());
  test.afterEach(async ({ page }) => {
    await removeAcmeProject(page, REPO);
  });

  test('manual run', async ({ page, request }) => {
    test.setTimeout(PIPELINE_TIMEOUT);
    const projectId = await createProject(page, request);

    const runId = await triggerRunFromUi(page, projectId);
    const run = await waitForRun(page.request, projectId, 'SUCCESS', { runId });

    expect(run.trigger, 'the run was started by hand').toBe('MANUAL');
    // The pipeline opens the pull request on the mock, titled with the SDK version it generated.
    const pull = onlyRequest(await requests({ method: 'POST', urlPath: '/repos/acme/billing-api/pulls' }));
    expect(pull.status, 'the mock accepted the pull request').toBe(201);
    expect(bodyOf<{ title: string }>(pull).title.startsWith('EmitKit: SDK v'), 'the pull request title starts with "EmitKit: SDK v"').toBe(true);
    expect(run.prNumber, 'the run records its pull request').not.toBeNull();
  });

  test('webhook run', async ({ page, request }) => {
    test.setTimeout(PIPELINE_TIMEOUT);
    const projectId = await createProject(page, request);
    // The secret the API registered with GitHub. It signs each delivery below and is never printed.
    const secret = await webhookSecret(REPO);

    // A push to the default branch that does not touch the spec is acknowledged and queues nothing.
    const unrelated = await deliverPush(request, secret, pushEvent({ repo: REPO, ref: 'refs/heads/main', modified: ['README.md'] }));
    expect(unrelated.httpStatus, 'the signed delivery is accepted').toBe(200);
    expect(unrelated.outcome, 'a push that does not touch the spec is ignored').toEqual({ status: 'ignored', reason: 'spec-not-changed' });
    expect(await listRuns(page.request, projectId), 'no run for a push that does not touch the spec').toHaveLength(0);

    // A push to the default branch that modifies the spec queues one webhook run.
    const push = pushEvent({ repo: REPO, ref: 'refs/heads/main', modified: [SPEC_PATH] });
    const first = await deliverPush(request, secret, push);
    expect(first.httpStatus, 'the signed delivery is accepted').toBe(200);
    expect(first.outcome.status, 'the spec push queues a run').toBe('queued');
    const runId = first.outcome.runId;
    if (!runId) throw new Error('the queued delivery did not name its run');

    // GitHub redelivers the same delivery id: it is answered as a duplicate and queues no second run.
    const redelivery = await deliverPush(request, secret, push, first.deliveryId);
    expect(redelivery.outcome, 'a redelivery of the same delivery id is a duplicate').toEqual({ status: 'duplicate' });
    expect((await listRuns(page.request, projectId)).map((run) => run.id), 'the duplicate created no second run').toEqual([runId]);

    const run = await waitForRun(page.request, projectId, 'SUCCESS', { runId });
    expect(run.trigger, 'the run was started by the webhook').toBe('WEBHOOK');
    const pull = onlyRequest(await requests({ method: 'POST', urlPath: '/repos/acme/billing-api/pulls' }));
    expect(pull.status, 'the mock accepted the pull request').toBe(201);
  });
});

test.describe('Live logs, run list, run detail', () => {
  test.beforeEach(() => requirePipeline());
  test.afterEach(async ({ page }) => {
    await removeAcmeProject(page, REPO);
  });

  // One run serves all three checks, in the order a user sees them: the live log while the run goes, then the list and
  // the detail once it has succeeded.
  test('Live logs, run list, run detail', async ({ page, request }) => {
    // The first-log wait (4 min) and the wait for the run's end (waitForRun, 5 min) add up to more than PIPELINE_TIMEOUT.
    test.setTimeout(10 * 60_000);
    const projectId = await createProject(page, request);

    const runId = await triggerRunFromUi(page, projectId);

    // The log streams in while the run is going: the first step's line appears, and the run is not yet finished.
    const logLines = page.getByRole('table', { name: 'Log lines' });
    await expect(logLines.getByRole('cell', { name: 'Fetch spec', exact: true }).first(), 'the fetch step streams into the live log').toBeVisible({
      timeout: 4 * 60_000,
    });
    const midRun = (await listRuns(page.request, projectId)).find((run) => run.id === runId);
    expect(['QUEUED', 'RUNNING'], 'the run is still going when its first log line shows').toContain(midRun?.status);

    const run = await waitForRun(page.request, projectId, 'SUCCESS', { runId });

    // The run list shows the run with its status and its pull request.
    await page.goto(`/projects/${projectId}/runs`);
    const row = page.getByRole('row').filter({ hasText: 'Succeeded' });
    await expect(row, 'the run list shows the succeeded run').toHaveCount(1);
    await expect(row.getByRole('link', { name: `Pull request #${run.prNumber}` })).toBeVisible();

    // The run detail shows the pipeline's steps, with the first one done, and a link to the pull request.
    await page.goto(`/runs/${runId}`);
    const steps = page.getByRole('list', { name: 'Pipeline steps' });
    await expect(steps.getByRole('listitem'), 'the run detail lists the eleven pipeline steps').toHaveCount(11);
    await expect(steps.getByRole('button', { name: /^Fetch spec/ }), 'the fetch step is done').toContainText('Done');
    await expect(page.getByRole('link', { name: 'View pull request' })).toHaveAttribute('href', run.prUrl ?? '');
  });
});
