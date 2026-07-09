import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testMatch: /ai-latency\.spec\.ts/,
  timeout: 1_200_000,
  expect: {
    timeout: 60_000
  },
  use: {
    baseURL: process.env.AI_LATENCY_WEB_BASE_URL ?? 'http://127.0.0.1:5173',
    viewport: { width: 1440, height: 1024 },
    trace: 'on-first-retry'
  },
  projects: [
    {
      name: 'ai-latency-desktop-chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
});
