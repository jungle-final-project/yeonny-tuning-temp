import { expect, test } from '@playwright/test';

const routes = [
  '/',
  '/requirements/new',
  '/builds/00000000-0000-4000-8000-000000002001',
  '/self-quote',
  '/checkout',
  '/checkout/complete',
  '/parts/00000000-0000-4000-8000-000000013001',
  '/builds/00000000-0000-4000-8000-000000002001/change-part',
  '/my/quotes',
  '/support/new',
  '/support/ai-chat',
  '/support/00000000-0000-4000-8000-000000006001',
  '/login',
  '/signup',
  '/admin',
  '/admin/agent-sessions',
  '/admin/agent-sessions/00000000-0000-4000-8000-000000003001',
  '/admin/tool-invocations',
  '/admin/tool-invocations/00000000-0000-4000-8000-000000005002',
  '/admin/rag-evidence',
  '/admin/rag-evidence/00000000-0000-4000-8000-000000004001',
  '/admin/parts',
  '/admin/price-jobs',
  '/admin/load-tests',
  '/admin/as-tickets',
  '/admin/as-tickets/00000000-0000-4000-8000-000000006001'
];

for (const route of routes) {
  test(`renders ${route}`, async ({ page }) => {
    await page.goto(route);
    await expect(page.locator('body')).toContainText('스펙업');
    await expect(page.getByRole('main')).toBeVisible();
  });
}
