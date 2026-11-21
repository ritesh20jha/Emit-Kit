import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';

const BLOCKING = ['serious', 'critical'];

/** The serious and critical axe violations on the page, each as `rule (impact): targets`. Empty when there are none. */
export async function blockingViolations(page: Page): Promise<string[]> {
  const results = await new AxeBuilder({ page }).analyze();
  return results.violations
    .filter((violation) => BLOCKING.includes(violation.impact ?? ''))
    .map((violation) => `${violation.id} (${violation.impact}): ${violation.nodes.map((n) => n.target.join(' ')).join(', ')}`);
}
