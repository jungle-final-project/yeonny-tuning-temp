import { expect, test, type Page } from '@playwright/test';

type MockDraftItem = {
  id: string;
  partId: string;
  category: string;
  name: string;
  manufacturer: string;
  quantity: number;
  unitPriceAtAdd: number;
  currentPrice: number;
  lineTotal: number;
  attributes: Record<string, unknown>;
};

const gpuPart = {
  id: 'part-all-gpu',
  category: 'GPU',
  name: 'AllParts RTX GPU',
  manufacturer: 'NVIDIA',
  price: 890000,
  status: 'ACTIVE',
  attributes: { shortSpec: 'RTX 테스트 GPU' },
  externalOffer: {
    imageUrl: 'https://example.test/all-gpu.png',
    supplierName: '전체부품몰',
    offerUrl: 'https://example.test/all-gpu',
    lowPrice: 890000,
    source: 'NAVER_SHOPPING_SEARCH'
  }
};

const ramPart = {
  id: 'part-all-ram',
  category: 'RAM',
  name: 'AllParts DDR5 RAM',
  manufacturer: 'BuildGraph',
  price: 120000,
  status: 'ACTIVE',
  attributes: { shortSpec: 'DDR5 32GB' },
  externalOffer: {
    imageUrl: 'https://example.test/all-ram.png',
    supplierName: '메모리테스트몰',
    offerUrl: 'https://example.test/all-ram',
    lowPrice: 120000,
    source: 'NAVER_SHOPPING_SEARCH'
  }
};

function draftItem(part: typeof gpuPart | typeof ramPart, quantity = 1): MockDraftItem {
  return {
    id: `draft-${part.id}`,
    partId: part.id,
    category: part.category,
    name: part.name,
    manufacturer: part.manufacturer,
    quantity,
    unitPriceAtAdd: part.price,
    currentPrice: part.price,
    lineTotal: part.price * quantity,
    attributes: part.attributes
  };
}

function draft(items: MockDraftItem[]) {
  return {
    id: 'draft-all-parts',
    status: 'ACTIVE',
    name: '전체 부품 테스트 견적',
    items,
    totalPrice: items.reduce((sum, item) => sum + item.lineTotal, 0),
    itemCount: items.reduce((sum, item) => sum + item.quantity, 0)
  };
}

async function loginAsUser(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-parts',
      email: 'parts@example.com',
      name: '부품 사용자',
      role: 'USER'
    }));
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'user-parts',
        email: 'parts@example.com',
        name: '부품 사용자',
        role: 'USER'
      })
    });
  });
}

test('renders the all parts page and keeps filter state in the /parts URL', async ({ page }) => {
  await loginAsUser(page);
  const partRequests: URL[] = [];

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(draft([])) });
  });
  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname !== '/api/parts') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], summary: { sampleCount: 0 } }) });
      return;
    }
    partRequests.push(url);
    const category = url.searchParams.get('category');
    const query = url.searchParams.get('q') ?? '';
    const items = [gpuPart, ramPart].filter((part) => {
      if (category && part.category !== category) return false;
      if (query && !part.name.includes(query) && !part.attributes.shortSpec.includes(query)) return false;
      return true;
    });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items, page: 0, size: 20, total: items.length })
    });
  });

  await page.goto('/parts');

  await expect(page.getByRole('heading', { name: '전체 부품', exact: true })).toBeVisible();
  await expect(page.getByText('AllParts RTX GPU')).toBeVisible();
  await expect(page.getByText('AllParts DDR5 RAM')).toBeVisible();

  await page.getByRole('button', { name: 'GPU', exact: true }).click();
  await expect(page).toHaveURL('/parts?category=GPU');
  await expect(page.getByText('AllParts RTX GPU')).toBeVisible();
  await expect(page.getByText('AllParts DDR5 RAM')).toHaveCount(0);

  await page.getByRole('textbox', { name: '부품 검색' }).fill('RTX');
  await expect(page).toHaveURL(/\/parts\?category=GPU&q=RTX/);
  await expect.poll(() => partRequests[partRequests.length - 1]?.searchParams.get('q')).toBe('RTX');
});

test('adds, updates, and removes quote draft items from the all parts page', async ({ page }) => {
  await loginAsUser(page);
  let currentDraft = draft([draftItem(ramPart)]);
  const mutations: Array<{ method: string; path: string; body: unknown }> = [];

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    if (method === 'PUT' && url.pathname.endsWith('/items/part-all-gpu')) {
      const body = JSON.parse(route.request().postData() ?? '{}') as { quantity: number };
      mutations.push({ method, path: url.pathname, body });
      currentDraft = draft([...currentDraft.items, draftItem(gpuPart, body.quantity)]);
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(currentDraft) });
      return;
    }
    if (method === 'PATCH' && url.pathname.endsWith('/items/part-all-ram')) {
      const body = JSON.parse(route.request().postData() ?? '{}') as { quantity: number };
      mutations.push({ method, path: url.pathname, body });
      currentDraft = draft(currentDraft.items.map((item) => item.partId === 'part-all-ram' ? draftItem(ramPart, body.quantity) : item));
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(currentDraft) });
      return;
    }
    if (method === 'DELETE' && url.pathname.endsWith('/items/part-all-ram')) {
      mutations.push({ method, path: url.pathname, body: null });
      currentDraft = draft(currentDraft.items.filter((item) => item.partId !== 'part-all-ram'));
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(currentDraft) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(currentDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname !== '/api/parts') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], summary: { sampleCount: 0 } }) });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [gpuPart, ramPart], page: 0, size: 20, total: 2 })
    });
  });

  await page.goto('/parts');

  await page.getByRole('button', { name: 'AllParts RTX GPU 견적 담기' }).click();
  await expect.poll(() => mutations.some((entry) => entry.method === 'PUT' && entry.path.endsWith('/items/part-all-gpu'))).toBe(true);

  await page.getByRole('button', { name: 'AllParts DDR5 RAM 수량 증가' }).click();
  await expect.poll(() => mutations.some((entry) => entry.method === 'PATCH' && entry.path.endsWith('/items/part-all-ram') && (entry.body as { quantity?: number }).quantity === 2)).toBe(true);

  await page.getByRole('button', { name: 'AllParts DDR5 RAM 견적에서 제거' }).first().click();
  await expect.poll(() => mutations.some((entry) => entry.method === 'DELETE' && entry.path.endsWith('/items/part-all-ram'))).toBe(true);
});
