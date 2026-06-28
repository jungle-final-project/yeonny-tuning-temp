import { expect, test } from '@playwright/test';

const adminRoutes = [
  '/admin',
  '/admin/agent-sessions/demo-session',
  '/admin/tool-invocations/tool-power-001',
  '/admin/rag-evidence/rag-psu-001',
  '/admin/parts',
  '/admin/as-tickets',
  '/admin/as-tickets/AS-1031'
];

test('shows permission screen without calling auth/me when token is missing', async ({ page }) => {
  let authMeCalls = 0;
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.getByText('관리자 화면을 보려면 먼저 로그인해야 합니다.')).toBeVisible();
  await page.waitForTimeout(100);
  expect(authMeCalls).toBe(0);
});

for (const route of adminRoutes) {
  test(`guards ${route} when token is missing`, async ({ page }) => {
    await page.goto(route);

    await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
    await expect(page.getByText('관리자 화면을 보려면 먼저 로그인해야 합니다.')).toBeVisible();
    await expect(page.getByRole('link', { name: '로그인으로 이동' })).toBeVisible();
    await expect(page.getByRole('link', { name: '홈으로 이동' })).toBeVisible();
  });
}

test('does not expose protected admin page content without admin permission', async ({ page }) => {
  await page.goto('/admin/parts');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.locator('main')).not.toContainText('부품 DB');
  await expect(page.locator('main')).not.toContainText('가격 Job 상태');
});

test('shows permission screen when auth/me returns USER role', async ({ page }) => {
  let authMeCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'demo-jwt-user');
  });
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'user-1004', email: 'user@example.com', role: 'USER' })
    });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.getByText('현재 로그인한 계정에는 관리자 권한이 없습니다.')).toBeVisible();
  expect(authMeCalls).toBeGreaterThan(0);
});

test('shows login-needed message when auth/me returns 401', async ({ page }) => {
  let authMeCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'invalid-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'UNAUTHORIZED', message: '로그인이 필요합니다.' })
    });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.getByText('관리자 화면을 보려면 먼저 로그인해야 합니다.')).toBeVisible();
  expect(authMeCalls).toBeGreaterThan(0);
});

test('shows permission message when auth/me returns 403', async ({ page }) => {
  let authMeCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'demo-jwt-user');
  });
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({
      status: 403,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'FORBIDDEN', message: '관리자 권한이 필요합니다.' })
    });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.getByText('현재 로그인한 계정에는 관리자 권한이 없습니다.')).toBeVisible();
  expect(authMeCalls).toBeGreaterThan(0);
});

test('renders admin page when auth/me returns ADMIN role', async ({ page }) => {
  let authMeCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'demo-jwt-admin');
  });
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });

  await page.goto('/admin/agent-sessions/demo-session');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeHidden();
  await expect(page.locator('body')).toContainText('Agent / RAG / Tool 근거 상세');
  await expect(page.getByRole('main')).toContainText('Agent 상태 전이');
  expect(authMeCalls).toBeGreaterThan(0);
});

test('renders admin dashboard with ADMIN role and dashboard API response', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'demo-jwt-admin');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/dashboard', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        llmQueueP95: '12초',
        apiP95: '210ms',
        asOpen: 3,
        recommendationSuccess: '98%'
      })
    });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeHidden();
  await expect(page.locator('main')).toContainText('12초');
  await expect(page.locator('main')).toContainText('210ms');
  await expect(page.locator('main')).toContainText('3건');
  await expect(page.locator('main')).toContainText('98%');
});
