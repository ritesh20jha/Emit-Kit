import { expect, test } from '@playwright/test';

test('login page renders the sign-in heading', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Sign in to EmitKit' })).toBeVisible();
});
