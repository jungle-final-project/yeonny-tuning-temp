import { expect, test, type Page } from '@playwright/test';

type AiTier = 'budget' | 'balanced' | 'performance';
type PartCategory = 'CPU' | 'MOTHERBOARD' | 'RAM' | 'GPU' | 'STORAGE' | 'PSU' | 'CASE' | 'COOLER';

const categories: PartCategory[] = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER'];
const tierLabels: Record<AiTier, string> = {
  budget: '실속형',
  balanced: '균형형',
  performance: '성능형'
};
const tierShortLabels: Record<AiTier, string> = {
  budget: '가성비',
  balanced: '균형',
  performance: '고성능'
};

function formatBudgetLabel(budgetWon: number) {
  return `${budgetWon / 10_000}만원`;
}

function item(category: PartCategory, tier: AiTier, budgetWon: number, suffix = '') {
  const basePrice = {
    CPU: 320000,
    MOTHERBOARD: 190000,
    RAM: 140000,
    GPU: 780000,
    STORAGE: 150000,
    PSU: 130000,
    CASE: 100000,
    COOLER: 80000
  }[category];
  const tierScale = tier === 'budget' ? 0.88 : tier === 'balanced' ? 1 : 1.18;
  const budgetScale = budgetWon / 2_000_000;
  const price = Math.round((basePrice * tierScale * budgetScale) / 1000) * 1000;
  const name = category === 'GPU' && suffix
    ? `${suffix} RTX 5070 서버 GPU`
    : `${suffix}${category} 서버 추천 ${tierLabels[tier]}`;

  return {
    partId: `part-${category.toLowerCase()}-${tier}${suffix ? '-updated' : ''}`,
    category,
    name,
    manufacturer: category === 'CPU' ? 'AMD' : category === 'GPU' ? 'NVIDIA' : 'BuildGraph',
    quantity: category === 'RAM' ? 2 : 1,
    price,
    note: 'DB 현재가 기준'
  };
}

function build(tier: AiTier, budgetWon: number, appliedPartCategories: PartCategory[] = []) {
  const budgetLabel = formatBudgetLabel(budgetWon);
  const items = categories.map((category) => item(category, tier, budgetWon, appliedPartCategories.includes(category) ? '서버 반영 ' : ''));
  return {
    id: `server-${budgetWon}-${tier}-${appliedPartCategories.join('-').toLowerCase() || 'base'}`,
    tier,
    label: tierShortLabels[tier],
    title: `${budgetLabel} ${tierLabels[tier]}`,
    summary: `${budgetLabel} 예산을 서버 DB/룰 기반으로 계산한 ${tierLabels[tier]} 조합입니다.`,
    totalPrice: items.reduce((sum, next) => sum + next.price * next.quantity, 0),
    badges: [budgetLabel, tierLabels[tier], ...appliedPartCategories.map((category) => `${category} 반영됨`)],
    budgetWon,
    budgetLabel,
    tierLabel: tierLabels[tier],
    appliedPartCategories,
    items,
    toolResults: [
      { tool: 'price', status: 'PASS', confidence: 'HIGH', summary: '저장된 현재가 기준 예산 안에 들어옵니다.' }
    ],
    warnings: [],
    confidence: 'HIGH'
  };
}

function partDetail(partId: string) {
  return {
    id: partId,
    category: partId.includes('case') ? 'CASE' : 'GPU',
    name: partId.includes('case') ? 'AI 추천 케이스' : 'AI 추천 부품',
    manufacturer: 'BuildGraph',
    price: 100000,
    status: 'ACTIVE',
    attributes: {
      shortSpec: 'AI 추천 대표 이미지',
      imageUrl: '/assets/home-banners/pc-build-festa.png'
    },
    externalOffer: {
      imageUrl: '/assets/home-banners/pc-build-festa.png',
      supplierName: 'BuildGraph',
      offerUrl: null,
      lowPrice: 100000,
      source: 'TEST',
      refreshedAt: '2026-06-30T00:00:00Z'
    }
  };
}

function budgetBuilds(budgetWon: number, appliedPartCategories: PartCategory[] = []) {
  return (['budget', 'balanced', 'performance'] as AiTier[]).map((tier) => build(tier, budgetWon, appliedPartCategories));
}

function storedAssistantSession(messageText: string) {
  return {
    messages: [
      {
        id: 'ai-intro',
        role: 'assistant',
        text: '예산은 “200만원 PC 추천”처럼, 부품은 “GPU 추천해줘”처럼 물어보세요. 추천은 서버의 실제 부품 DB와 룰 기반 검증 결과로 계산됩니다.',
        createdAt: '2026-06-30T00:00:00.000Z',
        kind: 'intro'
      },
      {
        id: 'user-existing',
        role: 'user',
        text: messageText,
        createdAt: '2026-07-01T00:00:00.000Z',
        kind: 'general'
      }
    ],
    latestBuilds: [],
    appliedPartPreferences: [],
    updatedAt: '2026-07-01T00:00:00.000Z'
  };
}

async function mockAiBuildChatApi(page: Page) {
  const requests: Array<{ message: string; currentBuilds?: unknown[] }> = [];

  await page.route('**/api/parts/part-case-*', async (route) => {
    const partId = decodeURIComponent(route.request().url().split('/').pop() ?? 'part-case-test');
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(partDetail(partId))
    });
  });

  await page.route('**/api/ai/build-chat', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}') as { message?: string; currentBuilds?: unknown[] };
    const message = body.message ?? '';
    requests.push({ message, currentBuilds: body.currentBuilds });

    if (/gpu/i.test(message) || message.includes('GPU')) {
      const baseBudget = 2_000_000;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          answerType: 'PART',
          message: 'GPU 추천 후보 3개를 DB 현재가 기준으로 정리했고 최신 추천 조합에도 반영했습니다.',
          builds: budgetBuilds(baseBudget, ['GPU']),
          partRecommendation: {
            category: 'GPU',
            label: 'GPU',
            intro: 'DB 현재가 기준 GPU 후보입니다.',
            options: [
              item('GPU', 'budget', baseBudget, '서버 가성비 '),
              item('GPU', 'balanced', baseBudget, '서버 균형 '),
              item('GPU', 'performance', baseBudget, '서버 고성능 ')
            ]
          },
          warnings: []
        })
      });
      return;
    }

    const budgetWon = message.includes('300') ? 3_000_000 : 2_000_000;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'BUDGET',
        message: `${formatBudgetLabel(budgetWon)} 예산 기준으로 실속형, 균형형, 성능형 3개 조합을 DB/룰 기반으로 계산했습니다.`,
        builds: budgetBuilds(budgetWon),
        partRecommendation: null,
        warnings: []
      })
    });
  });

  return requests;
}

async function mockHomePartsApi(page: Page) {
  const homeParts = [
    { id: 'home-cpu-ryzen7', category: 'CPU', query: 'Ryzen 7', name: 'Home Ryzen 7 CPU', imageUrl: 'https://example.test/popular-ryzen7.png', price: 420000 },
    { id: 'home-board-b850', category: 'MOTHERBOARD', query: 'B850', name: 'Home B850 Motherboard', imageUrl: 'https://example.test/home-b850.png', price: 280000 },
    { id: 'home-ram-ddr5-32', category: 'RAM', query: 'DDR5 32GB', name: 'Home DDR5 32GB RAM', imageUrl: 'https://example.test/popular-ddr5.png', price: 128000 },
    { id: 'home-gpu-rtx5070', category: 'GPU', query: 'RTX 5070', name: 'Home RTX 5070 GPU', imageUrl: 'https://example.test/popular-rtx5070.png', price: 890000 },
    { id: 'home-ssd-nvme-1tb', category: 'STORAGE', query: 'NVMe 1TB', name: 'Home NVMe 1TB SSD', imageUrl: 'https://example.test/home-nvme-1tb.png', price: 150000 },
    { id: 'home-psu-850', category: 'PSU', query: '850W', name: 'Home ATX 3.1 850W PSU', imageUrl: 'https://example.test/popular-psu.png', price: 165000 },
    { id: 'home-psu-850-popular', category: 'PSU', query: 'ATX 3.1 850W', name: 'Home ATX 3.1 850W PSU', imageUrl: 'https://example.test/popular-psu.png', price: 165000 },
    { id: 'home-case-frame', category: 'CASE', query: 'FRAME 4000D', name: 'Home FRAME 4000D Case', imageUrl: 'https://example.test/case-home-1.png', price: 180000 },
    { id: 'home-cooler-phantom', category: 'COOLER', query: 'Phantom Spirit', name: 'Home Phantom Spirit Cooler', imageUrl: 'https://example.test/home-phantom.png', price: 80000 },
    { id: 'home-cpu-ryzen9', category: 'CPU', query: 'Ryzen 9', name: 'Home Ryzen 9 CPU', imageUrl: 'https://example.test/home-ryzen9.png', price: 620000 },
    { id: 'home-board-x870e', category: 'MOTHERBOARD', query: 'X870E', name: 'Home X870E Motherboard', imageUrl: 'https://example.test/home-x870e.png', price: 540000 },
    { id: 'home-ram-ddr5-64', category: 'RAM', query: 'DDR5 64GB', name: 'Home DDR5 64GB RAM', imageUrl: 'https://example.test/home-ddr5-64.png', price: 240000 },
    { id: 'home-gpu-rtx5070ti', category: 'GPU', query: 'RTX 5070 Ti', name: 'Home RTX 5070 Ti GPU', imageUrl: 'https://example.test/home-rtx5070ti.png', price: 1390000 },
    { id: 'home-ssd-nvme-2tb', category: 'STORAGE', query: 'NVMe 2TB', name: 'Home NVMe 2TB SSD', imageUrl: 'https://example.test/home-nvme-2tb.png', price: 230000 },
    { id: 'home-psu-1000', category: 'PSU', query: '1000W', name: 'Home 1000W PSU', imageUrl: 'https://example.test/home-1000w.png', price: 245000 },
    { id: 'home-case-h9', category: 'CASE', query: 'H9 Flow', name: 'Home H9 Flow Case', imageUrl: 'https://example.test/case-home-2.png', price: 230000 },
    { id: 'home-cooler-liquid', category: 'COOLER', query: 'Liquid Freezer III', name: 'Home Liquid Freezer III Cooler', imageUrl: 'https://example.test/home-liquid.png', price: 191000 },
    { id: 'home-case-light-base', category: 'CASE', query: 'LIGHT BASE 900', name: 'Home LIGHT BASE 900 Case', imageUrl: 'https://example.test/case-home-3.png', price: 220000 },
    { id: 'home-cooler-dark-rock', category: 'COOLER', query: 'Dark Rock Pro 5', name: 'Home Dark Rock Pro 5 Cooler', imageUrl: 'https://example.test/home-dark-rock.png', price: 139000 }
  ].map((part) => ({
    id: part.id,
    category: part.category,
    name: part.name,
    manufacturer: 'BuildGraph',
    price: part.price,
    status: 'ACTIVE',
    attributes: {
      shortSpec: part.query
    },
    externalOffer: {
      imageUrl: part.imageUrl,
      supplierName: 'Naver Store',
      offerUrl: null,
      lowPrice: part.price,
      source: 'NAVER_SHOPPING_SEARCH',
      refreshedAt: '2026-07-01T00:00:00Z'
    }
  }));

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    const category = url.searchParams.get('category');
    const query = url.searchParams.get('q');
    const matchedParts = homeParts.filter((part) => part.category === category && (!query || part.attributes.shortSpec === query));
    if (url.pathname === '/api/parts' && matchedParts.length > 0) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: matchedParts,
          page: 0,
          size: matchedParts.length,
          total: matchedParts.length
        })
      });
      return;
    }

    await route.fallback();
  });
}

async function openHomeAsUser(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-1004',
      email: 'user@example.com',
      name: '테스트 사용자',
      role: 'USER'
    }));
    sessionStorage.clear();
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'user-1004',
        email: 'user@example.com',
        name: '테스트 사용자',
        role: 'USER'
      })
    });
  });
  await mockHomePartsApi(page);
  await page.goto('/');
}

async function mockSelfQuoteApis(page: Page) {
  const applyRequests: unknown[] = [];
  const draftPartNames: Record<string, string> = {
    'home-cpu-ryzen7': 'Home Ryzen 7 CPU',
    'home-board-b850': 'Home B850 Motherboard',
    'home-ram-ddr5-32': 'Home DDR5 32GB RAM',
    'home-gpu-rtx5070': 'Home RTX 5070 GPU',
    'home-ssd-nvme-1tb': 'Home NVMe 1TB SSD',
    'home-psu-850': 'Home ATX 3.1 850W PSU',
    'home-case-frame': 'Home FRAME 4000D Case',
    'home-cooler-phantom': 'Home Phantom Spirit Cooler'
  };
  const emptyDraft = {
    id: 'draft-home-ai-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [],
    totalPrice: 0,
    itemCount: 0
  };
  let draft: typeof emptyDraft | {
    id: string;
    status: string;
    name: string;
    items: Array<{
      id: string;
      partId: string;
      category: string;
      name: string;
      manufacturer: string;
      quantity: number;
      unitPriceAtAdd: number;
      currentPrice: number;
      lineTotal: number;
      attributes: Record<string, never>;
    }>;
    totalPrice: number;
    itemCount: number;
  } = emptyDraft;

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/apply-ai-build')) {
      const body = JSON.parse(route.request().postData() ?? '{}') as { items?: Array<{ partId: string; category: string; quantity: number }> };
      applyRequests.push(body);
      const knownAiItems = [
        ...budgetBuilds(2_000_000),
        ...budgetBuilds(2_000_000, ['GPU']),
        ...budgetBuilds(3_000_000),
        ...budgetBuilds(3_000_000, ['GPU'])
      ].flatMap((build) => build.items);
      const items = (body.items ?? []).map((next, index) => ({
        id: `applied-${index}`,
        partId: next.partId,
        category: next.category,
        name: draftPartNames[next.partId] ?? (next.category === 'GPU' ? '서버 반영 RTX 5070 서버 GPU' : `${next.category} 적용 부품`),
        manufacturer: next.category === 'GPU' ? 'NVIDIA' : 'BuildGraph',
        quantity: next.quantity,
        unitPriceAtAdd: knownAiItems.find((item) => item.partId === next.partId)?.price ?? 100000 + index,
        currentPrice: knownAiItems.find((item) => item.partId === next.partId)?.price ?? 100000 + index,
        lineTotal: (knownAiItems.find((item) => item.partId === next.partId)?.price ?? 100000 + index) * next.quantity,
        attributes: {}
      }));
      draft = {
        id: 'draft-home-ai-test',
        status: 'ACTIVE',
        name: '셀프 견적',
        items,
        totalPrice: items.reduce((sum, next) => sum + next.lineTotal, 0),
        itemCount: items.reduce((sum, next) => sum + next.quantity, 0)
      };
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(draft)
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    const detailMatch = url.pathname.match(/\/api\/parts\/([^/]+)$/);
    if (detailMatch) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(partDetail(decodeURIComponent(detailMatch[1])))
      });
      return;
    }

    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-gpu-balanced-updated',
          partName: '서버 반영 RTX 5070 서버 GPU',
          currentPrice: 890000,
          days: 3650,
          source: 'NAVER_SHOPPING_SEARCH',
          items: [],
          summary: {
            sampleCount: 0,
            currentPrice: 890000,
            minPrice: 890000,
            maxPrice: 890000,
            firstPrice: 890000,
            lastPrice: 890000,
            changeAmount: 0,
            changeRatePercent: 0
          }
        })
      });
      return;
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [],
        page: 0,
        size: 20,
        total: 0
      })
    });
  });

  return { applyRequests };
}

test('renders a single shopping home without the old hero prompt flow', async ({ page }) => {
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await expect(main.getByRole('textbox', { name: '원하는 PC 사양 입력' })).toHaveCount(0);
  await expect(main.getByRole('img', { name: 'PC Build Festa 프리미엄 PC 완성 광고' })).toBeVisible();
  await expect(main.getByRole('heading', { name: '부품 바로가기' })).toBeVisible();
  await expect(main.getByRole('heading', { name: '추천상품' })).toBeVisible();
  await expect(main.getByRole('tab', { name: '인기상품' })).toHaveAttribute('aria-selected', 'true');
  await expect(main.getByRole('tab', { name: 'AI 추천상품' })).toHaveAttribute('aria-selected', 'false');
  await expect(main.getByText('QHD 게이밍 추천팩')).toBeVisible();
  await expect(main.getByText('2,293,000원')).toBeVisible();
  await expect(main.getByRole('img', { name: /Home FRAME 4000D Case/ })).toBeVisible();
  await main.getByRole('tab', { name: 'AI 추천상품' }).click();
  await expect(main.getByText('AI에게 예산이나 부품을 물어보면 추천상품 3개가 여기에 표시됩니다.')).toBeVisible();
  await expect(main.getByRole('heading', { name: '인기 부품 랭킹' })).toBeVisible();
  await expect(main.getByRole('img', { name: /Home RTX 5070 GPU/ })).toBeVisible();
  await expect(main.getByRole('link', { name: '인기 부품 1번 보기' })).toHaveAttribute('href', '/parts/home-gpu-rtx5070');

  for (const label of ['CPU', '메인보드', 'RAM', 'GPU', 'SSD', '파워', '케이스', '쿨러']) {
    await expect(main.getByRole('link', { name: label, exact: true })).toBeVisible();
  }
});

test('selects a featured recommendation and applies every build part to self quote', async ({ page }) => {
  const { applyRequests } = await mockSelfQuoteApis(page);
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await expect(main.getByRole('img', { name: /Home FRAME 4000D Case/ })).toBeVisible();
  await main.getByRole('button', { name: /QHD/ }).click();

  await expect.poll(() => applyRequests.length).toBe(1);
  const request = applyRequests[0] as { buildId?: string; items?: Array<{ partId: string; category: string; quantity: number }> };
  expect(request.buildId).toBe('home-featured-qhd-gaming');
  expect(request.items?.map((item) => item.category)).toEqual(['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER']);
  expect(request.items).toContainEqual({ partId: 'home-gpu-rtx5070', category: 'GPU', quantity: 1 });
  expect(request.items).toContainEqual({ partId: 'home-case-frame', category: 'CASE', quantity: 1 });
  await expect(page).toHaveURL('/self-quote');
  await expect(page.getByText('Home RTX 5070 GPU')).toBeVisible();
  await expect(page.getByText('Home FRAME 4000D Case')).toBeVisible();
});

test('chatbot uses build-chat API and updates latest home AI recommendations', async ({ page }) => {
  const buildChatRequests = await mockAiBuildChatApi(page);
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCount(0);
  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();

  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('200만원 PC 추천');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatRequests.length).toBe(1);
  expect(buildChatRequests[0].message).toBe('200만원 PC 추천');
  await expect(main.getByRole('tab', { name: 'AI 추천상품' })).toHaveAttribute('aria-selected', 'true');
  await expect(main.getByTestId('home-ai-recommendations')).toContainText('200만원 실속형');
  await expect(main.getByTestId('home-ai-recommendations')).toContainText('200만원 균형형');
  await expect(main.getByTestId('home-ai-recommendations').getByRole('img', { name: /케이스 이미지/ })).toHaveCount(0);
  await expect(main.getByTestId('home-ai-recommendations')).toContainText('AI 추천');
  await expect(main.getByTestId('home-ai-recommendations')).toContainText('호환성 통과');
  await expect(page.getByTestId('ai-chat-messages')).toContainText('200만원 예산 기준');

  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('300만원 PC 추천');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatRequests.length).toBe(2);
  await expect(page.getByTestId('ai-chat-messages')).toContainText('200만원 예산 기준');
  await expect(page.getByTestId('ai-chat-messages')).toContainText('300만원 예산 기준');
  await expect(main.getByTestId('home-ai-recommendations')).toContainText('300만원 실속형');
  await expect(main.getByTestId('home-ai-recommendations')).toContainText('300만원 성능형');
  await expect(main.getByTestId('home-ai-recommendations')).not.toContainText('200만원 균형형');
});

test('chatbot part questions show backend parts and apply them to home AI builds', async ({ page }) => {
  const buildChatRequests = await mockAiBuildChatApi(page);
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('200만원 PC 추천');
  await page.getByRole('button', { name: '질문 보내기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('GPU 추천해줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatRequests.length).toBe(2);
  expect(buildChatRequests[1].message).toBe('GPU 추천해줘');
  expect(buildChatRequests[1].currentBuilds?.length).toBe(3);
  await expect(page.getByTestId('ai-chat-messages')).toContainText('GPU 추천 후보');
  await expect(page.getByTestId('ai-chat-messages')).toContainText('서버 균형 RTX 5070 서버 GPU');
  await expect(main.getByTestId('home-ai-recommendations').getByRole('img', { name: /케이스 이미지/ })).toHaveCount(0);
  await expect(main.getByTestId('home-ai-recommendations')).toContainText('AI 추천');
  await expect(main.getByTestId('home-ai-recommendations')).toContainText('호환성 통과');
});

test('chatbot only shows the current user scoped assistant session', async ({ page }) => {
  await page.addInitScript(({ otherUserSession, legacySession }) => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-b',
      email: 'test@test.test',
      name: '테스트 사용자 B',
      role: 'USER'
    }));
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-a', otherUserSession);
    sessionStorage.setItem('buildgraph.ai.assistantSession', legacySession);
  }, {
    otherUserSession: JSON.stringify(storedAssistantSession('kmb5037@naver.com 계정의 이전 구매 상담')),
    legacySession: JSON.stringify(storedAssistantSession('legacy global key에 남아 있던 이전 상담'))
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'user-b',
        email: 'test@test.test',
        name: '테스트 사용자 B',
        role: 'USER'
      })
    });
  });
  await mockHomePartsApi(page);
  await page.goto('/');

  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();

  await expect(page.getByTestId('ai-chat-messages')).toContainText('예산은');
  await expect(page.getByTestId('ai-chat-messages')).not.toContainText('kmb5037@naver.com 계정의 이전 구매 상담');
  await expect(page.getByTestId('ai-chat-messages')).not.toContainText('legacy global key에 남아 있던 이전 상담');
});

test('chatbot asks for login when token disappears before submit', async ({ page }) => {
  let buildChatCalls = 0;
  await openHomeAsUser(page);
  await page.route('**/api/ai/build-chat', async (route) => {
    buildChatCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'GENERAL',
        message: '호출되면 안 되는 응답입니다.',
        builds: [],
        partRecommendation: null,
        warnings: []
      })
    });
  });

  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await page.evaluate(() => {
    localStorage.removeItem('buildgraph.token');
  });
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('토큰 삭제 확인 질문');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect(page.getByRole('alert')).toContainText('로그인이 필요합니다. 다시 로그인해 주세요.');
  await expect(page.getByRole('alert')).not.toContainText('AI 추천 API 호출에 실패했습니다.');
  await expect(page.getByTestId('ai-chat-messages')).not.toContainText('토큰 삭제 확인 질문');
  expect(buildChatCalls).toBe(0);
});

test('chatbot maps build-chat 401 to login required instead of generic failure', async ({ page }) => {
  let buildChatCalls = 0;
  await openHomeAsUser(page);
  await page.route('**/api/ai/build-chat', async (route) => {
    buildChatCalls += 1;
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'UNAUTHORIZED',
        message: '로그인이 필요합니다.'
      })
    });
  });

  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('로그인 만료 확인 질문');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatCalls).toBe(1);
  await expect(page.getByRole('alert')).toContainText('로그인이 필요합니다. 다시 로그인해 주세요.');
  await expect(page.getByRole('alert')).not.toContainText('AI 추천 API 호출에 실패했습니다.');
  await expect(page.getByTestId('ai-chat-messages')).not.toContainText('로그인 만료 확인 질문');
});

test('selects a home AI recommendation through batch API and shows applied cart in self quote', async ({ page }) => {
  await mockAiBuildChatApi(page);
  const { applyRequests } = await mockSelfQuoteApis(page);
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('200만원 안에서 QHD 게임용 PC 추천해줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('GPU 추천해줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();
  await main.getByTestId('home-ai-recommendations').getByRole('button', { name: /200만원 균형형 셀프 견적으로 적용/ }).click();

  await expect.poll(() => applyRequests.length).toBe(1);
  const expectedTotal = budgetBuilds(2_000_000, ['GPU'])[1].totalPrice.toLocaleString();
  expect((applyRequests[0] as { conflictPolicy?: string; items?: unknown[] }).conflictPolicy).toBe('REPLACE');
  expect((applyRequests[0] as { items?: unknown[] }).items).toHaveLength(8);
  await expect(page).toHaveURL('/self-quote');
  await expect(page.getByTestId('ai-selected-build-panel')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'AI 선택 조합' })).toBeVisible();
  await expect(page.getByText('200만원 균형형')).toBeVisible();
  await expect(page.getByTestId('ai-selected-build-panel').getByText(`${expectedTotal}원`)).toBeVisible();
  await expect(page.getByText(`${expectedTotal}원`)).toHaveCount(2);
  await expect(page.getByText('GPU 반영됨')).toBeVisible();
  await expect(page.getByText('서버 반영 RTX 5070 서버 GPU').first()).toBeVisible();
  await expect(page.getByRole('heading', { name: '견적 장바구니', exact: true })).toBeVisible();
});

test('keeps shared header and navigation destinations unchanged', async ({ page }) => {
  await openHomeAsUser(page);
  const header = page.locator('header');
  const nav = page.getByRole('navigation');

  await expect(header.getByRole('link', { name: 'AI 견적' })).toHaveCount(0);
  await expect(header.getByRole('link', { name: '내 견적함' })).toHaveAttribute('href', '/my/quotes');
  await expect(header.getByRole('link', { name: 'AS 접수' })).toHaveAttribute('href', '/support/new');
  await expect(nav.getByRole('link', { name: '홈' })).toHaveAttribute('href', '/');
  await expect(nav.getByRole('link', { name: '셀프 견적' })).toHaveAttribute('href', '/self-quote');
  await expect(nav.getByRole('link', { name: '관리자' })).toHaveCount(0);
});

test('keeps the unified home usable on mobile width', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockAiBuildChatApi(page);
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await expect(main.getByRole('img', { name: 'PC Build Festa 프리미엄 PC 완성 광고' })).toBeVisible();
  await expect(main.getByRole('heading', { name: '부품 바로가기' })).toBeVisible();
  await expect(main.getByRole('tab', { name: '인기상품' })).toBeVisible();
  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await expect(page.getByTestId('ai-chatbot-panel')).toBeVisible();

  const hasBodyOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  expect(hasBodyOverflow).toBe(false);
});
