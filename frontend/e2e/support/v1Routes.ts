import type { Page } from '@playwright/test';

/** Fixture ids the MSW browser worker answers for (src/test/msw/handlers). */
export const ORG = 'org1';
export const PROJECT = 'p1';
export const RUN = 'r1';

/** Every v1 route from the v1 design spec, section 10.1, with fixture ids so each one renders populated content. */
export const V1_ROUTES: readonly string[] = [
  '/login',
  `/orgs/${ORG}`,
  `/orgs/${ORG}/members`,
  `/orgs/${ORG}/settings`,
  `/orgs/${ORG}/projects/new`,
  `/projects/${PROJECT}`,
  `/projects/${PROJECT}/runs`,
  `/projects/${PROJECT}/editor`,
  `/projects/${PROJECT}/diff?from=s1&to=s2`,
  `/projects/${PROJECT}/config`,
  `/projects/${PROJECT}/versions`,
  `/projects/${PROJECT}/settings`,
  `/runs/${RUN}/logs`,
  `/runs/${RUN}/files?path=sdk/typescript/src/index.ts`,
  `/runs/${RUN}/lint`,
  `/runs/${RUN}/changes`,
];

/** Pins the theme before the app reads it. ThemeProvider reads `emitkit.theme` from localStorage on mount. */
export async function setTheme(page: Page, theme: 'light' | 'dark'): Promise<void> {
  await page.addInitScript((value) => window.localStorage.setItem('emitkit.theme', value), theme);
}
