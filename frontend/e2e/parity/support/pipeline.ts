import { createHmac, randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext } from '@playwright/test';
import { MOCK_ADMIN } from './mockJournal';

/** Run statuses after which a run does not change again (the domain's RunStatus.isTerminal). */
const TERMINAL = new Set(['SUCCESS', 'FAILED', 'CANCELLED']);

/** The run fields the specs read from GET /api/v1/projects/{id}/runs. */
export interface RunSummary {
  id: string;
  status: string;
  trigger: string;
  dryRun: boolean;
  prNumber: number | null;
  prUrl: string | null;
}

/**
 * Skips the calling test unless the pipeline is on (E2E_PIPELINE=1, with the stack started with E2E_SANDBOX_SOCKET set).
 * With E2E_REQUIRED=1 the test fails instead, so a CI run that was meant to exercise the pipeline cannot pass by skipping.
 * Call it from a beforeEach hook or the top of a test.
 */
export function requirePipeline(): void {
  if (process.env.E2E_PIPELINE === '1') return;
  if (process.env.E2E_REQUIRED === '1') {
    throw new Error('E2E_REQUIRED=1 but E2E_PIPELINE is not 1: start the stack with E2E_SANDBOX_SOCKET and run with E2E_PIPELINE=1');
  }
  test.skip(true, 'pipeline specs need E2E_PIPELINE=1 and a stack started with E2E_SANDBOX_SOCKET');
}

/** The project's runs, newest first, as the runs API lists them. */
export async function listRuns(request: APIRequestContext, projectId: string): Promise<RunSummary[]> {
  const res = await request.get(`/api/v1/projects/${projectId}/runs`, { params: { limit: 50 } });
  expect(res.ok(), `GET runs returned ${res.status()}`).toBeTruthy();
  const { items } = (await res.json()) as { items: RunSummary[] };
  return items;
}

/**
 * Waits until the run (the one with `runId`, else the newest) reaches `status` or any terminal status, and returns it.
 * A run that ends in another terminal status fails at once, with its failure code, instead of waiting out the timeout.
 */
export async function waitForRun(
  request: APIRequestContext,
  projectId: string,
  status: string,
  options: { runId?: string; timeout?: number } = {},
): Promise<RunSummary> {
  let latest: RunSummary | undefined;
  await expect
    .poll(
      async () => {
        const runs = await listRuns(request, projectId);
        latest = options.runId ? runs.find((run) => run.id === options.runId) : runs[0];
        if (!latest) return 'waiting';
        return latest.status === status || TERMINAL.has(latest.status) ? latest.status : 'waiting';
      },
      { timeout: options.timeout ?? 5 * 60_000, intervals: [2_000, 5_000] },
    )
    .not.toBe('waiting');
  const run = latest;
  if (!run) throw new Error('the run never appeared in the project runs list');
  if (run.status !== status) {
    const detail = (await (await request.get(`/api/v1/runs/${run.id}`)).json()) as { failureCode?: string | null };
    throw new Error(`run ${run.id} ended ${run.status} (failure code: ${detail.failureCode ?? 'none'}), expected ${status}`);
  }
  return run;
}

/**
 * The raw webhook secret the API sent when it registered the project's push webhook for `repo` (the last such POST in
 * the journal). The journal's own view redacts it, so this reads the raw journal, in memory, and returns the value only
 * to the caller that signs a delivery with it. It is never logged, and no message here includes it.
 */
export async function webhookSecret(repo: string): Promise<string> {
  const res = await fetch(`${MOCK_ADMIN}/requests`);
  if (!res.ok) throw new Error(`GET /__admin/requests returned ${res.status}`);
  const { requests: journal } = (await res.json()) as { requests: { request: { method: string; url: string; body?: string } }[] };
  const registrations = journal.filter((entry) => entry.request.method === 'POST' && entry.request.url.split('?')[0] === `/repos/${repo}/hooks`);
  const body = registrations.at(-1)?.request.body;
  const secret = body === undefined ? undefined : (JSON.parse(body) as { config?: { secret?: unknown } }).config?.secret;
  if (typeof secret !== 'string' || secret.length === 0) throw new Error(`no webhook registration with a secret for ${repo} in the journal`);
  return secret;
}

/** A push event for `repo` to `ref`, where each file in `modified` is changed by one commit. Shaped as GitHub sends it. */
export function pushEvent(options: { repo: string; ref: string; modified: string[] }): Record<string, unknown> {
  const head = randomBytes(20).toString('hex');
  return {
    ref: options.ref,
    before: randomBytes(20).toString('hex'),
    after: head,
    repository: { full_name: options.repo, name: options.repo.split('/')[1] },
    commits: [{ id: head, message: 'e2e push', added: [], modified: options.modified, removed: [] }],
  };
}

/** The response of one delivery: the HTTP status and the outcome the API answered with (status, reason, runId). */
export interface DeliveryResult {
  httpStatus: number;
  outcome: { status: string; reason?: string; runId?: string };
}

/**
 * Sends one signed GitHub webhook delivery to /webhooks/github: X-Hub-Signature-256 is the HMAC-SHA256 of the exact body
 * bytes, keyed with the project's webhook secret. The delivery id is unique unless one is given (a redelivery reuses the id
 * of the delivery it repeats).
 */
export async function deliver(
  request: APIRequestContext,
  secret: string,
  event: 'push' | 'pull_request',
  payload: Record<string, unknown>,
  deliveryId: string = randomUUID(),
): Promise<DeliveryResult & { deliveryId: string }> {
  const raw = JSON.stringify(payload);
  const signature = `sha256=${createHmac('sha256', secret).update(raw).digest('hex')}`;
  const res = await request.post('/webhooks/github', {
    data: raw,
    headers: {
      'Content-Type': 'application/json',
      'X-GitHub-Event': event,
      'X-GitHub-Delivery': deliveryId,
      'X-Hub-Signature-256': signature,
    },
  });
  const outcome = res.ok() ? ((await res.json()) as DeliveryResult['outcome']) : { status: `http ${res.status()}` };
  return { httpStatus: res.status(), outcome, deliveryId };
}

/** Sends one GitHub push delivery (see deliver). */
export function deliverPush(
  request: APIRequestContext,
  secret: string,
  payload: Record<string, unknown>,
  deliveryId?: string,
): Promise<DeliveryResult & { deliveryId: string }> {
  return deliver(request, secret, 'push', payload, deliveryId);
}
