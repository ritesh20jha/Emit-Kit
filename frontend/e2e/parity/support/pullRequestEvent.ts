import { createHmac, randomBytes, randomUUID } from 'node:crypto';
import type { APIRequestContext } from '@playwright/test';
import type { DeliveryResult } from './pipeline';

/** A pull_request event for a merged pull request, shaped as GitHub sends it (action closed, merged true). */
export function mergedPullRequestEvent(options: {
  repo: string;
  number: number;
  headRef: string;
  mergeCommitSha: string;
}): Record<string, unknown> {
  return {
    action: 'closed',
    number: options.number,
    pull_request: {
      number: options.number,
      state: 'closed',
      merged: true,
      merge_commit_sha: options.mergeCommitSha,
      head: { ref: options.headRef, sha: randomBytes(20).toString('hex') },
      base: { ref: 'main' },
      html_url: `https://github.com/${options.repo}/pull/${options.number}`,
    },
    repository: { full_name: options.repo, name: options.repo.split('/')[1] },
  };
}

/**
 * Sends one signed pull_request delivery to /webhooks/github. The signature is GitHub's X-Hub-Signature-256: the HMAC-SHA256
 * of the exact body bytes, keyed with the project's webhook secret, as deliverPush in pipeline.ts signs a push. pipeline.ts
 * only signs push events and does not export its signer, so the one-line HMAC is repeated here rather than editing that module.
 * The delivery id is unique unless one is given.
 */
export async function deliverPullRequest(
  request: APIRequestContext,
  secret: string,
  payload: Record<string, unknown>,
  deliveryId: string = randomUUID(),
): Promise<DeliveryResult & { deliveryId: string }> {
  const raw = JSON.stringify(payload);
  const signature = `sha256=${createHmac('sha256', secret).update(raw).digest('hex')}`;
  const res = await request.post('/webhooks/github', {
    data: raw,
    headers: {
      'Content-Type': 'application/json',
      'X-GitHub-Event': 'pull_request',
      'X-GitHub-Delivery': deliveryId,
      'X-Hub-Signature-256': signature,
    },
  });
  const outcome = res.ok() ? ((await res.json()) as DeliveryResult['outcome']) : { status: `http ${res.status()}` };
  return { httpStatus: res.status(), outcome, deliveryId };
}
