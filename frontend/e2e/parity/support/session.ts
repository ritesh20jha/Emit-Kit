import type { Browser, BrowserContext } from '@playwright/test';

/** The stack's Caddy address. Mirrors playwright.config.ts (the parity project's baseURL). */
export const BASE_URL = process.env.E2E_BASE_URL ?? 'https://localhost';

/**
 * A fresh browser context for one signed-in session. Each session has its own cookies, so a test can hold the admin and
 * the viewer at once. The stack uses its own certificate, so HTTPS errors are ignored here as in the project config.
 */
export function newSessionContext(browser: Browser): Promise<BrowserContext> {
  return browser.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
}
