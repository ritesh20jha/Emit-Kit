import { randomBytes } from 'node:crypto';
import type { APIRequestContext } from '@playwright/test';
import { deliver, type DeliveryResult } from './pipeline';

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

/** Sends one signed pull_request delivery (see deliver in pipeline.ts, which signs it as GitHub does). */
export function deliverPullRequest(
  request: APIRequestContext,
  secret: string,
  payload: Record<string, unknown>,
  deliveryId?: string,
): Promise<DeliveryResult & { deliveryId: string }> {
  return deliver(request, secret, 'pull_request', payload, deliveryId);
}
