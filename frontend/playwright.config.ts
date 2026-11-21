import { defineConfig, devices } from '@playwright/test';

// The Storybook server builds only for the visual project: `test:visual` sets E2E_STORYBOOK=1.
const storybookServer = {
  command: 'pnpm build-storybook && pnpm exec vite preview --outDir storybook-static --port 6007 --strictPort',
  url: 'http://localhost:6007/index.json',
  reuseExistingServer: !process.env.CI,
  timeout: 180_000,
};

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
      // Built with VITE_MSW=1 so the browser worker answers the API from fixtures. It goes to its own dir, so the plain
      // build that check:bundle measures is left alone.
      command: 'VITE_MSW=1 pnpm exec vite build --outDir dist-e2e --emptyOutDir && pnpm exec vite preview --outDir dist-e2e --port 4173 --strictPort',
      url: 'http://localhost:4173',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
    ...(process.env.E2E_STORYBOOK === '1' ? [storybookServer] : []),
  ],
});
