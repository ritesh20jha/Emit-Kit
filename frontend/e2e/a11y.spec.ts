import { expect, test } from '@playwright/test';
import { blockingViolations } from './support/axe';
import { PROJECT, setTheme, V1_ROUTES } from './support/v1Routes';

for (const route of V1_ROUTES) {
  for (const theme of ['light', 'dark'] as const) {
    test(`${route.path} has no serious or critical axe violations [${theme}]`, async ({ page }) => {
      await setTheme(page, theme);
      await page.goto(route.path);
      // The theme is checked too, so a renamed storage key cannot quietly turn the dark runs into light ones.
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      await expect(route.ready(page)).toBeVisible();
      await page.waitForLoadState('networkidle');

      expect(await blockingViolations(page)).toEqual([]);
    });
  }
}

// On a phone the sidebar is a drawer behind the menu button. It is part of every signed-in route, so it is audited too.
for (const theme of ['light', 'dark'] as const) {
  test(`mobile navigation drawer has no serious or critical axe violations [${theme}]`, async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 740 });
    await setTheme(page, theme);
    await page.goto(`/projects/${PROJECT}/runs`);
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
    await page.waitForLoadState('networkidle');
    await page.getByRole('button', { name: 'Open navigation' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    expect(await blockingViolations(page)).toEqual([]);
  });
}
