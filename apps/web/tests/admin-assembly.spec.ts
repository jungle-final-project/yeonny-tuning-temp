import { expect, test } from '@playwright/test';

const pendingTechnician = {
  id: 'external-tech-pending',
  displayName: 'QA 외부 기사',
  initials: 'QA',
  status: 'INACTIVE',
  providerType: 'EXTERNAL',
  verificationStatus: 'PENDING',
  businessName: 'QA 조립소',
  contactPhone: '010-1111-2222',
  serviceRegions: ['서울'],
  serviceTypes: ['FULL_SERVICE'],
  specialties: ['게이밍 조립'],
  rating: 0,
  completedJobs: 0,
  avgResponseMinutes: 0,
  assemblyFee: 60_000,
  deliveryFee: 10_000,
  leadTimeDays: 2,
  partsPriceAdjustment: 0,
  sortPriority: 1000,
  standardAsAccepted: true,
  seeded: false,
  deletedAt: null
};

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('buildgraph.token', 'jwt-admin-token'));
  await page.route('**/api/auth/me', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ id: 'admin-1', email: 'admin@example.com', name: 'Demo Admin', role: 'ADMIN' })
  }));
  await page.route('**/api/admin/assembly-requests**', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 })
  }));
});

test('shows pending external technicians and approves an application', async ({ page }) => {
  let approved = false;
  await page.route('**/api/admin/technicians**', (route) => {
    const url = new URL(route.request().url());
    const pendingOnly = url.searchParams.get('verificationStatus') === 'PENDING';
    const items = approved ? [{ ...pendingTechnician, status: 'ACTIVE', verificationStatus: 'APPROVED' }] : [pendingTechnician];
    const visible = pendingOnly ? items.filter((item) => item.verificationStatus === 'PENDING') : items;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: visible, page: 0, size: 20, total: visible.length })
    });
  });
  await page.route('**/api/admin/technicians/external-tech-pending/approve', async (route) => {
    approved = true;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...pendingTechnician, status: 'ACTIVE', verificationStatus: 'APPROVED' })
    });
  });

  await page.goto('/admin/assembly');
  await page.getByRole('button', { name: '기사 관리' }).click();
  await expect(page.getByText('승인 대기 1')).toBeVisible();
  await page.getByText('QA 외부 기사').click();
  await page.getByRole('button', { name: '승인', exact: true }).click();

  await expect.poll(() => approved).toBe(true);
  await expect(page.getByText('승인 대기 0')).toBeVisible();
});
