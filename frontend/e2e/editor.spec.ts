import { expect, test } from '@playwright/test';
import { PROJECT, setTheme } from './support/v1Routes';

// The validation worker runs swagger-parser in the browser. Without the Buffer shim, every valid spec reported
// "Buffer is not defined". Unit tests run in Node, where Buffer exists, so only this check sees that regression.
test('the editor reports no problems for a valid spec', async ({ page }) => {
  await setTheme(page, 'light');
  await page.goto(`/projects/${PROJECT}/editor`);
  await expect(page.getByText('No problems found.')).toBeVisible();
});
