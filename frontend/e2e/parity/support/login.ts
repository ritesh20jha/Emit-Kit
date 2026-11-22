import { expect, type Page } from '@playwright/test';

/**
 * Signs in through the GitHub OAuth round trip. The browser follows the authorize redirect to the WireMock mock, which
 * sends it back to the api with a code; the api exchanges the code and reads the profile from the mock, then redirects
 * to the web app. Resolves once the signed-in shell (the organization navigation) is visible.
 */
export async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByRole('button', { name: 'Sign in with GitHub' }).click();
  // The first sign-in after the stack starts can be slow (api and Caddy warm up), so the wait is longer than the default.
  await expect(page.getByRole('navigation', { name: 'Organization' })).toBeVisible({ timeout: 15_000 });
}
