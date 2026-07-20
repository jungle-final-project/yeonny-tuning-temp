import { defineConfig, devices } from '@playwright/test';

// 이 파일은 Node에서 실행되지만 웹 tsconfig는 DOM 타입만 포함한다. 브라우저 전역 timer 타입을
// Node Timeout으로 오염시키지 않도록 필요한 env shape만 로컬 선언한다.
declare const process: { env: Record<string, string | undefined> };

const webPort = process.env.PLAYWRIGHT_WEB_PORT ?? '5174';
const webBaseUrl = `http://127.0.0.1:${webPort}`;

export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  use: {
    baseURL: webBaseUrl,
    viewport: { width: 1440, height: 1024 },
    trace: 'on-first-retry'
  },
  webServer: {
    command: `npm run dev -- --host 127.0.0.1 --port ${webPort} --strictPort`,
    url: webBaseUrl,
    reuseExistingServer: false,
    timeout: 120_000
  },
  projects: [
    {
      name: 'desktop-chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
});
