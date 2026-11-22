import { expect, type Page } from '@playwright/test';

/** The XSRF header the API wants on a mutation, taken from the cookie the signed-in browser holds. */
export async function xsrfHeaders(page: Page): Promise<Record<string, string>> {
  const cookie = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN');
  if (!cookie) throw new Error('no XSRF-TOKEN cookie: sign in before a mutation');
  return { 'X-XSRF-TOKEN': cookie.value };
}

/**
 * Saves the project's config as a new version: reads the current one, changes the fields in `patch`, and sends the rest
 * back unchanged. `aiApiKey` is sent only when given; the API keeps the stored key when the field is absent.
 */
export async function saveConfig(page: Page, projectId: string, patch: Record<string, unknown>, aiApiKey?: string): Promise<void> {
  const current = await page.request.get(`/api/v1/projects/${projectId}/config`);
  expect(current.ok(), `GET config returned ${current.status()}`).toBeTruthy();
  const config = (await current.json()) as Record<string, unknown>;
  const body = {
    outputs: config.outputs,
    sdkLanguages: config.sdkLanguages,
    outputDir: config.outputDir,
    npmScope: config.npmScope,
    pypiName: config.pypiName,
    mavenGroupId: config.mavenGroupId,
    mavenArtifactId: config.mavenArtifactId,
    versionStrategy: config.versionStrategy,
    breakingGate: config.breakingGate,
    lintFailOn: config.lintFailOn,
    aiProvider: config.aiProvider,
    aiModel: config.aiModel,
    ...patch,
    ...(aiApiKey === undefined ? {} : { aiApiKey }),
  };
  const res = await page.request.put(`/api/v1/projects/${projectId}/config`, { data: body, headers: await xsrfHeaders(page) });
  expect(res.ok(), `PUT config returned ${res.status()}`).toBeTruthy();
}

/** Starts a run through the API, as the Trigger run and Dry run buttons do, and returns its id. */
export async function startRun(page: Page, projectId: string, dryRun: boolean): Promise<string> {
  const res = await page.request.post(`/api/v1/projects/${projectId}/runs`, { data: { dryRun }, headers: await xsrfHeaders(page) });
  expect(res.status(), `POST runs returned ${res.status()}`).toBe(201);
  return ((await res.json()) as { id: string }).id;
}

/** The fields of a run the specs assert on, from GET /api/v1/runs/{id}. */
export interface RunFacts {
  id: string;
  status: string;
  trigger: string;
  dryRun: boolean;
  parentRunId: string | null;
  prNumber: number | null;
  failureCode: string | null;
  approval: { decision: string | null; comment: string | null } | null;
}

export async function runFacts(page: Page, runId: string): Promise<RunFacts> {
  const res = await page.request.get(`/api/v1/runs/${runId}`);
  expect(res.ok(), `GET run returned ${res.status()}`).toBeTruthy();
  return (await res.json()) as RunFacts;
}

/** Cancels a run through the API (RUN_CANCEL). */
export async function cancelRunApi(page: Page, runId: string): Promise<void> {
  const res = await page.request.post(`/api/v1/runs/${runId}/cancel`, { headers: await xsrfHeaders(page) });
  expect(res.ok(), `POST cancel returned ${res.status()}`).toBeTruthy();
}
