import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testMatch: /mvp\.spec\.ts/,
  timeout: 600_000,
  expect: {
    timeout: 60_000
  },
  use: {
    actionTimeout: 60_000,
    baseURL: process.env.MVP_WEB_BASE_URL ?? 'http://127.0.0.1:5173',
    viewport: { width: 1440, height: 1024 },
    trace: 'on-first-retry'
  },
  projects: [
    {
      name: 'mvp-desktop-chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
});
