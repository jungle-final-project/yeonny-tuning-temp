import { expect, test } from '@playwright/test';

const routes = [
  '/',
  '/requirements/new',
  '/builds/latest',
  '/builds/00000000-0000-4000-8000-000000002001',
  '/self-quote',
  '/checkout',
  '/checkout/offers/00000000-0000-4000-8000-000000020001',
  '/checkout/payment/00000000-0000-4000-8000-000000020001',
  '/checkout/complete/00000000-0000-4000-8000-000000020001',
  '/parts',
  '/parts/00000000-0000-4000-8000-000000013001',
  '/builds/00000000-0000-4000-8000-000000002001/change-part',
  '/my/quotes',
  '/my/assembly-requests',
  '/my/assembly-requests/00000000-0000-4000-8000-000000020001',
  '/technician/apply',
  '/technician',
  '/technician/jobs',
  '/technician/requests/00000000-0000-4000-8000-000000020001',
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
  '/admin/assembly',
  '/admin/price-jobs',
  '/admin/build-graph-layouts',
  '/admin/load-tests',
  '/admin/support-chat-sessions',
  '/admin/as-tickets',
  '/admin/as-tickets/00000000-0000-4000-8000-000000006001'
];

for (const route of routes) {
  test(`renders ${route}`, async ({ page }) => {
    await page.goto(route);
    await expect(page.getByRole('link', { name: '다짜줘 홈' })).toBeVisible();
    await expect(page.getByRole('main')).toBeVisible();
  });
}
