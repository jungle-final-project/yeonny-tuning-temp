import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testMatch: /build-chat-stateful-qa\.spec\.ts/,
  timeout: 1_800_000,
  expect: { timeout: 30_000 },
  use: {
    baseURL: process.env.STATEFUL_QA_WEB_BASE_URL ?? 'http://127.0.0.1:5173',
    viewport: { width: 1440, height: 1024 },
    trace: 'on-first-retry'
  },
  projects: [{
    name: 'build-chat-stateful-qa-chromium',
    use: { ...devices['Desktop Chrome'] }
  }]
});
