import AxeBuilder from '@axe-core/playwright';
import { expect, request, test } from '@playwright/test';

interface StorybookIndex {
  entries: Record<string, { id: string; type: string }>;
}

// Read at collection time, so every story becomes its own test in both themes.
const api = await request.newContext({ baseURL: 'http://localhost:6007' });
const index = (await (await api.get('/index.json')).json()) as StorybookIndex;
await api.dispose();
const storyIds = Object.values(index.entries)
  .filter((entry) => entry.type === 'story')
  .map((entry) => entry.id);

for (const id of storyIds) {
  for (const theme of ['light', 'dark'] as const) {
    test(`${id} [${theme}]`, async ({ page }) => {
      await page.goto(`/iframe.html?id=${id}&viewMode=story&globals=theme:${theme}`);
      await expect(page.locator('#storybook-root > *').first()).toBeVisible();
      await page.evaluate(() => document.fonts.ready);

      const results = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze();
      expect(
        results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`),
      ).toEqual([]);

      await expect(page).toHaveScreenshot(`${id}-${theme}.png`, { animations: 'disabled' });
    });
  }
}
