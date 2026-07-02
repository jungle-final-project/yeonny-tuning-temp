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

function buildGraphResponse(mode = 'BUILD_OVERVIEW') {
  return {
    mode,
    summary: 'RTX 5070 선택으로 PSU와 케이스 조건을 함께 확인해야 합니다.',
    nodes: [
      { id: 'part-GPU', type: 'PART', category: 'GPU', label: 'RTX 5070', status: 'PASS', detail: '250W · 길이 304mm' },
      { id: 'part-PSU', type: 'PART', category: 'PSU', label: '850W 파워', status: 'WARN', detail: '정격 850W' },
      { id: 'part-CASE', type: 'PART', category: 'CASE', label: '미들타워 케이스', status: 'PASS', detail: 'GPU 최대 320mm' },
      { id: 'constraint-power', type: 'CONSTRAINT', category: 'PSU', label: '정격 850W', status: 'WARN', detail: '권장 출력 750W / 현재 파워 850W' },
      { id: 'constraint-size', type: 'CONSTRAINT', category: 'CASE', label: '미들타워 케이스', status: 'PASS', detail: 'GPU 길이와 쿨러 높이가 케이스 제약 안에 있습니다.' },
      { id: 'constraint-compatibility', type: 'CONSTRAINT', category: 'MOTHERBOARD', label: 'B650 Board', status: 'PASS', detail: '소켓과 메모리 규격을 확인합니다.' }
    ],
    edges: [
      {
        id: 'edge-gpu-psu-power',
        source: 'part-GPU',
        target: 'part-PSU',
        type: 'AFFECTS',
        status: 'WARN',
        label: '전력 여유 100W',
        summary: '권장 출력 750W / 현재 파워 850W입니다. 여유 100W로 장착은 가능하지만 권장 여유가 낮습니다.'
      },
      {
        id: 'edge-gpu-case-length',
        source: 'part-GPU',
        target: 'part-CASE',
        type: 'REQUIRES',
        status: 'PASS',
        label: '길이 간섭 주의',
        summary: 'GPU 길이 304mm / 케이스 허용 320mm입니다. 여유 16mm로 장착은 가능하지만 간섭을 주의해야 합니다.'
      }
    ],
    focusNodeIds: ['part-GPU', 'part-PSU'],
    insights: [
      {
        id: 'insight-power',
        status: 'WARN',
        title: '파워 여유 확인',
        description: '여유 100W로 장착은 가능하지만 권장 여유가 낮습니다.',
        relatedNodeIds: ['part-GPU', 'part-PSU']
      }
    ],
    toolResults: [
      { tool: 'power', status: 'WARN', confidence: 'MEDIUM', summary: 'PSU 정격 출력 여유를 확인해야 합니다.' }
    ]
  };
}

async function mockBuildGraphApi(page: Page) {
  const requests: unknown[] = [];
  await page.route('**/api/build-graphs/resolve', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}');
    requests.push(body);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(buildGraphResponse(body.focus?.mode ?? 'BUILD_OVERVIEW'))
    });
  });
  return requests;
}

async function mockCompatibleCandidatesApi(page: Page) {
  const requests: Array<{ source?: string; category?: string; items?: unknown[] }> = [];
  await page.route('**/api/parts/compatible-candidates', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}') as { source?: string; category?: string; items?: unknown[] };
    requests.push(body);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        category: body.category ?? 'GPU',
        items: [
          {
            part: {
              id: 'candidate-gpu-pass',
              category: 'GPU',
              name: 'RTX 5070 Ti 호환 후보',
              manufacturer: 'NVIDIA',
              price: 990000,
              status: 'ACTIVE',
              attributes: { wattage: 285, lengthMm: 310 },
              externalOffer: {
                imageUrl: 'https://example.test/candidate-rtx5070ti.png',
                supplierName: '후보테스트몰',
                offerUrl: null,
                lowPrice: 990000,
                source: 'NAVER_SHOPPING_SEARCH'
              }
            },
            status: 'PASS',
            statusLabel: '여유 있음',
            summary: '현재 PSU/케이스 기준 장착 가능합니다.',
            checkedTools: ['power', 'size', 'performance']
          },
          {
            part: {
              id: 'candidate-gpu-warn',
              category: 'GPU',
              name: 'RTX 5080 Compact 호환 후보',
              manufacturer: 'NVIDIA',
              price: 1490000,
              status: 'ACTIVE',
              attributes: { wattage: 360, lengthMm: 330 }
            },
            status: 'WARN',
            statusLabel: '간섭 주의',
            summary: '파워 여유가 낮아 850W 이상을 권장합니다.',
            checkedTools: ['power', 'size', 'performance']
          }
        ],
        rejectedCount: 1,
        warnings: []
      })
    });
  });
  return requests;
}

async function dragFloatingGraphResizeHandle(page: Page, deltaX: number, deltaY: number) {
  const handle = page.getByTestId('floating-graph-resize-handle');
  const box = await handle.boundingBox();
  if (!box) {
    throw new Error('floating graph resize handle is not visible');
  }
  const startX = box.x + box.width / 2;
  const startY = box.y + box.height / 2;
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  await page.mouse.move(startX + deltaX, startY + deltaY, { steps: 8 });
  await page.mouse.up();
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

async function mockSelfQuoteApis(
  page: Page,
  options: { staleGetAfterApply?: boolean; getDelayAfterApplyMs?: number } = {}
) {
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
      const appliedDraft = {
        id: 'draft-home-ai-test',
        status: 'ACTIVE',
        name: '셀프 견적',
        items,
        totalPrice: items.reduce((sum, next) => sum + next.lineTotal, 0),
        itemCount: items.reduce((sum, next) => sum + next.quantity, 0)
      };
      if (!options.staleGetAfterApply) {
        draft = appliedDraft;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(appliedDraft)
      });
      return;
    }

    if (options.getDelayAfterApplyMs && applyRequests.length > 0 && route.request().method() === 'GET') {
      await new Promise((resolve) => setTimeout(resolve, options.getDelayAfterApplyMs));
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
  const buildGraphRequests = await mockBuildGraphApi(page);
  const compatibleCandidateRequests = await mockCompatibleCandidatesApi(page);
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
  await expect(main.getByTestId('build-dependency-graph')).toContainText('견적 관계도');
  await expect(main.getByTestId('build-dependency-graph')).toContainText('파워 여유 확인');
  await expect(main.getByTestId('build-dependency-graph')).toContainText('여유 100W로 장착은 가능하지만 권장 여유가 낮습니다.');
  await expect(main.getByTestId('build-dependency-graph')).toContainText('호환됨');
  await expect(main.getByTestId('build-dependency-graph')).not.toContainText('여유 있음');
  await expect(main.getByTestId('build-dependency-graph')).toContainText('간섭 주의');
  await expect(main.getByTestId('build-dependency-graph')).not.toContainText('250W · 길이 304mm');
  await expect(main.getByTestId('build-dependency-graph')).not.toContainText('권장 출력 750W / 현재 파워 850W');
  await expect(main.getByTestId('build-dependency-graph')).toContainText('정격 850W');
  await expect(main.getByTestId('build-dependency-graph')).toContainText('B650 Board');
  await expect(main.getByTestId('build-dependency-graph')).toContainText('미들타워 케이스');
  await expect(main.getByTestId('build-dependency-graph')).not.toContainText('기본 호환성');
  await expect(main.getByTestId('build-dependency-graph')).not.toContainText('장착 규격');
  await expect(main.getByTestId('build-dependency-graph')).not.toContainText('PASS');
  await expect(main.getByTestId('build-dependency-graph')).not.toContainText('WARN');
  await expect.poll(() => buildGraphRequests.length).toBeGreaterThan(0);
  expect((buildGraphRequests[0] as { source?: string; items?: unknown[] }).source).toBe('AI_BUILD');
  expect((buildGraphRequests[0] as { items?: unknown[] }).items?.length).toBe(8);
  const graphCanvas = main.getByTestId('graph-flow-canvas');
  await graphCanvas.scrollIntoViewIfNeeded();
  await expect(page.getByTestId('floating-dependency-graph')).toHaveCount(0);
  await graphCanvas.getByText('RTX 5070', { exact: true }).click();
  const graphPaneBox = await graphCanvas.locator('.react-flow').boundingBox();
  expect(graphPaneBox?.height).toBeGreaterThanOrEqual(520);
  const gpuGraphNode = graphCanvas.locator('.react-flow__node').filter({ hasText: 'RTX 5070' }).first();
  await expect(gpuGraphNode).toHaveClass(/buildgraph-flow-node/);
  await expect(gpuGraphNode).toHaveCSS('border-radius', '50%');
  await expect(gpuGraphNode.locator('.buildgraph-node-category-label')).toHaveText('GPU');
  await expect(gpuGraphNode.locator('.buildgraph-node-main-label')).toContainText('RTX 5070');
  await expect(gpuGraphNode.locator('.buildgraph-node-status-label')).toHaveText('호환됨');
  const gpuGraphNodeBox = await gpuGraphNode.boundingBox();
  expect(gpuGraphNodeBox).not.toBeNull();
  expect(Math.abs((gpuGraphNodeBox?.width ?? 0) - (gpuGraphNodeBox?.height ?? 0))).toBeLessThanOrEqual(2);
  await expect(gpuGraphNode.locator('.buildgraph-node-status-orb')).toHaveCount(0);
  const graphEdgePath = graphCanvas.locator('.react-flow__edge.buildgraph-flow-edge .react-flow__edge-path').first();
  await expect(graphEdgePath).toHaveCSS('stroke-linecap', 'round');
  await expect(graphEdgePath).toHaveCSS('stroke-width', '2px');
  const candidatePanel = graphCanvas.getByTestId('graph-node-candidate-panel');
  await expect(candidatePanel).toContainText('선택한 부품 상세');
  await expect(candidatePanel).toContainText('250W · 길이 304mm');
  await expect(candidatePanel).toContainText('호환 후보');
  await expect(candidatePanel).toContainText('RTX 5070 Ti 호환 후보');
  await expect(candidatePanel).toContainText('읽기 전용');
  await expect(candidatePanel).not.toContainText('담기/교체');
  await expect(candidatePanel.getByRole('img', { name: 'RTX 5070 Ti 호환 후보 제품 사진' })).toBeVisible();
  await expect(candidatePanel.getByRole('img', { name: 'RTX 5080 Compact 호환 후보 사진 없음' })).toBeVisible();
  await candidatePanel.getByRole('button', { name: 'RTX 5070 Ti 호환 후보 사진 확대' }).click();
  const imageDialog = page.getByRole('dialog', { name: 'RTX 5070 Ti 호환 후보 사진 확대' });
  await expect(imageDialog).toBeVisible();
  await expect(imageDialog.getByRole('img', { name: 'RTX 5070 Ti 호환 후보 확대 이미지' })).toBeVisible();
  await expect(imageDialog).toContainText('NVIDIA · 990,000원');
  await page.keyboard.press('Escape');
  await expect(imageDialog).toHaveCount(0);
  await expect.poll(() => compatibleCandidateRequests.length).toBe(1);
  expect(compatibleCandidateRequests[0].source).toBe('AI_BUILD');
  expect(compatibleCandidateRequests[0].category).toBe('GPU');
  expect(compatibleCandidateRequests[0].items?.length).toBe(8);
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  const floatingGraph = page.getByTestId('floating-dependency-graph');
  await expect(floatingGraph).toBeVisible();
  await expect(page.getByTestId('floating-graph-resize-handle')).toBeVisible();
  const defaultFloatingBox = await floatingGraph.boundingBox();
  expect(defaultFloatingBox).not.toBeNull();
  await dragFloatingGraphResizeHandle(page, 160, -100);
  const expandedFloatingBox = await floatingGraph.boundingBox();
  expect(expandedFloatingBox).not.toBeNull();
  expect(expandedFloatingBox?.width).toBeGreaterThan((defaultFloatingBox?.width ?? 0) + 90);
  expect(expandedFloatingBox?.height).toBeGreaterThan((defaultFloatingBox?.height ?? 0) + 60);
  await expect(floatingGraph.locator('.react-flow')).toBeVisible();

  await expect(page.getByTestId('floating-graph-candidate-panel')).toHaveCount(0);
  await floatingGraph.getByText('RTX 5070', { exact: true }).click();
  await expect(page.getByTestId('floating-graph-candidate-panel')).toHaveCount(0);
  await expect(floatingGraph.locator('.react-flow__node').filter({ hasText: 'RTX 5070' }).first()).toHaveClass(/buildgraph-flow-node--mini-active/);
  await expect(candidatePanel).toContainText('RTX 5070 Ti 호환 후보');
  await expect.poll(() => compatibleCandidateRequests.length).toBe(1);

  const floatingViewportTransform = await floatingGraph.locator('.react-flow__viewport').getAttribute('style');
  await floatingGraph.getByRole('button', { name: /zoom in/i }).click();
  await expect.poll(async () => floatingGraph.locator('.react-flow__viewport').getAttribute('style')).not.toBe(floatingViewportTransform);

  const mainViewportTransform = await graphCanvas.locator('.react-flow__viewport').getAttribute('style');
  await graphCanvas.getByRole('button', { name: /zoom in/i }).click();
  await expect.poll(async () => graphCanvas.locator('.react-flow__viewport').getAttribute('style')).not.toBe(mainViewportTransform);
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
  const buildGraphRequests = await mockBuildGraphApi(page);
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
  await expect(main.getByTestId('build-dependency-graph')).toContainText('견적 관계도');
  await expect(main.getByTestId('build-dependency-graph')).toContainText('전력 여유 100W');
  await expect.poll(() => buildGraphRequests.length).toBeGreaterThan(1);
  const latestGraphRequest = buildGraphRequests[buildGraphRequests.length - 1] as { focus?: { mode?: string; category?: string } };
  expect(latestGraphRequest.focus?.mode).toBe('PART_IMPACT');
  expect(latestGraphRequest.focus?.category).toBe('GPU');
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

test('chatbot routes simple part screen commands without build-chat API call', async ({ page }) => {
  let buildChatCalls = 0;
  await mockSelfQuoteApis(page);
  await openHomeAsUser(page);
  await page.route('**/api/ai/build-chat', async (route) => {
    buildChatCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'GENERAL',
        message: '빠른 라우팅에서는 호출되면 안 됩니다.',
        builds: [],
        partRecommendation: null,
        actions: [],
        warnings: []
      })
    });
  });

  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('GPU 보여줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await page.waitForURL(/\/self-quote\?category=GPU$/);
  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await expect(page.getByTestId('ai-chat-messages')).toContainText('GPU 부품 화면으로 이동했습니다.');
  expect(buildChatCalls).toBe(0);
});

test('chatbot does not fast-route recommendation requests', async ({ page }) => {
  let buildChatCalls = 0;
  await openHomeAsUser(page);
  await page.route('**/api/ai/build-chat', async (route) => {
    buildChatCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'PART',
        message: 'GPU 후보를 정리했습니다.',
        builds: [],
        partRecommendation: {
          category: 'GPU',
          label: 'GPU',
          intro: 'GPU 후보입니다.',
          options: [item('GPU', 'balanced', 2_000_000)]
        },
        actions: [],
        warnings: []
      })
    });
  });

  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('GPU 추천해줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatCalls).toBe(1);
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId('ai-chat-messages')).toContainText('GPU 후보를 정리했습니다.');
});

test('chatbot does not fast-route cart mutation requests', async ({ page }) => {
  let buildChatCalls = 0;
  await openHomeAsUser(page);
  await page.route('**/api/ai/build-chat', async (route) => {
    buildChatCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'GENERAL',
        message: '추천 조합 담기 action을 확인합니다.',
        builds: [],
        partRecommendation: null,
        actions: [],
        warnings: []
      })
    });
  });

  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('장바구니에 추가해줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatCalls).toBe(1);
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId('ai-chat-messages')).toContainText('추천 조합 담기 action을 확인합니다.');
});

test('chatbot lets concrete product detail requests go through build-chat route resolver', async ({ page }) => {
  let buildChatCalls = 0;
  await openHomeAsUser(page);
  await page.route('**/api/ai/build-chat', async (route) => {
    buildChatCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'GENERAL',
        message: '상품 상세로 이동하겠습니다.',
        builds: [],
        partRecommendation: null,
        actions: [
          {
            id: 'route-part-detail',
            type: 'OPEN_ROUTE',
            label: '상품 상세 보기',
            description: '상품 상세 화면으로 이동합니다.',
            payload: { route: '/parts/00000000-0000-4000-8000-000000005090', source: 'AI_BUILD_CHAT' },
            requiresConfirmation: false
          }
        ],
        warnings: []
      })
    });
  });

  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('ASUS Astral 5090 상세 보여줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatCalls).toBe(1);
  await page.waitForURL(/\/parts\/00000000-0000-4000-8000-000000005090$/);
});

test('chatbot follows server OPEN_ROUTE action after allowlist validation', async ({ page }) => {
  let buildChatCalls = 0;
  await openHomeAsUser(page);
  await page.route('**/api/ai/build-chat', async (route) => {
    buildChatCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'GENERAL',
        message: '내 견적함으로 이동하겠습니다.',
        builds: [],
        partRecommendation: null,
        actions: [
          {
            id: 'route-my-quotes',
            type: 'OPEN_ROUTE',
            label: '내 견적함 열기',
            description: '내 견적함 화면으로 이동합니다.',
            payload: { route: '/my/quotes', source: 'AI_BUILD_CHAT' },
            requiresConfirmation: false
          }
        ],
        warnings: []
      })
    });
  });

  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('지난번 만든 조합 목록 열어줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatCalls).toBe(1);
  await page.waitForURL(/\/my\/quotes$/);
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
  await mockBuildGraphApi(page);
  await mockAiBuildChatApi(page);
  const { applyRequests } = await mockSelfQuoteApis(page, { staleGetAfterApply: true, getDelayAfterApplyMs: 10_000 });
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await page.goto('/self-quote');
  await expect(page.getByText('왼쪽 목록에서 부품을 담으면 이곳에 내 견적이 쌓입니다.')).toBeVisible();
  await page.goto('/');

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

test('selects a chatbot recommendation and shows the applied cart without a later remove action', async ({ page }) => {
  await mockBuildGraphApi(page);
  await mockAiBuildChatApi(page);
  const { applyRequests } = await mockSelfQuoteApis(page, { staleGetAfterApply: true, getDelayAfterApplyMs: 10_000 });
  await openHomeAsUser(page);

  await page.goto('/self-quote');
  await expect(page.getByText('왼쪽 목록에서 부품을 담으면 이곳에 내 견적이 쌓입니다.')).toBeVisible();
  await page.goto('/');

  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('200만원 PC 추천');
  await page.getByRole('button', { name: '질문 보내기' }).click();
  await page.getByTestId('ai-chat-messages').getByRole('button', { name: '이 조합으로 셀프 견적 보기' }).nth(1).click();

  await expect.poll(() => applyRequests.length).toBe(1);
  const expectedTotal = budgetBuilds(2_000_000)[1].totalPrice.toLocaleString();
  expect((applyRequests[0] as { conflictPolicy?: string; items?: unknown[] }).conflictPolicy).toBe('REPLACE');
  expect((applyRequests[0] as { items?: unknown[] }).items).toHaveLength(8);
  await expect(page).toHaveURL('/self-quote');
  await expect(page.getByRole('heading', { name: '견적 장바구니', exact: true })).toBeVisible();
  await expect(page.getByText(`${expectedTotal}원`)).toHaveCount(2);
  await expect(page.getByText('서버 반영 RTX 5070 서버 GPU').first()).toBeVisible();
  await expect(page.getByRole('button', { name: /서버 반영 RTX 5070 서버 GPU 견적에서 제거/ })).toBeVisible();
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
  await mockBuildGraphApi(page);
  await mockCompatibleCandidatesApi(page);
  await mockAiBuildChatApi(page);
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await expect(main.getByRole('img', { name: 'PC Build Festa 프리미엄 PC 완성 광고' })).toBeVisible();
  await expect(main.getByRole('heading', { name: '부품 바로가기' })).toBeVisible();
  await expect(main.getByRole('tab', { name: '인기상품' })).toBeVisible();
  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await expect(page.getByTestId('ai-chatbot-panel')).toBeVisible();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('200만원 PC 추천');
  await page.getByRole('button', { name: '질문 보내기' }).click();
  await expect(main.getByTestId('build-dependency-graph')).toContainText('견적 관계도');
  await main.getByTestId('build-dependency-graph').getByText('RTX 5070', { exact: true }).click();
  await expect(main.getByTestId('graph-flow-canvas').getByTestId('graph-node-candidate-panel')).toContainText('호환 후보');
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await expect(page.getByTestId('floating-dependency-graph')).toHaveCount(0);

  const hasBodyOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  expect(hasBodyOverflow).toBe(false);
});
