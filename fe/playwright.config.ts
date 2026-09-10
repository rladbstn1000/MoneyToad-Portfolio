import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e', testMatch: '*.spec.ts', workers: 1, retries: 0, maxFailures: 1,
  timeout: 90_000, expect: { timeout: 15_000 },
  reporter: [['./e2e/reporter.ts']],
  outputDir: process.env.E2E_ARTIFACTS,
  use: {
    browserName: 'chromium', headless: true, actionTimeout: 15_000, baseURL: process.env.E2E_ORIGIN,
    ignoreHTTPSErrors: true, serviceWorkers: 'block',
    viewport: { width: 1440, height: 1000 },
    trace: 'off', video: 'off', screenshot: 'off',
  },
});
