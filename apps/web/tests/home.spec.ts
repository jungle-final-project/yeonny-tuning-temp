import { expect, test, type Page } from '@playwright/test';

type AiTier = 'budget' | 'balanced' | 'performance';
type PartCategory = 'CPU' | 'MOTHERBOARD' | 'RAM' | 'GPU' | 'STORAGE' | 'PSU' | 'CASE' | 'COOLER';
type MockQuoteDraftItem = {
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
};

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
    warnings: [] as string[],
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
      imageUrl: `/assets/home-banners/pc-build-festa.png?part=${partId}`
    },
    externalOffer: {
      imageUrl: `/assets/home-banners/pc-build-festa.png?part=${partId}`,
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
      { id: 'constraint-compatibility', type: 'CONSTRAINT', category: 'MOTHERBOARD', label: 'B650 Board', status: 'PASS', detail: '소켓과 메모리 규격을 확인합니다.' },
      { id: 'constraint-budget', type: 'CONSTRAINT', category: 'PRICE', label: '예산', status: 'PASS', detail: '2,500,000원' },
      { id: 'constraint-total-price', type: 'CONSTRAINT', category: 'PRICE', label: '총액', status: 'PASS', detail: '2,000,000원' }
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

function previewLayoutGraphResponse(variant: 'power' | 'size') {
  const isSizeVariant = variant === 'size';
  return {
    mode: 'BUILD_OVERVIEW',
    summary: isSizeVariant ? 'Case clearance needs attention.' : 'Power headroom needs attention.',
    nodes: [
      { id: 'part-GPU', type: 'PART', category: 'GPU', label: 'RTX 5070', status: 'PASS', detail: '250W / 304mm' },
      { id: 'part-PSU', type: 'PART', category: 'PSU', label: '850W PSU', status: isSizeVariant ? 'PASS' : 'WARN', detail: '850W' },
      { id: 'part-CASE', type: 'PART', category: 'CASE', label: 'Mid tower case', status: isSizeVariant ? 'WARN' : 'PASS', detail: 'GPU max 320mm' },
      { id: 'constraint-budget', type: 'CONSTRAINT', category: 'PRICE', label: 'Budget', status: 'PASS', detail: '2,500,000' },
      { id: 'constraint-total-price', type: 'CONSTRAINT', category: 'PRICE', label: 'Total', status: 'PASS', detail: '2,000,000' }
    ],
    edges: [
      {
        id: 'edge-gpu-psu-power',
        source: 'part-GPU',
        target: 'part-PSU',
        type: 'AFFECTS',
        status: isSizeVariant ? 'PASS' : 'WARN',
        label: 'Power',
        summary: 'Power relation'
      },
      {
        id: 'edge-gpu-case-length',
        source: 'part-GPU',
        target: 'part-CASE',
        type: 'REQUIRES',
        status: isSizeVariant ? 'WARN' : 'PASS',
        label: 'Length',
        summary: 'Case relation'
      }
    ],
    focusNodeIds: isSizeVariant ? ['part-GPU', 'part-CASE'] : ['part-GPU', 'part-PSU'],
    insights: [
      {
        id: isSizeVariant ? 'insight-size' : 'insight-power',
        status: 'WARN',
        title: isSizeVariant ? 'Case clearance' : 'Power headroom',
        description: isSizeVariant ? 'Check case clearance.' : 'Check power headroom.',
        relatedNodeIds: isSizeVariant ? ['part-GPU', 'part-CASE'] : ['part-GPU', 'part-PSU']
      }
    ],
    toolResults: [
      { tool: isSizeVariant ? 'size' : 'power', status: 'WARN', confidence: 'MEDIUM', summary: 'Preview layout fixture' }
    ]
  };
}

async function mockBuildGraphApiWithLayoutVariants(page: Page) {
  const requests: unknown[] = [];
  await page.route('**/api/build-graphs/resolve', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}') as { items?: Array<{ partId?: string }> };
    requests.push(body);
    const partIds = (body.items ?? []).map((item) => item.partId);
    const variant = partIds.includes('home-case-h9') ? 'size' : 'power';
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(previewLayoutGraphResponse(variant))
    });
  });
  return requests;
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

async function moveHomeFullPageDown(page: Page) {
  const viewport = page.viewportSize() ?? { width: 1280, height: 720 };
  const movedByFullPageApi = await page.evaluate(() => {
    const fullpageApi = (window as unknown as { fullpage_api?: { moveSectionDown: () => void } }).fullpage_api;
    if (!fullpageApi) return false;
    fullpageApi.moveSectionDown();
    return true;
  });
  if (!movedByFullPageApi) {
    await page.mouse.move(Math.floor(viewport.width / 2), Math.floor(viewport.height / 2));
    await page.mouse.wheel(0, 1200);
  }
  await page.waitForTimeout(1100);
}

async function openDesktopAiAssistant(page: Page) {
  await expect(page.getByTestId('ai-chatbot-launcher')).toHaveCount(0);
  const logoutButton = page.getByRole('button', { name: '로그아웃' });
  const assistantButton = page.getByRole('button', { name: 'AI에게 물어보기' });
  await expect(assistantButton).toBeVisible();
  const logoutBox = await logoutButton.boundingBox();
  const assistantBox = await assistantButton.boundingBox();
  expect(logoutBox).not.toBeNull();
  expect(assistantBox).not.toBeNull();
  expect(assistantBox?.x).toBeGreaterThan(logoutBox?.x ?? 0);

  await assistantButton.click();
  const chatbotPanel = page.getByTestId('ai-chatbot-panel');
  await expect(chatbotPanel).toBeVisible();
  await expect(chatbotPanel).toHaveCSS('width', '420px');
  await expect.poll(async () => {
    const shellMarginRight = await page.locator('.screen-shell').evaluate((element) => window.getComputedStyle(element).marginRight);
    return Number.parseFloat(shellMarginRight);
  }).toBeGreaterThanOrEqual(400);
}

function budgetBuilds(budgetWon: number, appliedPartCategories: PartCategory[] = []) {
  return (['budget', 'balanced', 'performance'] as AiTier[]).map((tier) => build(tier, budgetWon, appliedPartCategories));
}

function uniqueBudgetBuilds(budgetWon: number, batchLabel: string) {
  return budgetBuilds(budgetWon).map((candidate) => ({
    ...candidate,
    id: `${candidate.id}-${batchLabel}`,
    title: `${candidate.title} ${batchLabel}`,
    items: candidate.items.map((part) => ({
      ...part,
      partId: `${part.partId}-${batchLabel}`,
      name: `${part.name} ${batchLabel}`
    }))
  }));
}

function duplicateCompositionBuilds(sourceBuilds: ReturnType<typeof budgetBuilds>, batchLabel: string) {
  return sourceBuilds.map((candidate) => ({
    ...candidate,
    id: `${candidate.id}-${batchLabel}`,
    title: `${candidate.budgetLabel} ${candidate.tierLabel} ${batchLabel}`
  }));
}

function storedAssistantSession(messageText: string) {
  return {
    messages: [
      {
        id: 'ai-intro',
        role: 'assistant',
        text: '예산은 “800만원 PC 추천”처럼, 상세 이동은 “9950X3D 상세페이지로 이동해”처럼 물어보세요. 추천은 서버의 실제 부품 DB와 룰 기반 검증 결과로 계산됩니다.',
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
    savedBuildIds: {},
    updatedAt: '2026-07-01T00:00:00.000Z'
  };
}

function storedAssistantSessionWithBuilds(messageText: string, latestBuilds = budgetBuilds(2_000_000), savedBuildIds: Record<string, string> = {}) {
  return {
    ...storedAssistantSession(messageText),
    latestBuilds,
    latestActiveBuildId: latestBuilds[1]?.id ?? latestBuilds[0]?.id,
    savedBuildIds
  };
}

async function mockCurrentQuoteDraftApi(page: Page) {
  await page.route('**/api/quote-drafts/current', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-home-empty',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [],
        totalPrice: 0,
        itemCount: 0
      })
    });
  });
}

async function mockAiBuildChatApi(page: Page) {
  const requests: Array<{ message: string; currentBuilds?: unknown[] }> = [];

  await page.route('**/api/parts/part-*', async (route) => {
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

    if (message.includes('프레임') || message.includes('시뮬레이션') || message.includes('어떻게')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          answerType: 'GENERAL',
          message: 'RTX 5080에서 RTX 5090으로 바꾸면 배그 FPS가 해상도별로 상승하는 것으로 보입니다. 아래 벤치마크 표를 참고하세요.',
          builds: [],
          warnings: [],
          simulation: {
            type: 'PERFORMANCE_COMPARISON',
            category: 'GPU',
            currentPart: {
              partId: 'part-rtx5080',
              category: 'GPU',
              name: 'RTX 5080',
              manufacturer: 'MSI',
              price: 1700000
            },
            targetPart: {
              partId: 'part-rtx5090',
              category: 'GPU',
              name: 'RTX 5090',
              manufacturer: 'ZOTAC',
              price: 3400000
            },
            summary: 'RTX 5080에서 RTX 5090으로 바꿨을 때 확인 가능한 벤치마크입니다.',
            scoreComparison: {
              label: '내부 벤치마크 정규화 점수 (참고용)',
              currentScore: 95,
              targetScore: 100,
              delta: 5
            },
            fpsComparisons: [
              {
                gameTitle: "PlayerUnknown's Battlegrounds",
                resolution: 'QHD',
                graphicsPreset: 'HIGH',
                currentFps: 223,
                targetFps: 243,
                deltaFps: 20,
                source: 'HowManyFPS'
              }
            ],
            specComparisons: [
              { label: 'VRAM', currentValue: '16GB', targetValue: '32GB', deltaText: '+16GB' }
            ],
            warnings: [],
            disclaimer: '본 수치는 내부 벤치마크 DB 기준 참고용 추정치이며, 내부 DB에 등록된 부품·게임·해상도 조합에 한해 제공됩니다. 실제 성능은 게임 버전, 그래픽 옵션, 드라이버, 해상도, 냉각·전원 환경에 따라 달라질 수 있습니다.'
          }
        })
      });
      return;
    }

    if (/gpu/i.test(message) || message.includes('GPU')) {
      const baseBudget = 2_000_000;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          answerType: 'PART',
          message: 'GPU 추천 후보 3개를 DB 현재가 기준으로 정리했고 최신 추천 조합에도 반영했습니다.',
          builds: budgetBuilds(baseBudget, ['GPU']),
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
        warnings: []
      })
    });
  });

  return requests;
}

async function mockAiBuildChatSequence(page: Page, buildResponses: Array<ReturnType<typeof budgetBuilds>>) {
  const requests: Array<{ message: string; currentBuilds?: unknown[] }> = [];
  await page.route('**/api/ai/build-chat', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}') as { message?: string; currentBuilds?: unknown[] };
    const requestIndex = requests.length;
    requests.push({ message: body.message ?? '', currentBuilds: body.currentBuilds });
    const responseBuilds = buildResponses[Math.min(requestIndex, buildResponses.length - 1)] ?? [];
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'BUDGET',
        message: `${body.message ?? '추천'} 기준 추천 조합을 계산했습니다.`,
        builds: responseBuilds,
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

  const recommendedOrder = [
    'home-gpu-rtx5070',
    'home-cpu-ryzen7',
    'home-ram-ddr5-32',
    'home-psu-850-popular',
    'home-ssd-nvme-1tb',
    'home-board-b850',
    'home-case-frame',
    'home-cooler-phantom'
  ];
  await page.route('**/api/recommendations/home-parts**', async (route) => {
    const items = recommendedOrder
      .map((id, index) => {
        const part = homeParts.find((candidate) => candidate.id === id);
        if (!part) return null;
        return {
          recommendationId: `home-part-${part.id}`,
          rankPosition: index,
          part,
          scoreSource: 'FALLBACK',
          reasonTags: ['benchmark', 'image']
        };
      })
      .filter(Boolean);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        generatedAt: '2026-07-03T10:00:00Z',
        fallbackUsed: true
      })
    });
  });

  await page.route('**/api/recommendation-events', async (route) => {
    const body = route.request().postDataJSON() as { eventType?: string; sourceSurface?: string; recommendationId?: string; category?: string; rankPosition?: number };
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        id: `event-${body.eventType ?? 'unknown'}-${body.rankPosition ?? 0}`,
        eventType: body.eventType,
        labelScore: body.eventType === 'IMPRESSION' ? 0 : 1,
        sourceSurface: body.sourceSurface,
        recommendationId: body.recommendationId,
        category: body.category,
        rankPosition: body.rankPosition,
        createdAt: '2026-07-03T10:00:00Z'
      })
    });
  });

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
  options: { staleGetAfterApply?: boolean; getDelayAfterApplyMs?: number; initialItems?: MockQuoteDraftItem[]; failApply?: boolean } = {}
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
  let draft: {
    id: string;
    status: string;
    name: string;
    items: MockQuoteDraftItem[];
    totalPrice: number;
    itemCount: number;
  } = {
    id: 'draft-home-ai-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: options.initialItems ?? [],
    totalPrice: (options.initialItems ?? []).reduce((sum, next) => sum + next.lineTotal, 0),
    itemCount: (options.initialItems ?? []).reduce((sum, next) => sum + next.quantity, 0)
  };

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/apply-ai-build')) {
      const body = JSON.parse(route.request().postData() ?? '{}') as { items?: Array<{ partId: string; category: string; quantity: number }> };
      applyRequests.push(body);
      if (options.failApply) {
        await route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'apply failed' })
        });
        return;
      }
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
  await expect(main.getByRole('img', { name: '배틀그라운드 조립 PC 광고' })).toBeVisible();
  await expect(main.getByRole('button', { name: /배틀그라운드 추천 PC/ })).toBeVisible();
  for (const label of ['PC 견적', '전체 부품', 'AS 접수', '내 견적함']) {
    await expect(main.getByRole('link', { name: new RegExp(label) }).first()).toBeVisible();
  }
  await expect(main.getByRole('button', { name: /AI로 견적 맞춰보기/ })).toBeVisible();
  await expect(main.getByRole('link', { name: /AI로 견적 맞춰보기/ })).toHaveCount(0);
  await expect(main.getByRole('link', { name: /PC 부품 살펴보기/ })).toHaveAttribute('href', '/self-quote');
  await expect(main.getByRole('link', { name: /전체 부품/ }).first()).toHaveAttribute('href', '/self-quote?view=list');
  await expect(main.getByRole('heading', { name: '추천상품' })).toBeVisible();
  await expect(main.getByRole('tab', { name: '인기상품' })).toHaveAttribute('aria-selected', 'true');
  await expect(main.getByRole('tab', { name: 'AI 추천상품' })).toHaveAttribute('aria-selected', 'false');
  const qhdRecommendationCard = main.getByTestId('home-featured-preview-card-home-featured-qhd-gaming');
  await expect(qhdRecommendationCard).toBeVisible();
  await expect(qhdRecommendationCard.getByText('2,293,000원')).toBeVisible();
  await expect(qhdRecommendationCard.getByRole('img', { name: /Home FRAME 4000D Case/ })).toBeVisible();
  await expect(qhdRecommendationCard.getByRole('button', { name: 'QHD 게이밍 추천팩 셀프견적에 담기' })).toBeVisible();
  await main.getByRole('tab', { name: 'AI 추천상품' }).click();
  await expect(main.getByText('AI에게 예산이나 부품을 물어보면 추천상품 3개가 여기에 표시됩니다.')).toBeVisible();
  await expect(main.getByRole('heading', { name: '인기 부품 랭킹' })).toBeVisible();
  await expect(main.getByRole('img', { name: /Home RTX 5070 GPU/ })).toBeVisible();
  const firstPartCard = main.getByRole('link', { name: '인기 부품 1번 보기' });
  await expect(firstPartCard.getByText('벤치마크 점수 포함')).toBeVisible();
  await expect(firstPartCard.getByText('상품 정보 확인')).toBeVisible();
  await expect(firstPartCard).toHaveAttribute(
    'href',
    '/parts/home-gpu-rtx5070?recId=home-part-home-gpu-rtx5070&recSurface=HOME_RECOMMENDED_PARTS&rank=0'
  );

});

test('selects a featured recommendation and applies every build part to self quote', async ({ page }) => {
  const { applyRequests } = await mockSelfQuoteApis(page);
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  const qhdRecommendationCard = main.getByTestId('home-featured-preview-card-home-featured-qhd-gaming');
  await expect(qhdRecommendationCard.getByRole('img', { name: /Home FRAME 4000D Case/ })).toBeVisible();
  await qhdRecommendationCard.getByRole('button', { name: 'QHD 게이밍 추천팩 셀프견적에 담기' }).click();

  await expect.poll(() => applyRequests.length).toBe(1);
  const request = applyRequests[0] as { buildId?: string; items?: Array<{ partId: string; category: string; quantity: number }> };
  expect(request.buildId).toBe('home-featured-qhd-gaming');
  expect(request.items?.map((item) => item.category)).toEqual(['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER']);
  expect(request.items).toContainEqual({ partId: 'home-gpu-rtx5070', category: 'GPU', quantity: 1 });
  expect(request.items).toContainEqual({ partId: 'home-case-frame', category: 'CASE', quantity: 1 });
  await expect(page).toHaveURL('/self-quote');
  // 보드 카드는 이미지+카테고리 요약이라 상품명은 체크리스트(품목 지도)에서 확인한다.
  await expect(page.getByTestId('checklist-GPU')).toContainText('Home RTX 5070 GPU');
  await expect(page.getByTestId('checklist-CASE')).toContainText('Home FRAME 4000D Case');
});

test('renders the full draggable home preview graph', async ({ page }) => {
  await mockBuildGraphApiWithLayoutVariants(page);
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await main.getByTestId('home-featured-preview-card-home-featured-qhd-gaming').click();
  const graphCanvas = main.getByTestId('graph-flow-canvas');
  await expect(graphCanvas.locator('.react-flow__node')).toHaveCount(4);
  await expect(graphCanvas.locator('.react-flow__edge.buildgraph-flow-edge')).toHaveCount(2);
  await expect(graphCanvas.locator('.react-flow__node').filter({ hasText: 'RTX 5070' })).toHaveCount(1);
  await expect(graphCanvas.locator('.react-flow__node').filter({ hasText: '850W PSU' })).toHaveCount(1);
  await expect(graphCanvas.locator('.react-flow__node').filter({ hasText: 'Mid tower case' })).toHaveCount(1);
  await expect(graphCanvas).toContainText('Total');

  const gpuNode = graphCanvas.locator('.react-flow__node').filter({ hasText: 'RTX 5070' }).first();
  const beforeDragBox = await gpuNode.boundingBox();
  expect(beforeDragBox).not.toBeNull();
  const dragStartX = (beforeDragBox?.x ?? 0) + (beforeDragBox?.width ?? 0) / 2;
  const dragStartY = (beforeDragBox?.y ?? 0) + (beforeDragBox?.height ?? 0) / 2;

  await page.mouse.move(dragStartX, dragStartY);
  await page.mouse.down();
  await page.mouse.move(dragStartX + 64, dragStartY + 28, { steps: 6 });
  await page.mouse.up();

  await expect.poll(async () => {
    const afterDragBox = await gpuNode.boundingBox();
    return Math.round((afterDragBox?.x ?? 0) - (beforeDragBox?.x ?? 0));
  }).toBeGreaterThan(20);
});

test('chatbot uses build-chat API and updates latest home AI recommendations', async ({ page }) => {
  const buildGraphRequests = await mockBuildGraphApi(page);
  const buildChatRequests = await mockAiBuildChatApi(page);
  await mockCurrentQuoteDraftApi(page);
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCount(0);
  await openDesktopAiAssistant(page);
  const chatbotPanel = page.getByTestId('ai-chatbot-panel');
  const chatbotInput = page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' });

  await expect(chatbotPanel.getByRole('button', { name: '200만원 게이밍 PC' })).toBeVisible();
  await expect(chatbotPanel.getByRole('button', { name: '견적 마저 채우기' })).toBeVisible();
  await expect(chatbotPanel.getByRole('button', { name: '성능 비교' })).toBeVisible();
  await expect(chatbotPanel.getByRole('button', { name: '800만원 PC 추천' })).toHaveCount(0);
  await expect(chatbotPanel.getByRole('button', { name: '9950X3D 상세' })).toHaveCount(0);
  await expect(chatbotPanel.getByRole('button', { name: '내 견적함' })).toHaveCount(0);
  await chatbotPanel.getByRole('button', { name: '200만원 게이밍 PC' }).click();
  await expect(chatbotInput).toHaveValue('200만원으로 게이밍 PC 추천해줘');

  await chatbotInput.fill('200만원 PC 추천');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatRequests.length).toBe(1);
  expect(buildChatRequests[0].message).toBe('200만원 PC 추천');
  await expect(chatbotPanel).toContainText('AI 견적 어시스턴트');
  await expect(chatbotPanel).toContainText('가격 호환');
  await expect(main.getByRole('tab', { name: 'AI 추천상품' })).toHaveAttribute('aria-selected', 'true');
  await expect(main.getByTestId('home-ai-recommendations')).toContainText('200만원 실속형');
  await expect(main.getByTestId('home-ai-recommendations')).toContainText('200만원 균형형');
  await expect(main.getByTestId('home-ai-recommendations').getByRole('img', { name: /케이스 이미지/ })).toHaveCount(0);
  await expect(main.getByTestId('home-ai-recommendations')).toContainText('AI 추천');
  await expect(main.getByTestId('home-ai-recommendations')).toContainText('관계도 미리보기');
  const aiImageSrcs = await main.getByTestId('home-ai-recommendations').locator('img.home-ai-preview-thumb').evaluateAll((images) => (
    images.map((image) => (image as HTMLImageElement).getAttribute('src'))
  ));
  expect(new Set(aiImageSrcs).size).toBeGreaterThan(1);
  await expect(main.getByTestId('build-dependency-graph')).toHaveCount(0);
  await main.getByTestId('home-ai-preview-card-server-2000000-balanced-base').click();
  await expect(main.getByTestId('build-dependency-graph')).toContainText('AI 추천 관계도');
  await expect(main.getByTestId('build-dependency-graph')).toContainText('호환됨');
  await expect(main.getByTestId('build-dependency-graph')).not.toContainText('여유 있음');
  await expect(main.getByTestId('build-dependency-graph')).toContainText('간섭 주의');
  const graphCanvas = main.getByTestId('graph-flow-canvas');
  await expect(main.getByTestId('graph-summary-panel')).toHaveCount(0);
  await expect(main.getByTestId('build-dependency-graph')).not.toContainText('영향 요약');
  const sectionBox = await main.getByTestId('build-dependency-graph').boundingBox();
  const canvasBox = await graphCanvas.boundingBox();
  expect(sectionBox).not.toBeNull();
  expect(canvasBox).not.toBeNull();
  expect(canvasBox?.width).toBeGreaterThan((sectionBox?.width ?? 0) * 0.94);
  const guideCapsule = graphCanvas.getByTestId('graph-edge-guide-capsule');
  await expect(guideCapsule).toContainText('선을 누르면 두 부품 사이의 제약과 판단 근거를 확인할 수 있어요');
  await expect(graphCanvas.getByTestId('graph-edge-legend-card')).toHaveCount(0);
  await expect(graphCanvas.getByTestId('graph-issue-card')).toHaveCount(0);
  await expect(graphCanvas).not.toContainText('250W · 길이 304mm');
  await expect(graphCanvas).not.toContainText('권장 출력 750W / 현재 파워 850W');
  await expect(graphCanvas).not.toContainText('정격 850W');
  await expect(graphCanvas).not.toContainText('B650 Board');
  await expect(graphCanvas.locator('.react-flow__node').filter({ hasText: '미들타워 케이스' })).toHaveCount(1);
  await expect(graphCanvas).not.toContainText('예산');
  await expect(graphCanvas).toContainText('총액');
  await expect(main.getByTestId('build-dependency-graph')).not.toContainText('기본 호환성');
  await expect(main.getByTestId('build-dependency-graph')).not.toContainText('PASS');
  await expect(main.getByTestId('build-dependency-graph')).not.toContainText('WARN');
  await expect.poll(() => buildGraphRequests.length).toBeGreaterThan(0);
  expect((buildGraphRequests[0] as { source?: string; items?: unknown[] }).source).toBe('AI_BUILD');
  expect((buildGraphRequests[0] as { items?: unknown[] }).items?.length).toBe(8);
  await graphCanvas.scrollIntoViewIfNeeded();
  await expect(page.getByTestId('floating-dependency-graph')).toHaveCount(0);
  const graphCanvasBox = await graphCanvas.boundingBox();
  const graphPaneBox = await graphCanvas.locator('.react-flow').boundingBox();
  expect(graphCanvasBox?.height).toBeGreaterThanOrEqual(280);
  expect(graphPaneBox?.height).toBeGreaterThanOrEqual(260);
  const gpuGraphNode = graphCanvas.locator('.react-flow__node').filter({ hasText: 'RTX 5070' }).first();
  await expect(gpuGraphNode).toHaveClass(/buildgraph-flow-node/);
  await expect(gpuGraphNode).not.toHaveClass(/react-flow__node-default/);
  await expect(gpuGraphNode).toHaveCSS('border-radius', '10px');
  await expect(gpuGraphNode.locator('.buildgraph-node-card-main')).toBeVisible();
  await expect(gpuGraphNode.locator('.buildgraph-node-category-label')).toHaveText('GPU');
  await expect(gpuGraphNode.locator('.buildgraph-node-main-label')).toContainText('RTX 5070');
  await expect(gpuGraphNode.locator('.buildgraph-node-status-label')).toHaveText('간섭 주의');
  await expect(gpuGraphNode).toHaveCSS('border-color', 'rgb(245, 158, 11)');
  const gpuGraphNodeBox = await gpuGraphNode.boundingBox();
  expect(gpuGraphNodeBox).not.toBeNull();
  expect(gpuGraphNodeBox?.width).toBeGreaterThan((gpuGraphNodeBox?.height ?? 0) + 40);
  await expect(gpuGraphNode.locator('.buildgraph-node-status-orb')).toHaveCount(0);
  const graphEdgePath = graphCanvas.locator('.react-flow__edge.buildgraph-flow-edge .react-flow__edge-path').first();
  await expect(graphEdgePath).toHaveCSS('stroke-linecap', 'round');
  await expect(graphEdgePath).toHaveCSS('stroke-width', '2px');
  await moveHomeFullPageDown(page);
  await expect(page.getByTestId('floating-dependency-graph')).toHaveCount(0);
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

test('toggles the desktop AI assistant drawer from the header button', async ({ page }) => {
  await openHomeAsUser(page);
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCount(0);

  await openDesktopAiAssistant(page);
  const assistantButton = page.getByRole('button', { name: 'AI에게 물어보기' });
  await assistantButton.click();

  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCount(0);
  await expect(page.getByTestId('ai-chatbot-launcher')).toHaveCount(0);
  await expect.poll(async () => {
    const shellMarginRight = await page.locator('.screen-shell').evaluate((element) => window.getComputedStyle(element).marginRight);
    return Number.parseFloat(shellMarginRight);
  }).toBeLessThan(1);
});

test('chatbot renders performance simulation as a benchmark card', async ({ page }) => {
  await mockBuildGraphApi(page);
  await mockAiBuildChatApi(page);
  await mockCurrentQuoteDraftApi(page);
  await openHomeAsUser(page);

  await openDesktopAiAssistant(page);
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('지금 견적에 그래픽카드 5090 바꾸면 배그에서 어떻게 되나요?');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  const messages = page.getByTestId('ai-chat-messages');
  await expect(messages).toContainText('성능 시뮬레이션');
  await expect(messages).toContainText('RTX 5080 → RTX 5090');
  await expect(messages).toContainText('내부 벤치마크 정규화 점수 (참고용)');
  await expect(messages).toContainText('참고용 추정치');
  await expect(messages).toContainText("PlayerUnknown's Battlegrounds");
  await expect(messages).toContainText('223fps → 243fps');
  await expect(messages).toContainText('+20fps');
  await expect(messages).not.toContainText('AI DB 답변');
  await expect(messages).not.toContainText('내부 normalized');
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

  await openDesktopAiAssistant(page);

  await expect(page.getByTestId('ai-chat-messages')).toContainText('예산 견적은');
  await expect(page.getByTestId('ai-chat-messages')).not.toContainText('kmb5037@naver.com 계정의 이전 구매 상담');
  await expect(page.getByTestId('ai-chat-messages')).not.toContainText('legacy global key에 남아 있던 이전 상담');
});

test('chatbot asks for login when token disappears before submit', async ({ page }) => {
  let buildChatCalls = 0;
  await mockCurrentQuoteDraftApi(page);
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
        warnings: []
      })
    });
  });

  await openDesktopAiAssistant(page);
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

test('chatbot asks clarification with quick replies for vague requests and merges the follow-up', async ({ page }) => {
  await mockBuildGraphApi(page);
  await mockCompatibleCandidatesApi(page);
  await mockCurrentQuoteDraftApi(page);
  const chatRequests: Array<{ message: string; clarificationContext?: { originalMessage?: string } }> = [];
  await page.route('**/api/ai/build-chat', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}') as {
      message?: string;
      clarificationContext?: { originalMessage?: string };
    };
    chatRequests.push({ message: body.message ?? '', clarificationContext: body.clarificationContext });
    if (chatRequests.length === 1) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          answerType: 'GENERAL',
          message: '어떤 해상도 기준으로 맞출까요? 예산까지 알려주시면 바로 추천해드릴게요.',
          builds: [],
          warnings: ['LOW_INFORMATION'],
          quickReplies: ['FHD 게이밍 150만원', 'QHD 게이밍 250만원', '4K 게이밍 400만원'],
          clarification: { missingSlots: ['budget', 'useCase'], originalMessage: '해상도 좋은 피시 맞춰줘' }
        })
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'BUDGET',
        message: '250만원 기준 추천입니다.',
        builds: budgetBuilds(2500000),
        warnings: []
      })
    });
  });
  await openHomeAsUser(page);
  await openDesktopAiAssistant(page);
  const chatbotPanel = page.getByTestId('ai-chatbot-panel');
  const chatbotInput = page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' });

  await chatbotInput.fill('해상도 좋은 피시 맞춰줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  // 1턴: 견적 없이 되묻기 + 선택지 칩
  const quickReplies = chatbotPanel.getByTestId('ai-quick-replies');
  await expect(quickReplies).toBeVisible();
  await expect(chatbotPanel).toContainText('어떤 해상도 기준으로');
  await quickReplies.getByRole('button', { name: 'QHD 게이밍 250만원' }).click();

  // 2턴: 칩 문구가 그대로 전송되고 원 요청이 clarificationContext로 에코된다
  await expect.poll(() => chatRequests.length).toBe(2);
  expect(chatRequests[1].message).toBe('QHD 게이밍 250만원');
  expect(chatRequests[1].clarificationContext?.originalMessage).toBe('해상도 좋은 피시 맞춰줘');
  await expect(chatbotPanel).toContainText('250만원 기준 추천입니다.');
});

test('chatbot sends draft mutation messages to build-chat without touching the quote draft', async ({ page }) => {
  let buildChatCalls = 0;
  const draftMutationMethods: string[] = [];
  await page.route('**/api/quote-drafts/**', async (route) => {
    const method = route.request().method();
    if (method !== 'GET') {
      draftMutationMethods.push(method);
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-home-empty',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [],
        totalPrice: 0,
        itemCount: 0
      })
    });
  });
  await openHomeAsUser(page);
  await page.route('**/api/ai/build-chat', async (route) => {
    buildChatCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'GENERAL',
        message: '견적 변경은 셀프 견적 화면에서 직접 확인해 주세요.',
        builds: [],
        warnings: []
      })
    });
  });

  await openDesktopAiAssistant(page);
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('그래픽카드 빼줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatCalls).toBe(1);
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId('ai-chat-messages')).toContainText('견적 변경은 셀프 견적 화면에서 직접 확인해 주세요.');
  expect(draftMutationMethods).toHaveLength(0);
});

test('chatbot maps build-chat 401 to login required instead of generic failure', async ({ page }) => {
  let buildChatCalls = 0;
  await mockCurrentQuoteDraftApi(page);
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

  await openDesktopAiAssistant(page);
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
  await expect(page.getByText('미장착 슬롯 8개가 있습니다')).toBeVisible();
  await page.goto('/');

  await openDesktopAiAssistant(page);
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('200만원 안에서 QHD 게임용 PC 추천해줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('GPU 추천해줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();
  await main.getByTestId('home-ai-recommendations').getByRole('button', { name: /200만원 균형형 셀프 견적에 담기/ }).click();

  await expect.poll(() => applyRequests.length).toBe(1);
  const expectedTotal = budgetBuilds(2_000_000, ['GPU'])[1].totalPrice.toLocaleString();
  expect((applyRequests[0] as { conflictPolicy?: string; items?: unknown[] }).conflictPolicy).toBe('REPLACE');
  expect((applyRequests[0] as { items?: unknown[] }).items).toHaveLength(8);
  await expect(page).toHaveURL('/self-quote');
  const selectedBuildPanel = page.getByTestId('ai-selected-build-panel');
  await expect(selectedBuildPanel).toBeVisible();
  await expect(page.getByRole('heading', { name: 'AI 선택 조합' })).toBeVisible();
  await expect(selectedBuildPanel.getByText('200만원 균형형')).toBeVisible();
  await expect(selectedBuildPanel.getByText(`${expectedTotal}원`)).toBeVisible();
  await expect(selectedBuildPanel.getByText('GPU 반영됨')).toBeVisible();
  await expect(page.getByText('서버 반영 RTX 5070 서버 GPU').first()).toBeVisible();
  await expect(page.getByRole('heading', { name: '셀프 견적 · 구성 관계도' })).toBeVisible();
});

test('selects a chatbot recommendation and shows the applied cart without a later remove action', async ({ page }) => {
  await mockBuildGraphApi(page);
  await mockAiBuildChatApi(page);
  const { applyRequests } = await mockSelfQuoteApis(page, { staleGetAfterApply: true, getDelayAfterApplyMs: 10_000 });
  await openHomeAsUser(page);

  await page.goto('/self-quote');
  await expect(page.getByText('미장착 슬롯 8개가 있습니다')).toBeVisible();
  await page.goto('/');

  await openDesktopAiAssistant(page);
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('200만원 PC 추천');
  await page.getByRole('button', { name: '질문 보내기' }).click();
  await page.getByTestId('ai-chat-messages').getByRole('button', { name: '이 조합으로 셀프 견적 보기' }).nth(1).click();

  await expect.poll(() => applyRequests.length).toBe(1);
  const expectedTotal = budgetBuilds(2_000_000)[1].totalPrice.toLocaleString();
  expect((applyRequests[0] as { conflictPolicy?: string; items?: unknown[] }).conflictPolicy).toBe('REPLACE');
  expect((applyRequests[0] as { items?: unknown[] }).items).toHaveLength(8);
  await expect(page).toHaveURL('/self-quote');
  await expect(page.getByRole('heading', { name: '셀프 견적 · 구성 관계도' })).toBeVisible();
  await expect(page.getByTestId('slot-status-bar').getByText(`${expectedTotal}원`)).toBeVisible();
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
  await expect(nav.getByRole('link', { name: '전체 부품' })).toHaveAttribute('href', '/parts');
  await expect(nav.getByRole('link', { name: '추천 결과' })).toHaveAttribute('href', '/builds/latest');
  await expect(nav.getByRole('link', { name: '관리자' })).toHaveCount(0);
});

test('shows chatbot session recommendations on the recommendation result page without build history lookup', async ({ page }) => {
  const historyRequests: string[] = [];
  const latestBuilds = budgetBuilds(2_000_000);
  await page.route('**/api/builds/history', async (route) => {
    historyRequests.push(route.request().url());
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'UNEXPECTED_HISTORY_LOOKUP' })
    });
  });

  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds) });
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();

  await expect(page).toHaveURL('/builds/latest');
  await expect(page.getByRole('heading', { name: '추천 결과' })).toBeVisible();
  const latestGrid = page.getByTestId('latest-build-card-grid');
  await expect(latestGrid.getByText('200만원 실속형')).toBeVisible();
  await expect(latestGrid.getByText('200만원 균형형')).toBeVisible();
  await expect(latestGrid.getByText('200만원 성능형')).toBeVisible();
  await expect(page.getByText('최근 AI 추천 조합을 최대 9개까지 보관합니다. 현재 3/9개')).toBeVisible();
  await expect(page.getByRole('button', { name: '전체', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('button', { name: '실속형', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '균형형', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '성능형', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '상세 보기' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '상세 보기' })).toHaveCount(3);
  await expect(page.getByRole('button', { name: '견적 저장' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: /선택한 추천 조합/ })).toHaveCount(0);
  await expect(page.getByRole('dialog', { name: '추천 조합 상세' })).toHaveCount(0);
  expect(historyRequests).toHaveLength(0);
});

test('accumulates chatbot recommendations up to nine and sends only the latest response as chat context', async ({ page }) => {
  const buildChatRequests = await mockAiBuildChatSequence(page, [
    uniqueBudgetBuilds(2_000_000, '1차'),
    uniqueBudgetBuilds(2_100_000, '2차'),
    uniqueBudgetBuilds(2_200_000, '3차'),
    uniqueBudgetBuilds(2_300_000, '4차')
  ]);
  await mockCurrentQuoteDraftApi(page);
  await openHomeAsUser(page);

  await openDesktopAiAssistant(page);
  for (const message of ['1차 추천', '2차 추천', '3차 추천', '4차 추천']) {
    await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill(message);
    await page.getByRole('button', { name: '질문 보내기' }).click();
    await expect.poll(() => buildChatRequests.length).toBeGreaterThanOrEqual(['1차 추천', '2차 추천', '3차 추천', '4차 추천'].indexOf(message) + 1);
    await expect(page.getByTestId('ai-chat-messages')).toContainText(`${message} 기준 추천 조합을 계산했습니다.`);
  }

  expect(buildChatRequests[0].currentBuilds ?? []).toHaveLength(0);
  expect(buildChatRequests[1].currentBuilds).toHaveLength(3);
  expect(buildChatRequests[2].currentBuilds).toHaveLength(3);
  expect(buildChatRequests[3].currentBuilds).toHaveLength(3);
  expect((buildChatRequests[3].currentBuilds as Array<{ id: string }>).map((build) => build.id)).toEqual(uniqueBudgetBuilds(2_200_000, '3차').map((build) => build.id));

  const storedBuilds = await page.evaluate(() => {
    const raw = sessionStorage.getItem('buildgraph.ai.assistantSession:user-1004');
    return raw ? JSON.parse(raw).latestBuilds as Array<{ id: string; title: string }> : [];
  });
  expect(storedBuilds).toHaveLength(9);
  expect(storedBuilds.map((build) => build.id)).toEqual([
    ...uniqueBudgetBuilds(2_300_000, '4차').map((build) => build.id),
    ...uniqueBudgetBuilds(2_200_000, '3차').map((build) => build.id),
    ...uniqueBudgetBuilds(2_100_000, '2차').map((build) => build.id)
  ]);
  expect(storedBuilds.map((build) => build.id)).not.toContain(uniqueBudgetBuilds(2_000_000, '1차')[0].id);

  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();
  const latestGrid = page.getByTestId('latest-build-card-grid');
  await expect(page.getByText('최근 AI 추천 조합을 최대 9개까지 보관합니다. 현재 9/9개')).toBeVisible();
  await expect(page.getByRole('button', { name: '상세 보기' })).toHaveCount(9);
  await expect(latestGrid.getByText('230만원 실속형 4차')).toBeVisible();
  await expect(latestGrid.getByText('220만원 성능형 3차')).toBeVisible();
  await expect(latestGrid.getByText('210만원 균형형 2차')).toBeVisible();
  await expect(latestGrid.getByText('200만원 실속형 1차')).toHaveCount(0);
});

test('deduplicates identical build compositions when accumulating chatbot recommendations', async ({ page }) => {
  const originalBuilds = uniqueBudgetBuilds(2_000_000, '원본');
  const duplicateBuilds = duplicateCompositionBuilds(originalBuilds, '새추천');
  await mockAiBuildChatSequence(page, [duplicateBuilds]);
  await mockCurrentQuoteDraftApi(page);
  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('이전 추천', originalBuilds) });

  await openDesktopAiAssistant(page);
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('같은 조건 다시 추천');
  await page.getByRole('button', { name: '질문 보내기' }).click();
  await expect(page.getByTestId('ai-chat-messages')).toContainText('같은 조건 다시 추천 기준 추천 조합을 계산했습니다.');

  const storedBuilds = await page.evaluate(() => {
    const raw = sessionStorage.getItem('buildgraph.ai.assistantSession:user-1004');
    return raw ? JSON.parse(raw).latestBuilds as Array<{ id: string; title: string }> : [];
  });
  expect(storedBuilds).toHaveLength(3);
  expect(storedBuilds.map((build) => build.id)).toEqual(duplicateBuilds.map((build) => build.id));

  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();
  const latestGrid = page.getByTestId('latest-build-card-grid');
  await expect(page.getByText('최근 AI 추천 조합을 최대 9개까지 보관합니다. 현재 3/9개')).toBeVisible();
  await expect(latestGrid.getByText('200만원 실속형 새추천')).toBeVisible();
  await expect(latestGrid.getByText('200만원 실속형 원본')).toHaveCount(0);
});

test('filters latest recommendation cards and closes the detail drawer when the selected build is hidden', async ({ page }) => {
  const latestBuilds = [
    ...uniqueBudgetBuilds(2_300_000, '4차'),
    ...uniqueBudgetBuilds(2_200_000, '3차'),
    ...uniqueBudgetBuilds(2_100_000, '2차')
  ];
  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('최근 추천', latestBuilds) });
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();
  const latestGrid = page.getByTestId('latest-build-card-grid');

  await expect(page.getByRole('button', { name: '상세 보기' })).toHaveCount(9);
  await page.getByRole('button', { name: /230만원 성능형 4차/ }).click();
  const drawer = page.getByRole('dialog', { name: '추천 조합 상세' });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole('heading', { name: '선택한 추천 조합 / 230만원 성능형 4차' })).toBeVisible();

  await page.getByRole('button', { name: '실속형', exact: true }).click();
  await expect(page.getByRole('button', { name: '실속형', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('button', { name: '상세 보기' })).toHaveCount(3);
  await expect(latestGrid.getByText('230만원 실속형 4차')).toBeVisible();
  await expect(latestGrid.getByText('230만원 성능형 4차')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: /선택한 추천 조합/ })).toHaveCount(0);
  await expect(drawer).toHaveCount(0);

  await page.getByRole('button', { name: '전체', exact: true }).click();
  await expect(page.getByRole('button', { name: '상세 보기' })).toHaveCount(9);
});

test('shows chatbot guide empty state when there are no temporary recommendations', async ({ page }) => {
  const historyRequests: string[] = [];
  await page.route('**/api/builds/history', async (route) => {
    historyRequests.push(route.request().url());
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [build('balanced', 2_000_000)] })
    });
  });

  await openHomeAsUser(page);
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();

  await expect(page).toHaveURL('/builds/latest');
  await expect(page.getByRole('heading', { name: '추천 결과' })).toBeVisible();
  await expect(page.getByText('AI 챗봇에게 먼저 추천을 받아보세요')).toBeVisible();
  await expect(page.getByRole('link', { name: '홈에서 AI 챗봇 열기' })).toHaveAttribute('href', '/');
  expect(historyRequests).toHaveLength(0);
});

test('renders a temporary chatbot build detail and saves it to a persisted build', async ({ page }) => {
  const latestBuilds = budgetBuilds(2_000_000);
  const temporaryBuild = latestBuilds[1];
  const saveRequests: unknown[] = [];
  await page.route('**/api/builds/from-chat', async (route) => {
    const requestBody = JSON.parse(route.request().postData() ?? '{}');
    saveRequests.push(requestBody);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'saved-chat-build-001' })
    });
  });
  await page.route('**/api/builds/saved-chat-build-001', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'saved-chat-build-001',
        name: temporaryBuild.title,
        recommendedFor: temporaryBuild.tierLabel,
        summary: temporaryBuild.summary,
        totalPrice: temporaryBuild.totalPrice,
        confidence: temporaryBuild.confidence,
        items: temporaryBuild.items,
        warnings: [],
        evidenceIds: [],
        agentSessionId: null,
        agentSummary: null,
        changeableCategories: ['GPU', 'RAM'],
        createdAt: '2026-07-03T00:00:00Z',
        toolResults: temporaryBuild.toolResults
      })
    });
  });

  await openHomeAsUser(page);
  const session = storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session });
  await page.addInitScript(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session });
  await page.goto(`/builds/${temporaryBuild.id}`);

  await expect(page.getByRole('heading', { name: `추천 Build 결과 / ${temporaryBuild.title}` })).toBeVisible();
  await expect(page.getByText('저장 전 AI 챗봇 추천')).toBeVisible();
  await expect(page.getByRole('link', { name: temporaryBuild.items[0].name })).toBeVisible();
  await page.getByRole('button', { name: '견적 저장' }).click();

  await expect.poll(() => saveRequests.length).toBe(1);
  expect(saveRequests[0]).toMatchObject({
    sourceBuildId: temporaryBuild.id,
    lastUserMessage: '200만원 PC 추천',
    build: {
      id: temporaryBuild.id,
      title: temporaryBuild.title
    }
  });
  await expect(page).toHaveURL('/builds/saved-chat-build-001');
  await expect(page.getByRole('link', { name: '내 견적함 보기' })).toHaveAttribute('href', '/my/quotes');
  await expect(page.getByRole('button', { name: '견적 저장' })).toHaveCount(0);
  const savedMapping = await page.evaluate((buildId) => {
    const raw = sessionStorage.getItem('buildgraph.ai.assistantSession:user-1004');
    return raw ? JSON.parse(raw).savedBuildIds?.[buildId] : null;
  }, temporaryBuild.id);
  expect(savedMapping).toBe('saved-chat-build-001');
});

test('opens chatbot build details in a side drawer and saves in place', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 });
  const latestBuilds = budgetBuilds(2_000_000);
  const temporaryBuild = latestBuilds[1];
  const saveRequests: unknown[] = [];
  await page.route('**/api/builds/from-chat', async (route) => {
    const requestBody = JSON.parse(route.request().postData() ?? '{}');
    saveRequests.push(requestBody);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'saved-chat-build-inline' })
    });
  });

  await openHomeAsUser(page);
  const session = storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session });
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();

  await page.getByRole('button', { name: '상세 보기' }).nth(1).click();

  await expect(page).toHaveURL('/builds/latest');
  await expect(page.getByTestId('latest-build-inline-detail')).toHaveCount(0);
  const drawer = page.getByRole('dialog', { name: '추천 조합 상세' });
  await expect(drawer).toBeVisible();
  await expect(drawer).toHaveAttribute('aria-modal', 'false');
  await expect(drawer.getByRole('heading', { name: `선택한 추천 조합 / ${temporaryBuild.title}` })).toBeVisible();
  await expect(drawer.getByRole('heading', { name: '구성 부품' })).toBeVisible();
  await expect(drawer.getByRole('heading', { name: 'Tool 검증 결과' })).toBeVisible();
  await expect(drawer.getByRole('heading', { name: '견적 요약 / 액션' })).toBeVisible();
  await expect(drawer.getByRole('link', { name: temporaryBuild.items[0].name })).toBeVisible();

  await page.getByRole('button', { name: /200만원 성능형/ }).click();
  await expect(drawer.getByRole('heading', { name: `선택한 추천 조합 / ${latestBuilds[2].title}` })).toBeVisible();

  await drawer.getByRole('button', { name: '견적 저장' }).click();

  await expect.poll(() => saveRequests.length).toBe(1);
  expect(saveRequests[0]).toMatchObject({
    sourceBuildId: latestBuilds[2].id,
    lastUserMessage: '200만원 PC 추천',
    build: {
      id: latestBuilds[2].id,
      title: latestBuilds[2].title
    }
  });
  await expect(page).toHaveURL('/builds/latest');
  await expect(drawer.getByText('내 견적함에 저장되었습니다.')).toBeVisible();
  await expect(drawer.getByRole('link', { name: '내 견적함 보기' })).toHaveAttribute('href', '/my/quotes');
  await expect(drawer.getByRole('button', { name: '견적 저장' })).toHaveCount(0);
  const savedMapping = await page.evaluate((buildId) => {
    const raw = sessionStorage.getItem('buildgraph.ai.assistantSession:user-1004');
    return raw ? JSON.parse(raw).savedBuildIds?.[buildId] : null;
  }, latestBuilds[2].id);
  expect(savedMapping).toBe('saved-chat-build-inline');
});

test('opens self quote from the drawer graph card without replacing the current cart', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 });
  await mockBuildGraphApi(page);
  const latestBuilds = budgetBuilds(2_000_000);
  const existingDraftItem: MockQuoteDraftItem = {
    id: 'existing-gpu',
    partId: 'existing-gpu',
    category: 'GPU',
    name: '기존 장바구니 GPU',
    manufacturer: '기존 제조사',
    quantity: 1,
    unitPriceAtAdd: 777_000,
    currentPrice: 777_000,
    lineTotal: 777_000,
    attributes: {}
  };
  const { applyRequests } = await mockSelfQuoteApis(page, { initialItems: [existingDraftItem] });
  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds) });
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();

  await page.getByRole('button', { name: '상세 보기' }).first().click();
  const drawer = page.getByRole('dialog', { name: '추천 조합 상세' });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole('button', { name: /노드 선택/ })).toHaveCount(0);
  await drawer.getByRole('button', { name: '견적 관계도에서 셀프 견적으로 이동' }).click();

  await expect(page.getByRole('dialog', { name: '셀프 견적 교체 확인' })).toHaveCount(0);
  expect(applyRequests).toHaveLength(0);
  await expect(page).toHaveURL('/self-quote');
  await expect(page.getByTestId('ai-selected-build-panel')).toContainText(latestBuilds[0].title);
  await expect(page.getByTestId('checklist-GPU')).toContainText('기존 장바구니 GPU');
});

test('opens self quote from the drawer graph card when the current cart is empty', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 });
  await mockBuildGraphApi(page);
  const latestBuilds = budgetBuilds(2_000_000);
  const { applyRequests } = await mockSelfQuoteApis(page);
  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds) });
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();

  await page.getByRole('button', { name: '상세 보기' }).first().click();
  const drawer = page.getByRole('dialog', { name: '추천 조합 상세' });
  await drawer.getByRole('button', { name: '견적 관계도에서 셀프 견적으로 이동' }).click();

  await expect(page.getByRole('dialog', { name: '셀프 견적 교체 확인' })).toHaveCount(0);
  expect(applyRequests).toHaveLength(0);
  await expect(page).toHaveURL('/self-quote');
  await expect(page.getByTestId('ai-selected-build-panel')).toContainText(latestBuilds[0].title);
});

test('keeps hover preview graph read-only and uses only the drawer graph card as navigation', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 });
  await mockBuildGraphApi(page);
  const latestBuilds = budgetBuilds(2_000_000);
  const { applyRequests } = await mockSelfQuoteApis(page);
  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds) });
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();

  await page.getByRole('button', { name: /200만원 실속형/ }).hover();
  const preview = page.getByTestId('latest-build-graph-preview');
  await expect(preview).toBeVisible();
  await expect(preview.getByRole('button', { name: /노드 선택/ })).toHaveCount(0);
  await expect(preview.getByRole('button', { name: '견적 관계도에서 셀프 견적으로 이동' })).toHaveCount(0);
  expect(applyRequests).toHaveLength(0);
  await expect(page).toHaveURL('/builds/latest');

  await page.getByRole('button', { name: '상세 보기' }).first().click();
  const drawer = page.getByRole('dialog', { name: '추천 조합 상세' });
  await expect(drawer.getByRole('button', { name: /노드 선택/ })).toHaveCount(0);
  await expect(drawer.getByRole('button', { name: '견적 관계도에서 셀프 견적으로 이동' })).toBeVisible();
});

test('opens the exact recommendation card details when temporary build ids are duplicated', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 });
  await mockBuildGraphApi(page);
  const duplicatedId = 'ai-engine-current-balanced-base';
  const expensiveItems = categories.map((category, index) => ({
    ...item(category, 'balanced', 2_000_000, '고가 '),
    partId: `expensive-${category.toLowerCase()}`,
    name: `고가 ${category} 부품`,
    quantity: category === 'RAM' ? 2 : 1,
    price: 640_000 + index * 10_000
  }));
  const selectedItems = categories.map((category, index) => ({
    ...item(category, 'balanced', 2_000_000, '선택 '),
    partId: `selected-${category.toLowerCase()}`,
    name: `선택 ${category} 부품`,
    quantity: category === 'RAM' ? 2 : 1,
    price: 520_000 + index * 1_000
  }));
  const latestBuilds = [
    {
      ...build('balanced', 2_000_000),
      id: duplicatedId,
      title: '512만원 균형형 추천 조합',
      summary: '첫 번째 중복 id 조합입니다.',
      totalPrice: expensiveItems.reduce((sum, next) => sum + next.price * next.quantity, 0),
      items: expensiveItems,
      toolResults: [
        { tool: 'price', status: 'PASS', confidence: 'HIGH', summary: '고가 조합 검증 결과입니다.' }
      ]
    },
    {
      ...build('balanced', 2_000_000),
      id: duplicatedId,
      title: '421만원 균형형 추천 조합',
      summary: '사용자가 선택한 두 번째 중복 id 조합입니다.',
      totalPrice: selectedItems.reduce((sum, next) => sum + next.price * next.quantity, 0),
      items: selectedItems,
      toolResults: [
        { tool: 'price', status: 'PASS', confidence: 'MEDIUM', summary: '선택한 조합 검증 결과입니다.' }
      ]
    }
  ];

  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('중복 id 추천', latestBuilds) });
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();

  const selectedCard = page.locator('[data-latest-build-card="true"]').filter({ hasText: '421만원 균형형 추천 조합' });
  await expect(selectedCard.getByText(`${latestBuilds[1].totalPrice.toLocaleString()}원`)).toBeVisible();
  await selectedCard.getByRole('button', { name: '상세 보기' }).click();

  const drawer = page.getByRole('dialog', { name: '추천 조합 상세' });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole('heading', { name: `선택한 추천 조합 / ${latestBuilds[1].title}` })).toBeVisible();
  await expect(drawer.getByText(`${latestBuilds[1].totalPrice.toLocaleString()}원`)).toBeVisible();
  await expect(drawer.getByRole('link', { name: latestBuilds[1].items[0].name })).toBeVisible();
  await expect(drawer.getByText('선택한 조합 검증 결과입니다.')).toBeVisible();
  await expect(drawer.getByText('고가 조합 검증 결과입니다.')).toHaveCount(0);
});

test('keeps recommendation cards full width while the detail drawer overlays on desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const latestBuilds = budgetBuilds(2_000_000);
  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds) });
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();

  const layout = page.getByTestId('latest-build-results-layout');
  const grid = page.getByTestId('latest-build-card-grid');
  const gridWidthBefore = (await grid.boundingBox())?.width ?? 0;

  await page.getByRole('button', { name: '상세 보기' }).first().click();
  const drawer = page.getByRole('dialog', { name: '추천 조합 상세' });
  await expect(drawer).toBeVisible();
  await expect.poll(async () => (await grid.boundingBox())?.width ?? 0).toBeCloseTo(gridWidthBefore, 0);

  await page.setViewportSize({ width: 1600, height: 900 });
  await expect(layout).toHaveCSS('margin-right', '0px');
  await expect(drawer).toBeVisible();
});

test('shows a read-only build graph preview on recommendation card hover and reuses cached graph data', async ({ page }) => {
  const buildGraphRequests = await mockBuildGraphApi(page);
  const latestBuilds = budgetBuilds(2_000_000).map((candidate, index) => index === 0
    ? {
      ...candidate,
      warnings: [
        '성능 또는 작업 적합도 여유가 낮아 상위 부품을 검토해야 합니다.',
        '저장된 현재가 기준 예산을 초과합니다.'
      ]
    }
    : candidate);
  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds) });
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();

  await page.getByRole('button', { name: /200만원 실속형/ }).hover();
  const firstCard = page.locator('[data-latest-build-card]').first();
  const preview = page.getByTestId('latest-build-graph-preview');
  await expect(preview).toBeVisible();
  await expect(preview.getByRole('heading', { name: '견적 관계도' })).toBeVisible();
  await expect(preview).toContainText(latestBuilds[0].title);
  await expect(preview).toContainText(`${latestBuilds[0].totalPrice.toLocaleString()}원`);
  await expect(preview).toContainText('부품 8개');
  await expect(preview).toContainText('경고 2건');
  await expect(preview).toContainText('주의 필요 2건');
  await expect(preview.getByText('Tool 검증 요약')).toHaveCount(0);
  await expect(preview.getByText('추천 저장 전 상세 drawer에서 Tool 검증 결과를 확인하세요.')).toHaveCount(0);
  await expect(preview.locator('.react-flow__node').filter({ hasText: 'GPU' }).filter({ hasText: '호환됨' })).toBeVisible();
  await expect(preview.locator('.react-flow__node').filter({ hasText: '파워' }).filter({ hasText: '주의' })).toBeVisible();
  await expect(preview.locator('.react-flow__node').filter({ hasText: 'RTX 5070' })).toBeVisible();
  await expect(preview.locator('.react-flow__node').filter({ hasText: '총액' })).toHaveCount(0);
  const cardBox = await firstCard.boundingBox();
  const previewBox = await preview.boundingBox();
  expect(cardBox).not.toBeNull();
  expect(previewBox).not.toBeNull();
  expect(previewBox?.x).toBeGreaterThan((cardBox?.x ?? 0) + (cardBox?.width ?? 0));
  expect(previewBox?.width).toBeGreaterThan(700);
  expect(previewBox?.width).toBeLessThan(800);
  expect(previewBox?.height).toBeGreaterThan(520);
  expect(previewBox?.height).toBeLessThan(600);
  await expect.poll(() => buildGraphRequests.length).toBe(1);
  expect(buildGraphRequests[0]).toMatchObject({
    source: 'AI_BUILD',
    budgetWon: 2_000_000
  });
  expect((buildGraphRequests[0] as { items?: unknown[] }).items?.length).toBe(8);

  await page.getByRole('heading', { name: '추천 결과' }).hover();
  await expect(preview).toHaveCount(0);
  await page.getByRole('button', { name: /200만원 실속형/ }).hover();
  await expect(page.getByTestId('latest-build-graph-preview')).toBeVisible();
  await expect.poll(() => buildGraphRequests.length).toBe(1);

  await page.getByRole('button', { name: /200만원 균형형/ }).hover();
  await expect(page.getByTestId('latest-build-graph-preview').locator('.react-flow__node').filter({ hasText: 'RTX 5070' })).toBeVisible();
  await expect.poll(() => buildGraphRequests.length).toBe(2);
});

test('shows the graph preview on card focus and suppresses hover preview while the detail drawer is open', async ({ page }) => {
  await mockBuildGraphApi(page);
  const latestBuilds = budgetBuilds(2_000_000);
  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds) });
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();

  await page.getByRole('button', { name: /200만원 실속형/ }).focus();
  await expect(page.getByTestId('latest-build-graph-preview')).toBeVisible();

  await page.getByRole('button', { name: '상세 보기' }).first().click();
  const drawer = page.getByRole('dialog', { name: '추천 조합 상세' });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole('heading', { name: '견적 관계도' })).toBeVisible();
  await expect(drawer.locator('.react-flow__node').filter({ hasText: 'RTX 5070' })).toBeVisible();

  await page.getByRole('button', { name: /200만원 실속형/ }).hover();
  await expect(page.getByTestId('latest-build-graph-preview')).toHaveCount(0);
});

test('does not show hover graph preview on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockBuildGraphApi(page);
  const latestBuilds = budgetBuilds(2_000_000);
  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds) });
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();

  await page.getByRole('button', { name: /200만원 실속형/ }).hover();
  await expect(page.getByTestId('latest-build-graph-preview')).toHaveCount(0);
});

test('closes the recommendation detail drawer with close button, escape, and outside click', async ({ page }) => {
  const latestBuilds = budgetBuilds(2_000_000);
  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds) });
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();

  await page.getByRole('button', { name: '상세 보기' }).first().click();
  await expect(page.getByRole('dialog', { name: '추천 조합 상세' })).toBeVisible();
  await page.getByRole('button', { name: '추천 조합 상세 닫기' }).click();
  await expect(page.getByRole('dialog', { name: '추천 조합 상세' })).toHaveCount(0);

  await page.getByRole('button', { name: '상세 보기' }).first().click();
  await expect(page.getByRole('dialog', { name: '추천 조합 상세' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: '추천 조합 상세' })).toHaveCount(0);

  await page.getByRole('button', { name: '상세 보기' }).first().click();
  await expect(page.getByRole('dialog', { name: '추천 조합 상세' })).toBeVisible();
  await page.getByRole('heading', { name: '추천 결과' }).click();
  await expect(page.getByRole('dialog', { name: '추천 조합 상세' })).toHaveCount(0);
});

test('opens recommendation details as a right overlay drawer on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const latestBuilds = budgetBuilds(2_000_000);
  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds) });
  await page.getByRole('navigation').getByRole('link', { name: '추천 결과' }).click();

  await page.getByRole('button', { name: '상세 보기' }).first().click();

  const drawer = page.getByTestId('latest-build-detail-drawer');
  await expect(drawer).toBeVisible();
  await expect(page.getByTestId('latest-build-detail-sheet')).toHaveCount(0);
  await expect(drawer.getByRole('heading', { name: `선택한 추천 조합 / ${latestBuilds[0].title}` })).toBeVisible();
  await expect(drawer.getByRole('heading', { name: '구성 부품' })).toBeVisible();
  await page.getByRole('button', { name: '추천 조합 상세 닫기' }).click();
  await expect(page.getByRole('dialog', { name: '추천 조합 상세' })).toHaveCount(0);
});

test('redirects a previously saved temporary chatbot build to its persisted build detail', async ({ page }) => {
  const latestBuilds = budgetBuilds(2_000_000);
  const temporaryBuild = latestBuilds[0];
  await page.route('**/api/builds/saved-chat-build-redirect', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'saved-chat-build-redirect',
        name: temporaryBuild.title,
        recommendedFor: temporaryBuild.tierLabel,
        summary: temporaryBuild.summary,
        totalPrice: temporaryBuild.totalPrice,
        confidence: temporaryBuild.confidence,
        items: temporaryBuild.items,
        warnings: [],
        evidenceIds: [],
        agentSessionId: null,
        agentSummary: null,
        changeableCategories: ['GPU', 'RAM'],
        createdAt: '2026-07-03T00:00:00Z',
        toolResults: temporaryBuild.toolResults
      })
    });
  });

  await openHomeAsUser(page);
  const session = storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds, { [temporaryBuild.id]: 'saved-chat-build-redirect' });
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session });
  await page.addInitScript(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session });
  await page.goto(`/builds/${temporaryBuild.id}`);

  await expect(page).toHaveURL('/builds/saved-chat-build-redirect');
  await expect(page.getByRole('heading', { name: `추천 Build 결과 / ${temporaryBuild.title}` })).toBeVisible();
});

test('keeps the unified home usable on mobile width', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockBuildGraphApi(page);
  await mockCompatibleCandidatesApi(page);
  await mockAiBuildChatApi(page);
  await mockCurrentQuoteDraftApi(page);
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await expect(main.getByRole('img', { name: '배틀그라운드 조립 PC 광고' })).toBeVisible();
  await expect(main.getByRole('link', { name: /PC 견적/ }).first()).toBeVisible();
  await expect(main.getByRole('link', { name: /전체 부품/ })).toBeVisible();
  await expect(main.getByRole('tab', { name: '인기상품' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'AI에게 물어보기' })).toBeHidden();
  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await expect(page.getByTestId('ai-chatbot-panel')).toBeVisible();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('200만원 PC 추천');
  await page.getByRole('button', { name: '질문 보내기' }).click();
  await expect(main.getByTestId('build-dependency-graph')).toHaveCount(0);
  await page.getByRole('button', { name: 'AI 견적 챗봇 닫기' }).click();
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCount(0);
  await main.getByTestId('home-ai-preview-card-server-2000000-balanced-base').click();
  await expect(main.getByTestId('build-dependency-graph')).toContainText('AI 추천 관계도');
  await main.getByTestId('build-dependency-graph').getByRole('button', { name: '관계 안내 닫기' }).click();
  const mobileGpuNode = main.getByTestId('graph-flow-canvas').locator('.react-flow__node').filter({ hasText: 'RTX 5070' }).first();
  await expect(mobileGpuNode).toBeVisible();
  await mobileGpuNode.dispatchEvent('click');
  await expect(main.getByTestId('graph-flow-canvas').getByTestId('graph-node-candidate-panel')).toContainText('호환 후보');
  await moveHomeFullPageDown(page);
  await expect(page.getByTestId('floating-dependency-graph')).toHaveCount(0);

  const hasBodyOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  expect(hasBodyOverflow).toBe(false);
});

test('does not fetch the quote draft until the assistant panel is opened', async ({ page }) => {
  let draftGetCount = 0;
  await page.route('**/api/quote-drafts/current', async (route) => {
    if (route.request().method() === 'GET') {
      draftGetCount += 1;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-home-empty',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [],
        totalPrice: 0,
        itemCount: 0
      })
    });
  });

  await openHomeAsUser(page);

  // 패널을 열기 전에는 현재 견적(드래프트)을 선행 조회하지 않는다
  await page.waitForTimeout(1000);
  expect(draftGetCount).toBe(0);

  // 패널을 열면 그때 draft를 미리 받는다
  await openDesktopAiAssistant(page);
  await expect.poll(() => draftGetCount).toBeGreaterThan(0);
});
