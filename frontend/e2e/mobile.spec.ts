import { expect, test } from '@playwright/test';
import { setTheme, V1_ROUTES } from './support/v1Routes';

test.use({ viewport: { width: 360, height: 740 } });

for (const route of V1_ROUTES) {
  test(`${route} does not scroll horizontally at 360px`, async ({ page }) => {
    await setTheme(page, 'light');
    await page.goto(route);
    await page.waitForLoadState('networkidle');

    const overflow = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }));
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth);
  });
}
