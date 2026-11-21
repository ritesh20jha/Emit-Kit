import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { PROJECT, setTheme, V1_ROUTES } from './support/v1Routes';

const BLOCKING = ['serious', 'critical'];

for (const route of V1_ROUTES) {
  for (const theme of ['light', 'dark'] as const) {
    test(`${route} has no serious or critical axe violations [${theme}]`, async ({ page }) => {
      await setTheme(page, theme);
      await page.goto(route);
      await page.waitForLoadState('networkidle');

      const results = await new AxeBuilder({ page }).analyze();
      const blocking = results.violations
        .filter((violation) => BLOCKING.includes(violation.impact ?? ''))
        .map((violation) => `${violation.id} (${violation.impact}): ${violation.nodes.map((n) => n.target.join(' ')).join(', ')}`);
      expect(blocking).toEqual([]);
    });
  }
}

// On a phone the sidebar is a drawer behind the menu button. It is part of every signed-in route, so it is audited too.
for (const theme of ['light', 'dark'] as const) {
  test(`mobile navigation drawer has no serious or critical axe violations [${theme}]`, async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 740 });
    await setTheme(page, theme);
    await page.goto(`/projects/${PROJECT}/runs`);
    await page.waitForLoadState('networkidle');
    await page.getByRole('button', { name: 'Open navigation' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    const results = await new AxeBuilder({ page }).analyze();
    const blocking = results.violations
      .filter((violation) => BLOCKING.includes(violation.impact ?? ''))
      .map((violation) => `${violation.id} (${violation.impact}): ${violation.nodes.map((n) => n.target.join(' ')).join(', ')}`);
    expect(blocking).toEqual([]);
  });
}
