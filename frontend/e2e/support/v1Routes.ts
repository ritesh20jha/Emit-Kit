import type { Locator, Page } from '@playwright/test';

/** Fixture ids the MSW browser worker answers for (src/test/msw/handlers). */
export const ORG = 'org1';
export const PROJECT = 'p1';
export const RUN = 'r1';

export interface V1Route {
  path: string;
  /** Content only the fixtures produce. A route that falls into an error, empty or 404 state never shows it. */
  ready: (page: Page) => Locator;
}

/** Every v1 route from the v1 design spec, section 10.1, with fixture ids. `/` redirects to the org's projects. */
export const V1_ROUTES: readonly V1Route[] = [
  { path: '/login', ready: (page) => page.getByRole('heading', { name: 'Sign in to EmitKit' }) },
  { path: '/', ready: (page) => page.getByText('acme/billing-api', { exact: true }) },
  { path: `/orgs/${ORG}`, ready: (page) => page.getByText('acme/billing-api', { exact: true }) },
  { path: `/orgs/${ORG}/members`, ready: (page) => page.getByText('hubot', { exact: true }) },
  { path: `/orgs/${ORG}/settings`, ready: (page) => page.getByText('1001', { exact: true }) },
  { path: `/orgs/${ORG}/projects/new`, ready: (page) => page.getByRole('button', { name: /^acme\/billing-api/ }) },
  { path: `/projects/${PROJECT}`, ready: (page) => page.getByRole('heading', { name: 'acme/billing-api' }) },
  { path: `/projects/${PROJECT}/runs`, ready: (page) => page.getByRole('link', { name: 'Pull request #18' }) },
  { path: `/projects/${PROJECT}/editor`, ready: (page) => page.getByRole('textbox', { name: 'OpenAPI spec' }) },
  { path: `/projects/${PROJECT}/diff?from=s1&to=s2`, ready: (page) => page.getByText('Breaking changes', { exact: true }) },
  { path: `/projects/${PROJECT}/config`, ready: (page) => page.getByLabel('Output directory', { exact: true }) },
  { path: `/projects/${PROJECT}/versions`, ready: (page) => page.getByRole('button', { name: '1.4.0', exact: true }) },
  { path: `/projects/${PROJECT}/settings`, ready: (page) => page.getByText('Danger zone', { exact: true }) },
  { path: `/runs/${RUN}`, ready: (page) => page.getByText('Fetched openapi/billing.yaml at a1b2c3d') },
  { path: `/runs/${RUN}/logs`, ready: (page) => page.getByText('Fetched openapi/billing.yaml at a1b2c3d') },
  { path: `/runs/${RUN}/files?path=sdk/typescript/src/index.ts`, ready: (page) => page.getByRole('region', { name: 'Diff of sdk/typescript/src/index.ts' }) },
  { path: `/runs/${RUN}/lint`, ready: (page) => page.getByText('operation-operationId', { exact: true }) },
  { path: `/runs/${RUN}/changes`, ready: (page) => page.getByText('DELETE /invoices/{id}', { exact: true }) },
];

/** Pins the theme before the app reads it. ThemeProvider reads `emitkit.theme` from localStorage on mount. */
export async function setTheme(page: Page, theme: 'light' | 'dark'): Promise<void> {
  await page.addInitScript((value) => window.localStorage.setItem('emitkit.theme', value), theme);
}
