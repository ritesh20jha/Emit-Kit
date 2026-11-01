import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: [['list']],
  use: {
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      testIgnore: 'visual/**',
      use: { ...devices['Desktop Chrome'], baseURL: 'http://localhost:4173' },
    },
    // Storybook stories: contrast (axe) and screenshots, in light and dark, against a static Storybook build.
    {
      name: 'visual',
      testMatch: 'visual/**',
      use: { ...devices['Desktop Chrome'], baseURL: 'http://localhost:6007' },
    },
  ],
  webServer: [
    {
      command: 'pnpm build && pnpm preview --port 4173 --strictPort',
      url: 'http://localhost:4173',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
    {
      command: 'pnpm build-storybook && pnpm exec vite preview --outDir storybook-static --port 6007 --strictPort',
      url: 'http://localhost:6007/index.json',
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
    },
  ],
});
