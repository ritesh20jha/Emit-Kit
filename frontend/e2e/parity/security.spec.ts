/**
 * Security, and checklist line 70.
 *
 * - "Secrets encrypted at rest. CORS allowlist enforced": the stored GitHub token, the webhook secret and the AI key are
 *   read from the database as booleans only (Vault Transit ciphertext, and no plaintext), and the API is asked with a
 *   foreign and an allowlisted Origin.
 * - "70 VcsProvider abstraction" is not testable at UI level: VcsProvider is a Java interface behind GitHub calls, with no
 *   screen and no endpoint of its own. It is covered by the backend contract tests, so the entry is skipped here with that
 *   reason, and the checklist line can be ticked from the report.
 *
 * The e2e overlay sets no emitkit.cors.allowed-origins, so the allowlisted origin is refused until the overlay sets it
 * (see the task report). That is the failure this test is meant to show, not a reason to relax the assertion.
 */
import { expect, test } from '@playwright/test';
import { queryRows, sqlString, ulid } from './support/db';
import { saveConfig } from './support/api';
import { login } from './support/login';
import { createExistingProject } from './support/newProject';
import { acmeOrgId, projectIdOf, removeAcmeProject, removeProjectFor } from './support/projects';
import { BASE_URL } from './support/session';
import { webhookSecret } from './support/pipeline';

// The AI key is typed into a request body, which a Playwright trace would record. Traces are off for this file.
test.use({ trace: 'off' });

const REPO = 'acme/billing-api';
const SPEC_PATH = 'openapi/billing.yaml';
// Dummy values. The AI key is saved for the test and must never be stored in the clear.
const AI_KEY = 'test-ai-key-123';
// The fixture's OAuth token (deploy/compose/e2e/wiremock/mappings/oauth.json). Stored tokens must not contain it.
const PLAIN_TOKEN = 'gho_mock_token';
const FOREIGN_ORIGIN = 'https://evil.example';

test.afterEach(async ({ page }) => {
  await removeAcmeProject(page, REPO);
});

test.describe('Secrets and CORS', () => {
  test('Secrets encrypted at rest. CORS allowlist enforced', async ({ page }) => {
    await login(page);
    const orgId = await acmeOrgId(page);
    await removeProjectFor(page, orgId, REPO);
    await createExistingProject(page, orgId, REPO, SPEC_PATH);
    const projectId = ulid(projectIdOf(page));
    // The webhook secret was sent to GitHub on create; the journal holds it raw, in memory only.
    const hookSecret = await webhookSecret(REPO);
    await saveConfig(page, projectId, {}, AI_KEY);

    // At rest: each secret column holds Vault Transit ciphertext ("vault:v<n>:...") and never the plaintext. Counts only.
    const [tokenRows, tokenEncrypted, tokenPlain] = queryRows(
      `SELECT count(*), count(*) FILTER (WHERE access_token_enc LIKE 'vault:v%'), count(*) FILTER (WHERE position(${sqlString(PLAIN_TOKEN)} IN access_token_enc) > 0) FROM oauth_accounts;`,
    )[0] ?? [];
    expect(Number(tokenRows) > 0, 'the GitHub token is stored for the signed-in user').toBe(true);
    expect(tokenEncrypted, 'every stored GitHub token is Vault ciphertext').toBe(tokenRows);
    expect(tokenPlain, 'no stored GitHub token holds the plaintext token').toBe('0');

    const [hookRows, hookEncrypted, hookPlain] = queryRows(
      `SELECT count(*), count(*) FILTER (WHERE webhook_secret_enc LIKE 'vault:v%'), count(*) FILTER (WHERE position(${sqlString(hookSecret)} IN webhook_secret_enc) > 0) FROM projects WHERE id = '${projectId}';`,
    )[0] ?? [];
    expect(hookRows, 'the project has a stored webhook secret').toBe('1');
    expect(hookEncrypted, 'the webhook secret is Vault ciphertext').toBe('1');
    expect(hookPlain, 'the webhook secret is not stored in the clear').toBe('0');

    const [keyRows, keyEncrypted, keyPlain] = queryRows(
      `SELECT count(*), count(*) FILTER (WHERE ai_api_key_enc LIKE 'vault:v%'), count(*) FILTER (WHERE position(${sqlString(AI_KEY)} IN ai_api_key_enc) > 0) FROM project_configs WHERE project_id = '${projectId}' AND ai_api_key_enc IS NOT NULL;`,
    )[0] ?? [];
    expect(Number(keyRows) > 0, 'the AI key is stored for the project').toBe(true);
    expect(keyEncrypted, 'the AI key is Vault ciphertext').toBe(keyRows);
    expect(keyPlain, 'the AI key is not stored in the clear').toBe('0');

    // CORS: a foreign origin is refused before authentication (Spring answers 403 to a disallowed Origin).
    const foreign = await page.request.get('/api/v1/me', { headers: { Origin: FOREIGN_ORIGIN } });
    expect(foreign.status(), 'a foreign origin is refused').toBe(403);
    expect(foreign.headers()['access-control-allow-origin'], 'a foreign origin gets no allow-origin header').toBeUndefined();

    const foreignPreflight = await page.request.fetch(`/api/v1/projects/${projectId}/config`, {
      method: 'OPTIONS',
      headers: { Origin: FOREIGN_ORIGIN, 'Access-Control-Request-Method': 'PUT' },
    });
    expect(foreignPreflight.status(), 'a foreign preflight is refused').toBe(403);

    // The allowlisted origin (this stack's own address) is answered with credentials allowed. Needs the overlay's allowlist.
    const allowed = BASE_URL.replace(/\/$/, '');
    const own = await page.request.get('/api/v1/me', { headers: { Origin: allowed } });
    expect(own.status(), 'the allowlisted origin is served').toBe(200);
    expect(own.headers()['access-control-allow-origin'], 'the allowlisted origin is echoed back').toBe(allowed);
    expect(own.headers()['access-control-allow-credentials'], 'credentials are allowed for it').toBe('true');
  });
});

// 70 is a backend seam (VcsProvider, with GitHubVcsProvider behind it). No screen or endpoint reaches it directly.
test.describe('70 VcsProvider abstraction', () => {
  test('70 VcsProvider abstraction', () => {
    test.skip(true, 'VcsProvider is an internal abstraction with no UI or endpoint of its own; covered by backend contract tests');
  });
});
