import { expect, test } from '@playwright/test';
import { setTheme, V1_ROUTES } from './support/v1Routes';

test.use({ viewport: { width: 360, height: 740 } });

for (const route of V1_ROUTES) {
  test(`${route.path} does not scroll horizontally at 360px`, async ({ page }) => {
    await setTheme(page, 'light');
    await page.goto(route.path);
    await expect(route.ready(page)).toBeVisible();
    await page.waitForLoadState('networkidle');

    const overflow = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }));
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth);
  });
}
