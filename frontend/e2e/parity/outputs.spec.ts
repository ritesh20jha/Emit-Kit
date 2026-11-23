import { randomBytes } from 'node:crypto';
import { expect, test, type Browser, type Page } from '@playwright/test';
import { manifestOf, saveConfig, startRun } from './support/api';
import { login } from './support/login';
import { bodyOf, onlyRequest, requests, resetJournal } from './support/mockJournal';
import { createExistingProject } from './support/newProject';
import { addMapping, removeMapping, sweepTempStubs } from './support/mockStubs';
import { acmeOrgId, projectIdOf, removeAcmeProject, removeProjectFor } from './support/projects';
import { newSessionContext } from './support/session';
import { requirePipeline, waitForRun, webhookSecret, type RunSummary } from './support/pipeline';
import { deliverPullRequest, mergedPullRequestEvent } from './support/pullRequestEvent';

// The AI key is sent in a config request, and a Playwright trace would record that request body. Traces are off for this
// file, as in config.spec.ts, so the key is not written to a trace archive on failure.
test.use({ trace: 'off' });

const REPO = 'acme/billing-api';
const OUTPUT_REPO = 'acme/billing-sdk';
const SPEC_PATH = 'openapi/billing.yaml';
const SPEC_URL = `/repos/${REPO}/contents/${SPEC_PATH}`;
// A dummy key. It is typed into the config and must never come back or be printed.
const AI_KEY = 'test-ai-key-123';
const OUT_DIR = '.emitkit/';
// The user-owned stub the TypeScript generator writes (CUSTOM_STUB), at the path a default config produces.
const CUSTOM_PATH = '.emitkit/sdk/typescript/src/custom/index.ts';
const STUB_BLOB_SHA = 'c0ffee00'.repeat(5);
const STUB_TREE_SHA = '9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c';

// A run takes about 2.5 minutes in the sandbox (the toolchain compiles the SDKs). Waits inside a test are expect.poll calls
// with their own timeouts (support/pipeline.ts), never sleeps.
const RUN_WAIT = 6 * 60_000;
const SETUP_TIMEOUT = 10 * 60_000;
const ONE_RUN_TIMEOUT = 8 * 60_000;

// The spec the mock serves (the same fixture as editor.spec.ts), and a later version that adds one operation.
const SPEC_V1 =
  'openapi: 3.0.3\ninfo:\n  title: Billing API\n  version: 1.0.0\npaths:\n  /invoices:\n    get:\n      operationId: listInvoices\n      responses:\n        "200":\n          description: The invoices.\n';
const SPEC_V2 =
  `${SPEC_V1}  "/invoices/{id}":\n    get:\n      operationId: getInvoice\n      parameters:\n        - name: id\n          in: path\n          required: true\n          schema:\n            type: string\n      responses:\n        "200":\n          description: One invoice.\n`;

/** The config body of PUT /api/v1/projects/{id}/config (ConfigRequest). A null aiApiKey keeps the stored key; "" clears it. */
interface OutputConfig {
  outputs: string[];
  sdkLanguages: string[] | null;
  outputDir: string;
  npmScope: string | null;
  pypiName: string | null;
  mavenGroupId: string | null;
  mavenArtifactId: string | null;
  versionStrategy: string;
  breakingGate: string;
  lintFailOn: string;
  aiProvider: string | null;
  aiModel: string | null;
  aiApiKey: string | null;
}

/** Every output: TypeScript and Python SDKs, the CLI, the MCP server and the docs. No AI key. */
const ALL_OUTPUTS: OutputConfig = {
  outputs: ['SDK', 'CLI', 'MCP', 'DOCS'],
  sdkLanguages: ['typescript', 'python'],
  outputDir: OUT_DIR,
  npmScope: 'acme-billing',
  pypiName: 'emitkit-billing',
  mavenGroupId: null,
  mavenArtifactId: null,
  versionStrategy: 'EMITKIT_MANAGED',
  breakingGate: 'ALLOW',
  lintFailOn: 'NEVER',
  aiProvider: 'gemini',
  aiModel: null,
  aiApiKey: null,
};

/** The TypeScript SDK alone, for the runs whose output the test reads in full. */
const SDK_ONLY: OutputConfig = { ...ALL_OUTPUTS, outputs: ['SDK'], sdkLanguages: ['typescript'] };

/** The state the shared run leaves for the tests that read it. */
interface SharedRun {
  projectId: string;
  runId: string;
  prNumber: number;
  version: string;
}

let shared: SharedRun | undefined;

/** Stubs added by a test. afterEach removes them, so a failing test cannot leave the mock altered. */
const addedStubs: string[] = [];

/** The shared run, or a clear failure when the set-up did not run (the pipeline is off). */
function sharedRun(): SharedRun {
  if (!shared) throw new Error('the shared run was not started: run with E2E_PIPELINE=1');
  return shared;
}

/** Runs `body` in its own signed-in browser context, for the hooks, which have no page fixture. The context is closed after. */
async function withSession<T>(browser: Browser, body: (page: Page) => Promise<T>): Promise<T> {
  const context = await newSessionContext(browser);
  try {
    const page = await context.newPage();
    await login(page);
    return await body(page);
  } finally {
    await context.close();
  }
}

/** The fields of GET /api/v1/runs/{id} that these tests read. */
interface RunInfo {
  status: string;
  sdkVersion: string | null;
  changeType: string | null;
  steps: { name: string; status: string }[];
}

async function runInfo(page: Page, runId: string): Promise<RunInfo> {
  const res = await page.request.get(`/api/v1/runs/${runId}`);
  expect(res.ok(), `GET run returned ${res.status()}`).toBeTruthy();
  return (await res.json()) as RunInfo;
}

/** One generated file as the run recorded it. The path keeps its '/' separators. */
async function fileContent(page: Page, runId: string, path: string): Promise<string | null> {
  const res = await page.request.get(`/api/v1/runs/${runId}/files/${path}`);
  expect(res.ok(), `GET run file returned ${res.status()}`).toBeTruthy();
  return ((await res.json()) as { content: string | null }).content;
}

/** The versions of a project, newest first, as the API lists them. */
async function versionsOf(page: Page, projectId: string): Promise<{ version: string; changeType: string; runId: string }[]> {
  const res = await page.request.get(`/api/v1/projects/${projectId}/versions`);
  expect(res.ok(), `GET versions returned ${res.status()}`).toBeTruthy();
  return (await res.json()) as { version: string; changeType: string; runId: string }[];
}

/**
 * The files the run committed to `repo`, by path, with their text. The commit writes each file in a tree POST (the
 * kohsuke client sends the text inline), so the journal holds the decoded content. Several POSTs are one commit when a
 * run has more files than one tree write takes.
 */
async function committedFiles(repo: string): Promise<Map<string, string>> {
  const writes = await requests({ method: 'POST', urlPath: `/repos/${repo}/git/trees` });
  const files = new Map<string, string>();
  for (const write of writes) {
    expect(write.status, `a tree write to ${repo} was refused`).toBe(201);
    const { tree } = bodyOf<{ tree: { path: string; content?: string }[] }>(write);
    for (const item of tree) {
      if (typeof item.content === 'string') files.set(item.path, item.content);
    }
  }
  return files;
}

const isDocs = (path: string) => path.startsWith(`${OUT_DIR}docs/`);

/** A tree stub for `repo`'s default branch: the listing the commit compares with, so each path counts as on the branch. */
function treeStub(repo: string, paths: string[]): object {
  return {
    priority: 1,
    request: { method: 'GET', urlPathPattern: `/repos/${repo}/git/trees/.+` },
    response: {
      status: 200,
      jsonBody: {
        sha: STUB_TREE_SHA,
        truncated: false,
        tree: paths.map((path) => ({ path, mode: '100644', type: 'blob', sha: STUB_BLOB_SHA, size: 1 })),
      },
    },
  };
}

/** The contents stub for the spec file: a later version of the spec, so the next run has a diff. */
function specStub(content: string): object {
  return {
    priority: 1,
    request: { method: 'GET', urlPath: SPEC_URL },
    response: {
      status: 200,
      jsonBody: {
        type: 'file',
        encoding: 'base64',
        name: 'billing.yaml',
        path: SPEC_PATH,
        sha: 'a'.repeat(40),
        content: Buffer.from(content).toString('base64'),
        html_url: `https://github.com/${REPO}/blob/main/${SPEC_PATH}`,
      },
    },
  };
}

/** The repository list of the signed-in user: the fixture repository, and the separate output repository. */
function reposStub(): object {
  return {
    priority: 1,
    request: { method: 'GET', urlPath: '/user/repos' },
    response: {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
      jsonBody: [
        {
          id: 3001,
          name: 'billing-api',
          full_name: REPO,
          private: false,
          owner: { login: 'acme', id: 2001, type: 'Organization' },
          html_url: `https://github.com/${REPO}`,
          default_branch: 'main',
          pushed_at: '2026-11-01T09:00:00Z',
          permissions: { admin: true, push: true, pull: true },
        },
        {
          id: 3003,
          name: 'billing-sdk',
          full_name: OUTPUT_REPO,
          private: false,
          owner: { login: 'acme', id: 2001, type: 'Organization' },
          html_url: `https://github.com/${OUTPUT_REPO}`,
          default_branch: 'main',
          pushed_at: null,
          permissions: { admin: true, push: true, pull: true },
        },
      ],
    },
  };
}

/** Adds a stub and remembers its id for afterEach. */
async function stub(mapping: object): Promise<void> {
  addedStubs.push(await addMapping(mapping));
}

/** Removes the stubs the test added, and then sweeps any temporary stub left behind. Rethrows what failed. */
async function removeAddedStubs(): Promise<void> {
  const failures: unknown[] = [];
  const removed = await Promise.allSettled(addedStubs.splice(0).map((id) => removeMapping(id)));
  for (const result of removed) if (result.status === 'rejected') failures.push(result.reason);
  await sweepTempStubs().catch((error: unknown) => failures.push(error));
  if (failures.length > 0) throw new AggregateError(failures, 'could not restore the mock');
}

/** Creates the project for the fixture repo through the wizard's existing-repo flow, as config.spec.ts does. */
async function createAppendProject(page: Page): Promise<string> {
  const orgId = await acmeOrgId(page);
  await removeProjectFor(page, orgId, REPO);
  await createExistingProject(page, orgId, REPO, SPEC_PATH);
  return projectIdOf(page);
}

/**
 * Drives the wizard to a project whose output goes to the separate repository: step 3 picks "Separate repository" and the
 * output repository, then creates the project. Resolves once the wizard lands on the project's config page.
 */
async function createSeparateProject(page: Page): Promise<string> {
  const orgId = await acmeOrgId(page);
  await removeProjectFor(page, orgId, REPO);
  await page.goto(`/orgs/${orgId}/projects/new`);
  await page.getByRole('group', { name: 'Existing repositories' }).getByRole('button', { name: REPO }).click();
  await page.getByRole('button', { name: 'Next' }).click();
  await page.getByLabel('Spec path').fill(SPEC_PATH);
  await page.getByRole('button', { name: 'Next' }).click();
  await page.getByRole('combobox', { name: 'Output mode' }).click();
  await page.getByRole('option', { name: 'Separate repository', exact: true }).click();
  await page.getByRole('group', { name: 'Output repositories' }).getByRole('button', { name: OUTPUT_REPO }).click();
  await page.getByRole('button', { name: 'Create project' }).click();
  await expect(page).toHaveURL(/\/projects\/[^/]+\/config/);
  return projectIdOf(page);
}

// ---------------------------------------------------------------------------------------------------------------------
// One shared run (every output, APPEND to the fixture repository). The checks below read its commit, its pull request,
// its tags and its version history, so the run is made once for the group.
// ---------------------------------------------------------------------------------------------------------------------

test.describe('Generated outputs, one shared run', () => {
  test.beforeEach(() => requirePipeline());

  test.beforeAll(async ({ browser }) => {
    if (process.env.E2E_PIPELINE !== '1') return;
    test.setTimeout(SETUP_TIMEOUT);
    await sweepTempStubs();
    shared = await withSession(browser, async (page) => {
      const orgId = await acmeOrgId(page);
      await removeProjectFor(page, orgId, REPO);
      // The journal starts empty here, so every write the shared run makes is in it.
      await resetJournal(page.request);
      await createExistingProject(page, orgId, REPO, SPEC_PATH);
      const projectId = projectIdOf(page);
      await saveConfig(page, projectId, ALL_OUTPUTS, { full: true });
      const runId = await startRun(page, projectId, false);
      const run: RunSummary = await waitForRun(page.request, projectId, 'SUCCESS', { runId, timeout: RUN_WAIT });
      if (run.prNumber === null) throw new Error('the shared run opened no pull request');
      const info = await runInfo(page, runId);
      return { projectId, runId, prNumber: run.prNumber, version: info.sdkVersion ?? '' };
    });
  });

  // Runs even when the set-up failed partway, so a project it created is removed.
  test.afterAll(async ({ browser }) => {
    if (process.env.E2E_PIPELINE !== '1') return;
    await withSession(browser, (page) => removeAcmeProject(page, REPO));
  });

  test.describe('TS SDK, Python SDK, CLI, MCP, docs (with and without AI) produced and checked', () => {
    test('the TypeScript SDK, Python SDK, CLI, MCP and docs files are committed to the run branch', async ({ page }) => {
      const run = sharedRun();
      await login(page);
      const files = await committedFiles(REPO);
      const info = await runInfo(page, run.runId);

      // The run's syntax check (tsc, ruff, prettier, ...) ran in the sandbox and was done when the commit was made.
      expect(info.steps.find((step) => step.name === 'SYNTAX_CHECK')?.status, 'the syntax check step is done').toBe('DONE');
      expect(run.version, 'the run released a version').toMatch(/^\d+\.\d+\.\d+$/);
      const version = run.version;

      const expected = [
        // TypeScript SDK (the per-tag resource files are checked by pattern below)
        `${OUT_DIR}sdk/typescript/package.json`,
        `${OUT_DIR}sdk/typescript/tsconfig.json`,
        `${OUT_DIR}sdk/typescript/README.md`,
        `${OUT_DIR}sdk/typescript/src/client.ts`,
        `${OUT_DIR}sdk/typescript/src/errors.ts`,
        `${OUT_DIR}sdk/typescript/src/index.ts`,
        `${OUT_DIR}sdk/typescript/src/types.ts`,
        CUSTOM_PATH,
        // Python SDK, in the package directory the PyPI name gives
        `${OUT_DIR}sdk/python/pyproject.toml`,
        `${OUT_DIR}sdk/python/README.md`,
        `${OUT_DIR}sdk/python/emitkit_billing/__init__.py`,
        `${OUT_DIR}sdk/python/emitkit_billing/client.py`,
        `${OUT_DIR}sdk/python/emitkit_billing/errors.py`,
        `${OUT_DIR}sdk/python/emitkit_billing/models.py`,
        `${OUT_DIR}sdk/python/emitkit_billing/custom/__init__.py`,
        // CLI
        `${OUT_DIR}cli/package.json`,
        `${OUT_DIR}cli/tsconfig.json`,
        `${OUT_DIR}cli/README.md`,
        `${OUT_DIR}cli/src/index.ts`,
        // MCP server
        `${OUT_DIR}mcp/package.json`,
        `${OUT_DIR}mcp/tsconfig.json`,
        `${OUT_DIR}mcp/README.md`,
        `${OUT_DIR}mcp/src/server.ts`,
        `${OUT_DIR}mcp/src/tools.ts`,
        // Docs, under the version
        `${OUT_DIR}docs/v${version}/index.md`,
      ];
      const missing = expected.filter((path) => !files.has(path));
      expect(missing, 'files missing from the commit').toEqual([]);

      const paths = [...files.keys()];
      expect(paths.filter((p) => /^\.emitkit\/sdk\/typescript\/src\/resources\/[^/]+\.ts$/.test(p)).length, 'one TypeScript resource file per tag').toBeGreaterThan(0);
      expect(paths.filter((p) => /^\.emitkit\/sdk\/python\/emitkit_billing\/resources\/[^/]+\.py$/.test(p)).length, 'one Python resource module per tag').toBeGreaterThan(0);
      expect(paths.filter(isDocs).length, 'the docs have more than the index').toBeGreaterThan(1);

      // Content, not only presence: the package names and versions are the ones the config and the run set.
      const tsPackage = JSON.parse(files.get(`${OUT_DIR}sdk/typescript/package.json`) ?? '{}') as { name?: string; version?: string };
      expect(tsPackage.name, 'the npm package is scoped with the npm scope').toBe('@acme-billing/billing-api');
      expect(tsPackage.version, 'the npm package carries the run version').toBe(version);
      expect(files.get(`${OUT_DIR}sdk/python/pyproject.toml`) ?? '', 'the Python package carries the PyPI name').toContain('name = "emitkit-billing"');
      expect(() => JSON.parse(files.get(`${OUT_DIR}cli/package.json`) ?? '')).not.toThrow();
      expect(() => JSON.parse(files.get(`${OUT_DIR}mcp/package.json`) ?? '')).not.toThrow();
      expect(
        [...files.entries()].filter(([p]) => isDocs(p)).some(([, text]) => text.includes('/invoices')),
        'the docs document the fixture path',
      ).toBe(true);
    });

    test('docs with an AI key match the template docs: an AI failure falls back silently', async ({ page }) => {
      test.setTimeout(ONE_RUN_TIMEOUT);
      const run = sharedRun();
      await login(page);
      const committed = await committedFiles(REPO);
      const docs = [...committed.keys()].filter(isDocs).sort();
      expect(docs.length, 'the shared run committed docs').toBeGreaterThan(0);
      const writesBefore = (await requests({ method: 'POST', urlPath: `/repos/${REPO}/git/trees` })).length;

      await saveConfig(page, run.projectId, { ...ALL_OUTPUTS, aiApiKey: AI_KEY }, { full: true });
      try {
        // The worker is on the app network only (docker-compose.e2e.yml), with no route to the internet, so the docs
        // assistant's call to Gemini fails on every attempt. The generator catches the failure and writes the template
        // docs, and the run still succeeds. This covers the fallback path only: real AI output needs a configurable Gemini
        // base URL in the backend (ChatModelFactory pins it). A dry run keeps the comparison to the generated files and
        // commits nothing.
        const dryRunId = await startRun(page, run.projectId, true);
        await waitForRun(page.request, run.projectId, 'SUCCESS', { runId: dryRunId, timeout: RUN_WAIT });

        const writesAfter = (await requests({ method: 'POST', urlPath: `/repos/${REPO}/git/trees` })).length;
        expect(writesAfter, 'a dry run commits nothing').toBe(writesBefore);

        const dryDocs = (await manifestOf(page, dryRunId)).map((entry) => entry.path).filter(isDocs).sort();
        expect(dryDocs, 'the dry run generates the same docs files').toEqual(docs);
        for (const path of docs) {
          const content = await fileContent(page, dryRunId, path);
          expect(content === committed.get(path), `${path} with the AI key is not the template output`).toBe(true);
        }
      } finally {
        // Clears the key (an empty string clears it), so later tests in this group run without AI.
        await saveConfig(page, run.projectId, { ...ALL_OUTPUTS, aiApiKey: '' }, { full: true });
      }
    });
  });

  test.describe('npm and PyPI workflow files generated', () => {
    test('the npm and PyPI publish workflows are committed, and publish on sdk/v tags', async () => {
      const files = await committedFiles(REPO);
      const npm = files.get('.github/workflows/emitkit-publish-npm.yml') ?? '';
      const pypi = files.get('.github/workflows/emitkit-publish-pypi.yml') ?? '';
      expect(npm, 'the npm workflow is committed').not.toBe('');
      expect(pypi, 'the PyPI workflow is committed').not.toBe('');
      // Both trigger on the SDK tags, and each builds in its own SDK directory with its own token.
      for (const workflow of [npm, pypi]) expect(workflow, 'the workflow triggers on sdk/v tags').toContain("'sdk/v*'");
      expect(npm).toContain('NPM_TOKEN');
      expect(npm, 'the npm workflow builds the TypeScript SDK').toContain('.emitkit/sdk/typescript');
      expect(pypi).toContain('PYPI_TOKEN');
      expect(pypi, 'the PyPI workflow builds the Python SDK').toContain('.emitkit/sdk/python');
      // No Java SDK was selected, so no Maven workflow is written.
      expect(files.has('.github/workflows/emitkit-publish-maven.yml'), 'no Maven workflow without a Java SDK').toBe(false);
    });
  });

  test.describe('PR opened. Merge creates sdk/v* (and mcp/v*) tags', () => {
    test('the run opens one pull request, titled with its version, from the run branch', async () => {
      const run = sharedRun();
      const pull = onlyRequest(await requests({ method: 'POST', urlPath: `/repos/${REPO}/pulls` }));
      expect(pull.status, 'the mock accepted the pull request').toBe(201);
      const body = bodyOf<{ title: string; head: string; base: string }>(pull);
      expect(body.title, 'the title names the SDK version').toBe(`EmitKit: SDK v${run.version}`);
      expect(body.head, 'the pull request is from the run branch').toBe(`emitkit/run-${run.runId}`);
      expect(body.base, 'the pull request targets the default branch').toBe('main');
    });

    test('merging the pull request creates the sdk/v and mcp/v tags at the merge commit', async ({ request }) => {
      const run = sharedRun();
      const secret = await webhookSecret(REPO);
      const mergeCommitSha = randomBytes(20).toString('hex');

      // The run has MCP output, so the merge tags both the SDK and the MCP server. The delivery is signed as GitHub signs it.
      const delivery = await deliverPullRequest(
        request,
        secret,
        mergedPullRequestEvent({ repo: REPO, number: run.prNumber, headRef: `emitkit/run-${run.runId}`, mergeCommitSha }),
      );
      expect(delivery.httpStatus, 'the signed delivery is accepted').toBe(200);
      expect(delivery.outcome, 'the merge is answered as tagged').toEqual({ status: 'tagged' });

      const tagWrites = (await requests({ method: 'POST', urlPath: `/repos/${REPO}/git/refs` }))
        .map((entry) => ({ status: entry.status, ...bodyOf<{ ref: string; sha: string }>(entry) }))
        .filter((write) => write.ref.startsWith('refs/tags/'));
      expect(tagWrites.map((write) => write.ref).sort(), 'the tags are the sdk and mcp versions').toEqual(
        [`refs/tags/mcp/v${run.version}`, `refs/tags/sdk/v${run.version}`].sort(),
      );
      expect(
        tagWrites.every((write) => write.status === 201 && write.sha === mergeCommitSha),
        'each tag is created at the merge commit',
      ).toBe(true);
    });
  });

  test.describe('Version history with change type', () => {
    test.afterEach(async () => {
      await removeAddedStubs();
    });

    test('a later run that adds an operation is listed as Patch, and the first release as Minor', async ({ page }) => {
      test.setTimeout(ONE_RUN_TIMEOUT);
      const run = sharedRun();
      await login(page);
      // The second run reads a spec with one more operation. The first release was a Minor (the first run), and an added
      // operation below 1.0.0 is a Patch, so the history shows both change types.
      await stub(specStub(SPEC_V2));
      const secondRunId = await startRun(page, run.projectId, false);
      await waitForRun(page.request, run.projectId, 'SUCCESS', { runId: secondRunId, timeout: RUN_WAIT });
      const second = await runInfo(page, secondRunId);
      expect(second.changeType, 'an added operation below 1.0.0 is a Patch').toBe('PATCH');

      const versions = await versionsOf(page, run.projectId);
      expect(
        versions.map((v) => ({ version: v.version, changeType: v.changeType, runId: v.runId })),
        'the history lists the second run first, then the first run',
      ).toEqual([
        { version: second.sdkVersion ?? '', changeType: 'PATCH', runId: secondRunId },
        { version: run.version, changeType: 'MINOR', runId: run.runId },
      ]);

      // The page shows the same rows with their change type, each linked to its run.
      await page.goto(`/projects/${run.projectId}/versions`);
      await expect(page.getByRole('heading', { name: 'Version history' })).toBeVisible();
      const row = (version: string) =>
        page.getByRole('listitem').filter({ has: page.getByRole('button', { name: version, exact: true }) });
      await expect(row(second.sdkVersion ?? '').getByText('Patch', { exact: true })).toBeVisible();
      await expect(row(second.sdkVersion ?? '').getByRole('link', { name: 'View run' })).toHaveAttribute('href', `/runs/${secondRunId}`);
      await expect(row(run.version).getByText('Minor', { exact: true })).toBeVisible();
      await expect(row(run.version).getByRole('link', { name: 'View run' })).toHaveAttribute('href', `/runs/${run.runId}`);
    });
  });
});

// ---------------------------------------------------------------------------------------------------------------------
// APPEND and SEPARATE. Each mode has its own project and its own run. A custom stub the target branch already holds is
// skipped, so the commit never overwrites it.
// ---------------------------------------------------------------------------------------------------------------------

test.describe('APPEND and SEPARATE modes. Custom stubs are not overwritten', () => {
  test.beforeEach(async () => {
    requirePipeline();
    await sweepTempStubs();
  });

  test.afterEach(async ({ page }) => {
    try {
      await removeAddedStubs();
    } finally {
      await removeAcmeProject(page, REPO);
    }
  });

  test('APPEND: the run commits to the project repository, and keeps a custom stub already on its branch', async ({ page, request }) => {
    test.setTimeout(ONE_RUN_TIMEOUT);
    await login(page);
    await resetJournal(request);
    // The default branch already holds the user's custom stub. The run must skip it, not write over it.
    await stub(treeStub(REPO, [SPEC_PATH, CUSTOM_PATH]));
    const projectId = await createAppendProject(page);
    await saveConfig(page, projectId, SDK_ONLY, { full: true });
    const runId = await startRun(page, projectId, false);
    await waitForRun(page.request, projectId, 'SUCCESS', { runId, timeout: RUN_WAIT });

    const files = await committedFiles(REPO);
    expect(files.has(CUSTOM_PATH), 'the custom stub is not committed over the one on the branch').toBe(false);
    expect(files.has(`${OUT_DIR}sdk/typescript/src/client.ts`), 'the generated files are committed').toBe(true);
    const manifest = await manifestOf(page, runId);
    expect(manifest.find((entry) => entry.path === CUSTOM_PATH)?.status, 'the manifest marks the stub as skipped').toBe('SKIPPED');
    const pull = onlyRequest(await requests({ method: 'POST', urlPath: `/repos/${REPO}/pulls` }));
    expect(bodyOf<{ base: string }>(pull).base, 'the pull request targets the project repository').toBe('main');
    expect(
      (await requests({ method: 'POST', urlPath: `/repos/${OUTPUT_REPO}/git/trees` })).length,
      'nothing is committed to the output repository in APPEND mode',
    ).toBe(0);
  });

  test('SEPARATE: the run commits to the output repository, and keeps a custom stub already on its branch', async ({ page, request }) => {
    test.setTimeout(ONE_RUN_TIMEOUT);
    await login(page);
    await resetJournal(request);
    // The output repository is in the user's repository list, and its default branch already holds the custom stub.
    await stub(reposStub());
    await stub(treeStub(OUTPUT_REPO, [CUSTOM_PATH]));
    const projectId = await createSeparateProject(page);
    await saveConfig(page, projectId, SDK_ONLY, { full: true });
    const runId = await startRun(page, projectId, false);
    await waitForRun(page.request, projectId, 'SUCCESS', { runId, timeout: RUN_WAIT });

    const files = await committedFiles(OUTPUT_REPO);
    expect(files.has(CUSTOM_PATH), 'the custom stub in the output repository is not overwritten').toBe(false);
    expect(files.has(`${OUT_DIR}sdk/typescript/package.json`), 'the SDK is committed to the output repository').toBe(true);
    expect(
      (await requests({ method: 'POST', urlPath: `/repos/${REPO}/git/trees` })).length,
      'nothing is committed to the source repository in SEPARATE mode',
    ).toBe(0);
    const pull = onlyRequest(await requests({ method: 'POST', urlPath: `/repos/${OUTPUT_REPO}/pulls` }));
    expect(pull.status, 'the pull request is opened in the output repository').toBe(201);
  });
});
