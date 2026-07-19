import { expect, test, type Locator, type Page } from '@playwright/test';

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
  const launcher = page.getByTestId('ai-chatbot-launcher');
  await expect(launcher).toBeVisible();
  await launcher.click();
  const chatbotPanel = page.getByTestId('ai-chatbot-panel');
  await expect(chatbotPanel).toBeVisible();
  await expect(chatbotPanel).toHaveCSS('width', '390px');
  await expect.poll(async () => {
    const shellMarginRight = await page.locator('.screen-shell').evaluate((element) => window.getComputedStyle(element).marginRight);
    return Number.parseFloat(shellMarginRight);
  }).toBeGreaterThan(389);
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
  const requests: Array<{ message: string; currentBuilds?: unknown[]; currentQuoteDraft?: unknown }> = [];

  await page.route('**/api/parts/part-*', async (route) => {
    const partId = decodeURIComponent(route.request().url().split('/').pop() ?? 'part-case-test');
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(partDetail(partId))
    });
  });

  await page.route('**/api/ai/build-chat', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}') as { message?: string; currentBuilds?: unknown[]; currentQuoteDraft?: unknown };
    const message = body.message ?? '';
    requests.push({ message, currentBuilds: body.currentBuilds, currentQuoteDraft: body.currentQuoteDraft });

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
  const recommendedItems = recommendedOrder
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
    .filter((item): item is NonNullable<typeof item> => Boolean(item));
  const recommendedParts = {
    items: recommendedItems,
    generatedAt: '2026-07-03T10:00:00Z',
    fallbackUsed: true
  };
  const categoryParts = categories.reduce<Record<string, typeof homeParts>>((acc, category) => {
    acc[category] = homeParts.filter((part) => part.category === category);
    return acc;
  }, {});
  const homeResponse = {
    categoryParts,
    recommendedParts
  };

  await page.route('**/api/home', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(homeResponse)
    });
  });

  await page.route('**/api/public/home', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(homeResponse)
    });
  });

  await page.route('**/api/recommendations/home-parts**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(recommendedParts)
    });
  });

  await page.route('**/api/recommendation-events/bulk', async (route) => {
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ accepted: true })
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

async function openHomeAsUser(page: Page, options: { dismissHomeChoice?: boolean } = {}) {
  const { dismissHomeChoice = true } = options;
  await page.addInitScript(({ dismissHomeChoice }) => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-1004',
      email: 'user@example.com',
      name: '테스트 사용자',
      role: 'USER'
    }));
    if (dismissHomeChoice) {
      localStorage.setItem('buildgraph.homeLoginChoice.dismissed', 'true');
    } else {
      localStorage.removeItem('buildgraph.homeLoginChoice.dismissed');
    }
  }, { dismissHomeChoice });
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

async function expectFlowNodeReady(node: Locator) {
  await expect(node).toHaveCount(1);
  await expect.poll(async () => {
    const box = await node.boundingBox();
    const isVisible = await node.evaluate((element) => {
      const style = window.getComputedStyle(element);
      return style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
    }).catch(() => false);
    return Boolean(isVisible && box && box.width > 0 && box.height > 0);
  }, { timeout: 10000 }).toBe(true);
  await expect(node).toBeVisible();
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

test('shows the login choice prompt before dismissed and opens AI flow choices', async ({ page }) => {
  await openHomeAsUser(page, { dismissHomeChoice: false });

  const firstChoiceDialog = page.getByTestId('home-login-choice-dialog');
  await expect(firstChoiceDialog).toBeVisible();
  await expect(firstChoiceDialog.getByTestId('home-login-choice-ai')).toBeVisible();
  await expect(firstChoiceDialog.getByTestId('home-login-choice-parts')).toBeVisible();

  await firstChoiceDialog.getByTestId('home-login-choice-ai').click();
  await expect(firstChoiceDialog).toHaveCount(0);

  const aiFlowDialog = page.getByTestId('home-ai-flow-choice-dialog');
  await expect(aiFlowDialog).toBeVisible();
  await expect(aiFlowDialog.getByTestId('home-ai-flow-choice-ai')).toBeVisible();
  await expect(aiFlowDialog.getByTestId('home-ai-flow-choice-self-quote')).toBeVisible();
  await expect(aiFlowDialog.getByTestId('home-ai-flow-choice-all-parts')).toBeVisible();
});

// 중앙 AI 모달은 인라인 ref로 컨테이너를 넘겨, 리렌더마다 스크롤 컨테이너가 재부착되며
// 바닥으로 튕겨 내려가던 회귀가 있었다(#255). 위로 올린 위치는 타이핑·리렌더에도 유지돼야 한다.
test('홈 중앙 AI 모달에서 위로 올린 스크롤 위치가 타이핑·리렌더에도 유지된다', async ({ page }) => {
  await mockCurrentQuoteDraftApi(page);
  const longAnswer = Array.from({ length: 12 }, (_, index) => (
    `${index + 1}번째 근거 문단입니다. 현재 구성의 전력·발열·장착 여유와 게임별 예상 성능을 근거 수치와 함께 길게 설명하는 문단입니다.`
  )).join(' ');
  await page.route('**/api/ai/build-chat', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ answerType: 'GENERAL', message: longAnswer, builds: [], warnings: [] })
    });
  });
  await openHomeAsUser(page, { dismissHomeChoice: false });
  await page.getByTestId('home-login-choice-dialog').getByTestId('home-login-choice-ai').click();
  await page.getByTestId('home-ai-flow-choice-dialog').getByTestId('home-ai-flow-choice-ai').click();

  const modal = page.getByTestId('ai-chatbot-modal');
  await expect(modal).toBeVisible();
  const input = modal.getByRole('textbox', { name: 'AI에게 PC 견적 질문' });
  await input.fill('게이밍 200만원');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  const messages = modal.getByTestId('ai-chat-messages');
  await expect(messages).toContainText('12번째 근거 문단입니다', { timeout: 20000 });
  await expect.poll(async () => messages.evaluate((element) => element.scrollHeight - element.clientHeight))
    .toBeGreaterThan(100);

  // 위로 올려 이전 대화를 읽는 중.
  await messages.evaluate((element) => { element.scrollTop = 0; });
  expect(await messages.evaluate((element) => element.scrollTop)).toBeLessThan(50);

  // 입력창에 타이핑하면 리렌더가 발생한다 — 그래도 읽던 위치가 유지돼야 한다.
  await input.pressSequentially('추천', { delay: 30 });
  expect(await messages.evaluate((element) => element.scrollTop)).toBeLessThan(50);

  // 휠로 조금 내려도 강제로 바닥까지 끌려가지 않는다.
  await messages.evaluate((element) => { element.scrollTop = 120; });
  await input.pressSequentially('해줘', { delay: 30 });
  const afterTyping = await messages.evaluate((element) => ({
    top: element.scrollTop,
    bottom: element.scrollHeight - element.clientHeight
  }));
  expect(afterTyping.top).toBeLessThan(afterTyping.bottom - 50);
});

test('renders the editorial home with the quote validation flow', async ({ page }) => {
  await mockCurrentQuoteDraftApi(page);
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await expect(main.getByRole('textbox', { name: '원하는 PC 사양 입력' })).toHaveCount(0);
  await expect(main.getByRole('img', { name: /조립 PC 광고/ })).toHaveCount(0);
  await expect(page.getByTestId('home-fullpage-scroll')).toHaveCount(0);
  await expect(main.getByRole('heading', { name: '견적부터 조립 후 AS까지, 한 흐름으로' })).toBeVisible();
  await expect(main.getByText('한 흐름으로', { exact: true })).toHaveCSS('color', 'rgb(222, 108, 45)');
  await expect(main.getByRole('link', { name: '나만의 견적 알아보기' })).toHaveCSS('background-color', 'rgb(222, 108, 45)');
  const hero = main.locator('.modern-home-hero');
  await expect(hero).toHaveCSS('border-top-width', '0px');
  await expect(hero).toHaveCSS('border-bottom-width', '1px');
  const heroViewportAlignment = await hero.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return {
      left: bounds.left,
      right: document.documentElement.clientWidth - bounds.right
    };
  });
  expect(Math.abs(heroViewportAlignment.left)).toBeLessThan(1);
  expect(Math.abs(heroViewportAlignment.right)).toBeLessThan(1);
  const primaryNav = page.getByRole('navigation', { name: '견적 및 PC 부품 카테고리' });
  const navHeroGap = await primaryNav.evaluate((nav, heroElement) => {
    const navBounds = nav.getBoundingClientRect();
    const heroBounds = (heroElement as HTMLElement).getBoundingClientRect();
    return heroBounds.top - navBounds.bottom;
  }, await hero.elementHandle());
  expect(Math.abs(navHeroGap)).toBeLessThan(1);
  await expect(main.getByTestId('home-hero-dummy-collage').locator('img.modern-home-hero-dummy')).toHaveCount(12);
  await expect(main.getByTestId('home-hero-gradient-layer')).toHaveClass(/opacity-60/);
  await expect(main.getByTestId('home-hero-gradient-layer')).toHaveClass(/from-\[#fbeee7\]/);
  const heroDescription = main.locator('.modern-home-intro p');
  await expect(heroDescription).toContainText('당신이 원하는 맞춤형 PC를 가상으로 견적을 맞추고,');
  await expect(heroDescription).toContainText('조립 및 AS 기사까지 매칭되는 올인원 플랫폼입니다.');
  await expect(main.getByRole('link', { name: '나만의 견적 알아보기' })).toHaveAttribute('href', '/self-quote');
  await expect(main.getByTestId('home-hero-process-flow')).toHaveCount(0);
  await expect(main.getByTestId('home-quick-start-panel')).toHaveCount(0);
  await expect(main.getByRole('heading', { name: '인기있는 조합' })).toBeVisible();
  await expect(main.locator('.home-featured-interest-card')).toHaveCount(4);
  await expect(main.getByTestId('home-featured-preview-card-home-featured-budget-starter')).toContainText('가성비 입문 추천팩');
  const qhdRecommendationCard = main.getByTestId('home-featured-preview-card-home-featured-qhd-gaming');
  await expect(qhdRecommendationCard).toBeVisible();
  await expect(qhdRecommendationCard.getByText('약 229만원')).toBeVisible();
  await expect(qhdRecommendationCard.getByRole('img', { name: /Home FRAME 4000D Case/ })).toBeVisible();
  await expect(qhdRecommendationCard.getByRole('button', { name: 'QHD 게이밍 추천팩 셀프 견적에 담기' })).toHaveCount(0);
  await expect(main.getByRole('heading', { name: '맞춤형 부품' })).toBeVisible();
  await expect(main.getByRole('link', { name: 'MORE >' })).toHaveAttribute('href', '/parts');
  await expect(main.getByText('다짜줘의 검증 원칙')).toHaveCount(0);
  await expect(main.getByRole('img', { name: /Home RTX 5070 GPU/ })).toBeVisible();
  await expect(main.locator('.modern-home-product-card')).toHaveCount(5);
  const firstPartCard = main.getByRole('link', { name: '인기 부품 1번 보기' });
  await expect(firstPartCard.locator('.modern-home-product-image')).toHaveCSS('aspect-ratio', '1 / 1');
  const productName = firstPartCard.getByRole('heading', { name: 'Home RTX 5070 GPU' });
  await expect(productName).toHaveCSS('font-weight', '400');
  await expect(productName).toHaveCSS('text-overflow', 'ellipsis');
  const categoryBadge = firstPartCard.getByText('GPU', { exact: true });
  await expect(categoryBadge).toHaveClass(/absolute/);
  await expect(categoryBadge).toHaveClass(/font-medium/);
  await expect(categoryBadge).toHaveClass(/text-black/);
  await expect(categoryBadge).toHaveCSS('background-color', 'rgb(255, 255, 255)');
  await expect(categoryBadge).toHaveCSS('border-color', 'rgb(148, 163, 184)');
  const productPrice = firstPartCard.getByText('890,000원', { exact: true });
  expect(await productPrice.getAttribute('class')).toContain('w-[80%]');
  await expect(productPrice).toHaveCSS('font-weight', '600');
  await expect(firstPartCard.getByText('벤치마크 점수 포함')).toHaveCount(0);
  await expect(firstPartCard.getByText('상품 정보 확인')).toHaveCount(0);
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
  await qhdRecommendationCard.click();
  await qhdRecommendationCard.getByRole('button', { name: 'QHD 게이밍 추천팩 셀프 견적에 담기' }).click();

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
  await expectFlowNodeReady(gpuNode);
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

test('chatbot uses build-chat API without replacing featured home recommendations', async ({ page }) => {
  const buildChatRequests = await mockAiBuildChatApi(page);
  await mockCurrentQuoteDraftApi(page);
  await openHomeAsUser(page);
  const main = page.getByRole('main');
  const featuredCards = main.locator('[data-testid^="home-featured-preview-card-"]');

  await expect(featuredCards).toHaveCount(4);
  await expect(main.getByTestId('home-featured-preview-card-home-featured-qhd-gaming')).toBeVisible();
  await expect(main.getByTestId('home-featured-preview-card-home-featured-budget-starter')).toBeVisible();

  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCount(0);
  await openDesktopAiAssistant(page);
  const chatbotPanel = page.getByTestId('ai-chatbot-panel');
  const chatbotInput = page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' });

  await expect(chatbotPanel.getByText('이렇게 물어보세요')).toHaveCount(0);
  await expect(chatbotPanel.getByRole('button', { name: '200만원 게이밍 PC' })).toHaveCount(0);
  await expect(chatbotPanel.getByRole('button', { name: '견적 마저 채우기' })).toHaveCount(0);
  await expect(chatbotPanel.getByRole('button', { name: '성능 비교' })).toHaveCount(0);
  await expect(chatbotPanel.getByRole('button', { name: 'PC 문제 상담' })).toHaveCount(0);
  await expect(chatbotPanel.getByRole('button', { name: '800만원 PC 추천' })).toHaveCount(0);
  await expect(chatbotPanel.getByRole('button', { name: '9950X3D 상세' })).toHaveCount(0);
  await expect(chatbotPanel.getByRole('button', { name: '내 견적함' })).toHaveCount(0);
  await chatbotInput.fill('200만원 PC 추천');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatRequests.length).toBe(1);
  expect(buildChatRequests[0].message).toBe('200만원 PC 추천');
  expect(buildChatRequests[0].currentQuoteDraft).toBeUndefined();
  const assistantMessageSurfaces = await chatbotPanel.getByTestId('ai-chat-message-assistant').evaluateAll((messages) => (
    messages.map((message) => ({
      surface: message.getAttribute('data-message-surface'),
      className: message.className
    }))
  ));
  expect(assistantMessageSurfaces.length).toBeGreaterThan(0);
  expect(assistantMessageSurfaces.every(({ surface }) => surface === 'plain')).toBe(true);
  expect(assistantMessageSurfaces.every(({ className }) => !className.includes('border') && !className.includes('bg-white') && !className.includes('shadow'))).toBe(true);
  await expect(chatbotPanel.getByTestId('ai-chat-message-user')).toHaveAttribute('data-message-surface', 'bubble');
  await expect(chatbotPanel).toContainText('AI 견적 어시스턴트');
  await expect(chatbotPanel).toContainText('이 조합으로 셀프 견적 보기');
  await expect(page.getByTestId('ai-chat-messages')).toContainText('200만원 예산 기준');
  await expect(main.getByTestId('home-ai-recommendations')).toHaveCount(0);
  await expect(featuredCards).toHaveCount(4);
  await expect(main.getByTestId('home-featured-preview-card-home-featured-qhd-gaming')).toContainText('QHD 게이밍 추천팩');
  await expect(main.getByTestId('home-featured-preview-card-home-featured-budget-starter')).toContainText('가성비 입문 추천팩');

  await chatbotInput.fill('300만원 PC 추천');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatRequests.length).toBe(2);
  await expect(page.getByTestId('ai-chat-messages')).toContainText('200만원 예산 기준');
  await expect(page.getByTestId('ai-chat-messages')).toContainText('300만원 예산 기준');
  await expect(main.getByTestId('home-ai-recommendations')).toHaveCount(0);
  await expect(featuredCards).toHaveCount(4);
});
test('chatbot gives symptom-based possibilities and connects to the separate Agent diagnosis entry', async ({ page }) => {
  let activationRequests = 0;
  await page.route('**/api/ai/build-chat', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'GENERAL',
        message: '게임·화면 멈춤 증상으로 이해했습니다. 그래픽 드라이버 충돌 등이 원인 후보로 예상됩니다.',
        builds: [],
        simulation: null,
        warnings: [],
        supportGuidance: {
          type: 'PC_AGENT_DIAGNOSTIC_ENTRY',
          scope: 'PRE_DIAGNOSIS',
          symptomCategory: 'DISPLAY_FREEZE',
          title: '게임·화면 멈춤 증상',
          summary: '그래픽 드라이버 충돌, GPU 온도·부하 불안정 등이 원인 후보로 예상됩니다.',
          possibleCauses: [
            '그래픽 드라이버 충돌',
            'GPU 온도 또는 부하 불안정',
            '메모리 또는 전원 공급 불안정'
          ],
          beforeDiagnosisChecks: [
            '문제가 발생한 게임과 시간을 기록해 주세요.',
            '재현 직후 PC Agent 진단을 실행해 주세요.'
          ],
          agentRecommendation: 'RECOMMENDED',
          actions: [
            { type: 'DOWNLOAD_PC_AGENT', label: 'PC Agent 다운로드' },
            { type: 'OPEN_SUPPORT_NEW', label: 'AS 접수 화면 보기', route: '/support/new' }
          ],
          disclaimer: '표시된 원인은 입력한 증상만으로 예상한 일반적인 가능성이며 진단 결과가 아닙니다.'
        }
      })
    });
  });
  await page.route('**/api/users/me/agent-activation-token', async (route) => {
    activationRequests += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ activationToken: 'test-agent-token' })
    });
  });
  await page.route('**/downloads/pc-agent/latest.json', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ version: 'test', downloadUrl: '/downloads/pc-agent/PCAgent.exe' })
    });
  });
  await page.route('**/downloads/pc-agent/PCAgent.exe*', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/octet-stream', body: 'test-executable' });
  });
  await openHomeAsUser(page);
  await openDesktopAiAssistant(page);

  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('게임하다 화면이 자꾸 멈춰');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  const guidance = page.getByTestId('ai-support-guidance');
  await expect(guidance).toBeVisible();
  await expect(guidance).toHaveAttribute('data-response-surface', 'plain');
  await expect(guidance).not.toHaveClass(/border-cyan-200|bg-cyan-50/);
  await expect(guidance).toContainText('진단 전 안내');
  await expect(guidance).toContainText('증상만으로 예상되는 원인');
  await expect(guidance).toContainText('그래픽 드라이버 충돌');
  await expect(guidance).toContainText('원인 확정과 지원 방식은 PC Agent');
  await expect(guidance).not.toContainText('위험도');
  await guidance.getByTestId('ai-download-pc-agent').click();
  await expect.poll(() => activationRequests).toBe(1);
  await expect(guidance).toContainText('PCAgent.zip을 내려받았습니다.');

  await guidance.getByTestId('ai-open-support-new').click();
  await expect(page).toHaveURL(/\/support\/new$/);
});

// AI 대화창에서 말한 증상을 [PC Agent로 바로 접수]가 설치된 에이전트로 그대로 전달한다 (팀장 데모 시나리오 3번).
test('chatbot support guidance submits the spoken symptom to the installed PC Agent', async ({ page }) => {
  const symptomText = '게임 중 화면이 갑자기 검게 변하고 몇 초 뒤 다시 나옵니다.';
  const guidancePayload = {
    type: 'PC_AGENT_DIAGNOSTIC_ENTRY',
    scope: 'PRE_DIAGNOSIS',
    symptomCategory: 'DISPLAY_FREEZE',
    title: '게임·화면 멈춤 증상',
    summary: '그래픽 드라이버 충돌 등이 원인 후보로 예상됩니다.',
    possibleCauses: ['그래픽 드라이버 충돌'],
    beforeDiagnosisChecks: ['재현 직후 PC Agent 진단을 실행해 주세요.'],
    agentRecommendation: 'RECOMMENDED',
    actions: [
      { type: 'DOWNLOAD_PC_AGENT', label: 'PC Agent 다운로드' },
      { type: 'OPEN_SUPPORT_NEW', label: 'AS 접수 화면 보기', route: '/support/new' }
    ],
    disclaimer: '표시된 원인은 입력한 증상만으로 예상한 가능성입니다.'
  };
  await page.route('**/api/ai/build-chat', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'GENERAL',
        message: '증상으로 이해했습니다.',
        builds: [],
        warnings: [],
        supportGuidance: guidancePayload
      })
    });
  });
  const diagnosisBodies: Array<{ symptom?: string; mode?: string; requestedChecks?: string[] }> = [];
  let diagnosisResponseStatus: 'ACCEPTED' | 'DISCONNECTED' = 'DISCONNECTED';
  await page.route('**/api/users/me/agent-diagnosis-requests', async (route) => {
    diagnosisBodies.push(JSON.parse(route.request().postData() ?? '{}'));
    if (diagnosisResponseStatus === 'DISCONNECTED') {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'AGENT_DISCONNECTED', message: 'no agent' })
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        diagnosisId: 'diag-e2e-1',
        deviceId: 'device-1',
        requestedAt: '2026-07-15T00:00:00Z',
        expiresAt: '2026-07-15T00:05:00Z',
        mode: 'LIVE',
        status: 'ACCEPTED'
      })
    });
  });
  await openHomeAsUser(page);
  await openDesktopAiAssistant(page);

  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill(symptomText);
  await page.getByRole('button', { name: '질문 보내기' }).click();

  const guidance = page.getByTestId('ai-support-guidance');
  await expect(guidance).toBeVisible();
  const submit = guidance.getByTestId('ai-agent-diagnosis-request');
  await expect(submit).toContainText('PC Agent로 바로 접수');

  // 1) 에이전트 미실행: 안내 문구가 뜨고 재시도 가능해야 한다.
  await submit.click();
  await expect(guidance.getByTestId('ai-agent-diagnosis-status')).toContainText('실행 중인 PC Agent가 없습니다');
  expect(diagnosisBodies[0].symptom).toBe(symptomText); // 대화에서 말한 증상이 그대로 전달된다
  expect(diagnosisBodies[0].mode).toBe('LIVE');

  // 2) 에이전트 연결됨: 접수 성공 + 버튼이 접수 완료로 잠긴다.
  diagnosisResponseStatus = 'ACCEPTED';
  await submit.click();
  await expect(guidance.getByTestId('ai-agent-diagnosis-status')).toContainText('PC Agent가 증상을 접수했습니다');
  await expect(submit).toContainText('접수 완료');
  await expect(submit).toBeDisabled();
  expect(diagnosisBodies[1].symptom).toBe(symptomText);
});

test('docks the desktop AI assistant and shifts the page while open', async ({ page }) => {
  await openHomeAsUser(page);
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCount(0);

  await openDesktopAiAssistant(page);
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCSS('top', '0px');
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCSS('right', '0px');
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveClass(/ai-assistant-docked-panel/);
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCSS('background-color', 'rgb(247, 247, 248)');
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCSS('border-left-width', '0px');
  await expect(page.getByTestId('ai-chatbot-panel').getByRole('heading', { name: 'AI 견적 어시스턴트' })).toHaveCount(0);
  await expect(page.getByTestId('ai-chatbot-panel')).not.toContainText('내부 견적 자산 기준 · 호환성 자동 체크');
  await expect(page.getByTestId('ai-chatbot-panel').getByRole('button', { name: 'AI 견적 챗봇 닫기' })).toBeVisible();
  await expect(page.getByTestId('ai-chatbot-panel')).not.toContainText('이렇게 물어보세요');
  await expect(page.getByTestId('ai-chatbot-panel').getByRole('button', { name: '200만원 게이밍 PC' })).toHaveCount(0);
  await expect(page.getByTestId('ai-chatbot-panel')).not.toHaveCSS('box-shadow', 'none');
  await expect(page.getByTestId('ai-chatbot-panel')).not.toContainText('대화 기록은 브라우저에만 임시 저장됩니다');
  await expect.poll(async () => {
    const shellMarginRight = await page.locator('.screen-shell').evaluate((element) => window.getComputedStyle(element).marginRight);
    return Number.parseFloat(shellMarginRight);
  }).toBeGreaterThan(389);
  await page.evaluate(() => {
    window.dispatchEvent(new Event('buildgraph.aiAssistant.close'));
  });

  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCount(0);
  await expect(page.getByTestId('ai-chatbot-launcher')).toBeVisible();
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
  const simulation = page.getByTestId('ai-simulation-result');
  await expect(simulation).toHaveAttribute('data-response-surface', 'plain');
  await expect(simulation).not.toHaveClass(/border-blue-100|bg-blue-50/);
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
    localStorage.setItem('buildgraph.homeLoginChoice.dismissed', 'true');
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
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCSS('top', '0px');
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCSS('right', '0px');
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveClass(/ai-assistant-docked-panel/);
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCSS('background-color', 'rgb(247, 247, 248)');
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCSS('border-left-width', '0px');
  await expect(page.getByTestId('ai-chatbot-panel').getByRole('heading', { name: 'AI 견적 어시스턴트' })).toHaveCount(0);
  await expect(page.getByTestId('ai-chatbot-panel')).not.toContainText('내부 견적 자산 기준 · 호환성 자동 체크');
  await expect(page.getByTestId('ai-chatbot-panel').getByRole('button', { name: 'AI 견적 챗봇 닫기' })).toBeVisible();
  await expect(page.getByTestId('ai-chatbot-panel')).not.toContainText('이렇게 물어보세요');
  await expect(page.getByTestId('ai-chatbot-panel')).not.toContainText('대화 기록은 브라우저에만 임시 저장됩니다');
  await expect.poll(async () => {
    const shellMarginRight = await page.locator('.screen-shell').evaluate((element) => window.getComputedStyle(element).marginRight);
    return Number.parseFloat(shellMarginRight);
  }).toBeGreaterThan(389);
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

test('adds a selected RAM recommendation directly and leaves a compatibility notice in chat', async ({ page }) => {
  const putBodies: Array<{ quantity?: number }> = [];
  let buildChatCalls = 0;
  let draft = {
    id: 'draft-ram-direct-add',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [{
      id: 'draft-ram-direct-add-item',
      partId: 'ram-direct-add',
      category: 'RAM',
      name: 'RAM 후보 A',
      manufacturer: 'BuildGraph',
      quantity: 2,
      unitPriceAtAdd: 234000,
      currentPrice: 234000,
      lineTotal: 468000,
      attributes: {}
    }],
    totalPrice: 468000,
    itemCount: 2
  };
  await page.route('**/api/quote-drafts/current**', async (route) => {
    const method = route.request().method();
    if (method === 'PUT') {
      const body = JSON.parse(route.request().postData() ?? '{}') as { quantity?: number };
      putBodies.push(body);
      const quantity = body.quantity ?? 1;
      draft = {
        ...draft,
        items: draft.items.map((item) => ({ ...item, quantity, lineTotal: item.unitPriceAtAdd * quantity })),
        totalPrice: 234000 * quantity,
        itemCount: quantity
      };
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(draft) });
  });
  await page.route('**/api/build-graphs/resolve', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...buildGraphResponse(),
        compositeScore: { score: 0, maxScore: 1000, grade: 'F', label: '확인 필요', summary: '메모리 슬롯 초과', components: [], caps: [] },
        toolResults: [{ tool: 'compatibility', status: 'FAIL', confidence: 'HIGH', summary: '메모리 슬롯 수를 초과했습니다.' }]
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
        answerType: 'PART',
        message: '32GB 조건에 맞는 RAM 후보입니다.',
        builds: [],
        warnings: [],
        quickReplies: ['RAM 후보 A 견적에 담아줘'],
        quickReplyCommands: [{
          label: 'RAM 후보 A 견적에 담아줘',
          type: 'ADD_MULTI_ITEM_TO_DRAFT',
          partId: 'ram-direct-add',
          partName: 'RAM 후보 A',
          category: 'RAM',
          quantityDelta: 1
        }]
      })
    });
  });

  await openDesktopAiAssistant(page);
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('32GB RAM 최저가로 추천해줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();
  await expect.poll(() => buildChatCalls).toBe(1);
  await page.getByRole('button', { name: 'RAM 후보 A 견적에 담아줘' }).click();

  await expect.poll(() => putBodies).toEqual([{ quantity: 3 }]);
  const messages = page.getByTestId('ai-chat-messages');
  // 담기 확인문은 점수 영수증으로 대체돼도 "어떤 상품이 몇 개가 됐는지"를 첫 줄에 그대로 남긴다.
  await expect(messages).toContainText('RAM 후보 A 추가됨 · 현재 수량 3개');
  await expect(messages).toContainText('변경은 반영됐지만 호환성 또는 장착 문제로 종합 점수는 0점입니다.');
  await expect(messages).toContainText('현재 견적에 호환성 확인이 필요한 항목이 있습니다.');
  // 추천 칩은 LLM으로 다시 보내지 않고 기존 quote draft item API만 호출한다.
  expect(buildChatCalls).toBe(1);
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

test('selects a chatbot recommendation and shows the applied cart without a later remove action', async ({ page }) => {
  await mockBuildGraphApi(page);
  await mockAiBuildChatApi(page);
  const { applyRequests } = await mockSelfQuoteApis(page, { staleGetAfterApply: true, getDelayAfterApplyMs: 10_000 });
  await openHomeAsUser(page);

  await page.goto('/self-quote');
  await expect(page.getByTestId('quote-summary-bar')).toContainText('0 / 8');
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
  await expect(page.getByRole('heading', { name: '셀프 견적 · 구성 관계도' })).toHaveCount(0);
  await expect(page.getByTestId('quote-summary-bar')).toContainText(`${expectedTotal}원`);
  await expect(page.getByText('서버 반영 RTX 5070 서버 GPU').first()).toBeVisible();
  // 슬롯 카드의 제거 버튼은 실장도 보기에서 노출된다(기본 배치도는 hover X 버튼이 담당).
  await page.getByRole('button', { name: '실장도 보기' }).click();
  await expect(page.getByRole('button', { name: /서버 반영 RTX 5070 서버 GPU 견적에서 제거/ })).toBeVisible();
});

test('renders the Etsy-style header and PC category navigation', async ({ page }) => {
  await openHomeAsUser(page);
  await expect(page.locator('.modern-home-screen')).toHaveCSS('background-color', 'rgb(255, 255, 255)');
  const header = page.locator('header');
  const nav = page.getByRole('navigation', { name: '견적 및 PC 부품 카테고리' });

  await expect(header.getByRole('link', { name: '다짜줘 홈' })).toHaveAttribute('href', '/');
  await expect(header.getByTestId('header-logo-placeholder')).toHaveCount(0);
  const dazzajoBrand = header.getByText('Dazzajo', { exact: true });
  await expect(dazzajoBrand).toBeVisible();
  await expect(dazzajoBrand).toHaveCSS('font-size', '33.6px');
  await expect(dazzajoBrand).toHaveCSS('font-family', 'Outfit, sans-serif');
  await expect(dazzajoBrand).toHaveCSS('font-weight', '500');
  await expect(dazzajoBrand).toHaveCSS('color', 'rgb(222, 108, 45)');
  const generalSearchMode = header.getByRole('button', { name: '일반검색', exact: true });
  const aiSearchMode = header.getByRole('button', { name: 'AI 검색', exact: true });
  await expect(generalSearchMode).toHaveAttribute('aria-pressed', 'false');
  await expect(aiSearchMode).toHaveAttribute('aria-pressed', 'true');
  await expect(generalSearchMode).toHaveCSS('font-size', '15px');
  await expect(aiSearchMode).toHaveCSS('font-size', '15px');
  await expect(aiSearchMode).toHaveCSS('border-bottom-color', 'rgb(222, 108, 45)');
  await expect(aiSearchMode).toHaveCSS('border-bottom-width', '2px');
  await generalSearchMode.click();
  await expect(generalSearchMode).toHaveAttribute('aria-pressed', 'true');
  await expect(generalSearchMode).toHaveCSS('border-bottom-color', 'rgb(222, 108, 45)');
  await expect(aiSearchMode).toHaveAttribute('aria-pressed', 'false');
  await expect(header.getByRole('textbox', { name: '부품 일반 검색' })).toHaveAttribute('placeholder', /부품명이나 모델명을 검색/);
  await expect(header.getByRole('button', { name: '부품 검색' })).toHaveCSS('background-color', 'rgb(222, 108, 45)');
  // 일반검색 모드 확인을 마쳤으니 AI 검색 모드로 되돌려 이어지는 AI 검색 흐름을 검증한다.
  await aiSearchMode.click();
  await expect(aiSearchMode).toHaveAttribute('aria-pressed', 'true');
  await expect(header.getByRole('textbox', { name: 'AI에게 견적 질문하기' })).toHaveAttribute('placeholder', /어떤 PC를 맞춰드릴까요/);
  const aiSearchSubmit = header.getByRole('button', { name: 'AI 견적 검색' });
  await expect(aiSearchSubmit).toHaveCSS('background-color', 'rgb(222, 108, 45)');
  await expect(aiSearchSubmit).toHaveCSS('color', 'rgb(255, 255, 255)');
  const searchCenterOffset = await header.getByTestId('header-ai-search').evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return Math.abs(bounds.left + bounds.width / 2 - window.innerWidth / 2);
  });
  expect(searchCenterOffset).toBeLessThan(2);
  const headerSearchInput = header.getByRole('textbox', { name: 'AI에게 견적 질문하기' });
  await headerSearchInput.fill('QHD 게임용 PC 추천');
  await headerSearchInput.press('Enter');
  await expect(page.getByTestId('ai-chatbot-panel')).toBeVisible();
  await expect.poll(async () => {
    const shellMarginRight = await page.locator('.screen-shell').evaluate((element) => window.getComputedStyle(element).marginRight);
    return Number.parseFloat(shellMarginRight);
  }).toBeGreaterThan(389);
  const savedQuotesLink = header.getByRole('link', { name: '내 견적함' });
  await expect(savedQuotesLink).toHaveAttribute('href', '/my/quotes');
  await expect(savedQuotesLink.locator('svg')).toHaveAttribute('width', '21');
  await expect(header.getByRole('link', { name: '현재 견적' })).toHaveCount(0);
  await expect(header.getByText('내 견적함', { exact: true })).toHaveCount(0);
  await expect(header.getByText('현재 견적', { exact: true })).toHaveCount(0);
  await expect(header.getByText('계정', { exact: true })).toHaveCount(0);
  await expect(header.getByTestId('header-account-slot')).toHaveCSS('width', '118px');
  await expect(header.getByTestId('header-account-name')).toHaveText('테스트 사용자');
  const accountName = header.getByTestId('header-account-name');
  await expect(accountName).toHaveCSS('font-size', '15px');
  await expect(header.locator('summary[aria-label="계정 메뉴: 테스트 사용자"] svg').first()).toHaveAttribute('width', '21');
  const textCenter = async (locator: typeof dazzajoBrand) => locator.evaluate((element) => {
    const range = document.createRange();
    range.selectNodeContents(element);
    const bounds = range.getBoundingClientRect();
    return bounds.top + bounds.height / 2;
  });
  const [dazzajoCenter, generalSearchCenter, aiSearchCenter, accountNameCenter] = await Promise.all([
    textCenter(dazzajoBrand),
    textCenter(generalSearchMode),
    textCenter(aiSearchMode),
    textCenter(accountName)
  ]);
  const alignedTextCenters = [generalSearchCenter, aiSearchCenter, accountNameCenter];
  expect(Math.max(...alignedTextCenters) - Math.min(...alignedTextCenters)).toBeLessThan(1.5);
  const alignedTextCenter = alignedTextCenters.reduce((sum, value) => sum + value, 0) / alignedTextCenters.length;
  expect(dazzajoCenter - alignedTextCenter).toBeGreaterThanOrEqual(-2.75);
  expect(dazzajoCenter - alignedTextCenter).toBeLessThanOrEqual(-0.75);
  await header.locator('summary[aria-label="계정 메뉴: 테스트 사용자"]').click();
  await expect(header.getByRole('link', { name: 'AS 접수' })).toHaveAttribute('href', '/support/new');
  await expect(nav.getByRole('button', { name: 'AI 견적' })).toBeVisible();
  await expect(nav.getByRole('link', { name: '셀프 견적' })).toHaveAttribute('href', '/self-quote');
  await expect(nav.getByRole('link', { name: 'CPU' })).toHaveAttribute('href', '/parts?category=CPU');
  await expect(nav.getByRole('link', { name: 'GPU' })).toHaveAttribute('href', '/parts?category=GPU');
  await expect(nav.getByRole('link', { name: '전체 부품' })).toHaveAttribute('href', '/parts');
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
  await page.goto('/builds/latest');

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

  await page.goto('/builds/latest');
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

  await page.goto('/builds/latest');
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
  await page.goto('/builds/latest');
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
  await page.goto('/builds/latest');

  await expect(page).toHaveURL('/builds/latest');
  await expect(page.getByRole('heading', { name: '추천 결과' })).toBeVisible();
  await expect(page.getByText('AI 챗봇에게 먼저 추천을 받아보세요')).toBeVisible();
  // 홈 이동만 하는 것이 아니라 '?assistant=open'으로 챗봇까지 실제로 연다(흐름 수선).
  await expect(page.getByRole('link', { name: '홈에서 AI 챗봇 열기' })).toHaveAttribute('href', '/?assistant=open');
  expect(historyRequests).toHaveLength(0);
});

test('renders a temporary chatbot build detail and saves it to a persisted build', async ({ page }) => {
  const latestBuilds = budgetBuilds(2_000_000);
  const temporaryBuild = latestBuilds[1];
  const saveRequests: unknown[] = [];
  const buildGraphRequests = await mockBuildGraphApi(page);
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

  await expect(page.getByRole('heading', { name: `추천 견적 결과 / ${temporaryBuild.title}` })).toBeVisible();
  await expect(page.getByText('저장 전 AI 챗봇 추천')).toBeVisible();
  await expect(page.getByRole('link', { name: temporaryBuild.items[0].name })).toBeVisible();
  const partsPanel = page.getByRole('heading', { name: '구성 부품' }).locator('..').locator('..').locator('..');
  const partsTable = partsPanel.getByRole('table');
  await expect(partsTable.getByRole('columnheader', { name: '호환성' })).toBeVisible();
  await expect(partsTable.getByText('활성', { exact: true })).toHaveCount(0);
  const gpuItem = temporaryBuild.items.find((item) => item.category === 'GPU');
  const gpuRow = partsTable.getByRole('row').filter({ hasText: gpuItem?.name ?? 'GPU' });
  await expect(gpuRow.getByText('주의', { exact: true })).toBeVisible();
  await expect.poll(() => buildGraphRequests.length).toBeGreaterThan(0);
  expect(buildGraphRequests[0]).toMatchObject({
    source: 'AI_BUILD',
    items: expect.arrayContaining([
      expect.objectContaining({ category: 'GPU', partId: gpuItem?.partId, quantity: gpuItem?.quantity })
    ])
  });
  await expect(page.getByRole('heading', { name: '검증 결과' })).toHaveCount(0);
  const summaryPanel = page.getByRole('heading', { name: '견적 요약 / 액션' }).locator('..').locator('..').locator('..');
  await expect(partsPanel.getByText('검증 요약', { exact: true })).toBeVisible();
  await expect(partsPanel.getByText('1/1 통과', { exact: true })).toBeVisible();
  await expect(summaryPanel.getByText('검증 요약', { exact: true })).toHaveCount(0);
  const [partsBox, summaryBox] = await Promise.all([partsPanel.boundingBox(), summaryPanel.boundingBox()]);
  expect(partsBox).not.toBeNull();
  expect(summaryBox).not.toBeNull();
  expect(Math.abs((partsBox?.height ?? 0) - (summaryBox?.height ?? 0))).toBeLessThanOrEqual(1);
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
  await page.goto('/builds/latest');

  await page.getByRole('button', { name: '상세 보기' }).nth(1).click();

  await expect(page).toHaveURL('/builds/latest');
  await expect(page.getByTestId('latest-build-inline-detail')).toHaveCount(0);
  const drawer = page.getByRole('dialog', { name: '추천 조합 상세' });
  await expect(drawer).toBeVisible();
  await expect(drawer).toHaveAttribute('aria-modal', 'false');
  await expect(drawer.getByRole('heading', { name: `선택한 추천 조합 / ${temporaryBuild.title}` })).toBeVisible();
  await expect(drawer.getByRole('heading', { name: '구성 부품' })).toBeVisible();
  await expect(drawer.getByRole('heading', { name: '검증 결과' })).toBeVisible();
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
  await page.goto('/builds/latest');

  await page.getByRole('button', { name: '상세 보기' }).first().click();
  const drawer = page.getByRole('dialog', { name: '추천 조합 상세' });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole('button', { name: /노드 선택/ })).toHaveCount(0);
  await drawer.getByRole('button', { name: '견적 관계도에서 셀프 견적으로 이동' }).click();

  await expect(page.getByRole('dialog', { name: '셀프 견적 교체 확인' })).toHaveCount(0);
  expect(applyRequests).toHaveLength(0);
  await expect(page).toHaveURL('/self-quote');
  await expect(page.getByTestId('ai-selected-build-panel')).toHaveCount(0);
  await expect(page.getByTestId('ai-chatbot-panel')).toBeVisible();
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
  await page.goto('/builds/latest');

  await page.getByRole('button', { name: '상세 보기' }).first().click();
  const drawer = page.getByRole('dialog', { name: '추천 조합 상세' });
  await drawer.getByRole('button', { name: '견적 관계도에서 셀프 견적으로 이동' }).click();

  await expect(page.getByRole('dialog', { name: '셀프 견적 교체 확인' })).toHaveCount(0);
  expect(applyRequests).toHaveLength(0);
  await expect(page).toHaveURL('/self-quote');
  await expect(page.getByTestId('ai-selected-build-panel')).toHaveCount(0);
  await expect(page.getByTestId('ai-chatbot-panel')).toBeVisible();
  await expect(page.getByTestId('quote-start-banner')).toBeVisible();
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
  await page.goto('/builds/latest');

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
  await page.goto('/builds/latest');

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
  await page.goto('/builds/latest');

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
  await page.goto('/builds/latest');

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
  await expect(preview.getByText('검증 요약')).toHaveCount(0);
  await expect(preview.getByText('추천 저장 전 상세 drawer에서 Tool 검증 결과를 확인하세요.')).toHaveCount(0);
  await expectFlowNodeReady(preview.locator('.react-flow__node').filter({ hasText: 'GPU' }).filter({ hasText: '호환 가능' }));
  await expectFlowNodeReady(preview.locator('.react-flow__node').filter({ hasText: '파워' }).filter({ hasText: '주의' }));
  await expectFlowNodeReady(preview.locator('.react-flow__node').filter({ hasText: 'RTX 5070' }));
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
  await expectFlowNodeReady(page.getByTestId('latest-build-graph-preview').locator('.react-flow__node').filter({ hasText: 'RTX 5070' }));
  await expect.poll(() => buildGraphRequests.length).toBe(2);
});

test('shows the graph preview on card focus and suppresses hover preview while the detail drawer is open', async ({ page }) => {
  await mockBuildGraphApi(page);
  const latestBuilds = budgetBuilds(2_000_000);
  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds) });
  await page.goto('/builds/latest');

  await page.getByRole('button', { name: /200만원 실속형/ }).focus();
  await expect(page.getByTestId('latest-build-graph-preview')).toBeVisible();

  await page.getByRole('button', { name: '상세 보기' }).first().click();
  const drawer = page.getByRole('dialog', { name: '추천 조합 상세' });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole('heading', { name: '견적 관계도' })).toBeVisible();
  await expectFlowNodeReady(drawer.locator('.react-flow__node').filter({ hasText: 'RTX 5070' }));

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
  await page.goto('/builds/latest');

  await page.getByRole('button', { name: /200만원 실속형/ }).hover();
  await expect(page.getByTestId('latest-build-graph-preview')).toHaveCount(0);
});

test('closes the recommendation detail drawer with close button, escape, and outside click', async ({ page }) => {
  const latestBuilds = budgetBuilds(2_000_000);
  await openHomeAsUser(page);
  await page.evaluate(({ session }) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-1004', JSON.stringify(session));
  }, { session: storedAssistantSessionWithBuilds('200만원 PC 추천', latestBuilds) });
  await page.goto('/builds/latest');

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
  await page.goto('/builds/latest');

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
  await expect(page.getByRole('heading', { name: `추천 견적 결과 / ${temporaryBuild.title}` })).toBeVisible();
});

test('keeps the editorial home usable on mobile width', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockBuildGraphApi(page);
  await mockCompatibleCandidatesApi(page);
  await mockAiBuildChatApi(page);
  await mockCurrentQuoteDraftApi(page);
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await expect(main.getByRole('heading', { name: '견적부터 조립 후 AS까지, 한 흐름으로' })).toBeVisible();
  await expect(main.locator('.modern-home-hero')).toHaveCSS('border-top-width', '0px');
  await expect(main.locator('.modern-home-hero')).toHaveCSS('border-bottom-width', '1px');
  await expect(page.getByTestId('home-quick-start-panel')).toHaveCount(0);
  await expect(main.getByRole('link', { name: '나만의 견적 알아보기' })).toHaveAttribute('href', '/self-quote');
  await expect(page.getByTestId('ai-chatbot-panel')).toHaveCount(0);
  await expect(main.getByTestId('build-dependency-graph')).toHaveCount(0);

  const hasBodyOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  expect(hasBodyOverflow).toBe(false);
});

test('allows guests to view the public home and requires login for self quote', async ({ page }) => {
  let authenticatedHomeCalls = 0;
  await page.addInitScript(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
  await page.route('**/api/parts**', async (route) => {
    authenticatedHomeCalls += 1;
    await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 'UNAUTHORIZED' }) });
  });
  await page.route('**/api/recommendations/home-parts**', async (route) => {
    authenticatedHomeCalls += 1;
    await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 'UNAUTHORIZED' }) });
  });
  await page.route('**/api/public/home', async (route) => {
    const categoryParts = Object.fromEntries(
      ['CPU', 'GPU', 'MOTHERBOARD', 'RAM', 'STORAGE', 'PSU', 'CASE', 'COOLER'].map((category) => [category, [{
        id: `public-${category.toLowerCase()}`,
        category,
        name: `Public ${category}`,
        manufacturer: 'BuildGraph',
        price: 100000,
        status: 'ACTIVE',
        attributes: {},
        externalOffer: null
      }]])
    );
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        categoryParts,
        recommendedParts: {
          items: [{
            recommendationId: 'public-home-cpu',
            rankPosition: 0,
            part: categoryParts.CPU[0],
            scoreSource: 'FALLBACK',
            modelVersion: null,
            reasonTags: []
          }],
          generatedAt: '2026-07-14T00:00:00Z',
          fallbackUsed: true
        }
      })
    });
  });

  await page.goto('/');

  await expect(page).toHaveURL('/');
  await expect(page.getByRole('heading', { name: /견적부터 조립 후 AS까지/ })).toBeVisible();
  await expect(page.getByTestId('home-login-choice-dialog')).toHaveCount(0);
  await expect.poll(() => authenticatedHomeCalls).toBe(0);
  const guestAccountSlot = page.getByTestId('header-account-slot');
  await expect(guestAccountSlot).toHaveCSS('width', '118px');
  await expect(page.getByTestId('header-account-name')).toHaveText('');
  await expect(page.getByRole('link', { name: '계정 로그인' })).toHaveAttribute('href', '/login');

  await page.getByRole('link', { name: '나만의 견적 알아보기' }).click();
  await expect(page).toHaveURL(/\/login\?redirect=%2Fself-quote/);
});

// AI 챗봇 응답 대기 표시 — 느린 LLM 응답 구간에만 박스 없는 상태 행을 띄운다.
// 신규 describe 블록으로만 추가하고 기존 테스트/헬퍼는 수정하지 않는다.
test.describe('AI 챗봇 응답 대기 표시', () => {
  const CHAT_INPUT = 'AI 챗봇에게 PC 사양 질문';
  const SEND_BUTTON = '질문 보내기';

  // 지연·상태코드를 제어할 수 있는 build-chat mock. LIFO 라우팅이라 다른 mock 뒤에 등록하면 우선한다.
  async function mockBuildChat(page: Page, options: { delayMs?: number; status?: number; message?: string; builds?: ReturnType<typeof budgetBuilds> } = {}) {
    const { delayMs = 0, status = 200, message = '200만원 예산 기준으로 조합을 계산했습니다.', builds = [] } = options;
    const state = { calls: 0 };
    await page.route('**/api/ai/build-chat', async (route) => {
      state.calls += 1;
      if (delayMs > 0) {
        await new Promise((resolve) => setTimeout(resolve, delayMs));
      }
      if (status !== 200) {
        await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ message: '서버 오류' }) });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ answerType: 'BUDGET', message, builds, warnings: [] })
      });
    });
    return state;
  }

  async function openAssistant(page: Page) {
    await mockCurrentQuoteDraftApi(page);
    await openHomeAsUser(page);
    await openDesktopAiAssistant(page);
    const panel = page.getByTestId('ai-chatbot-panel');
    return {
      panel,
      input: page.getByRole('textbox', { name: CHAT_INPUT }),
      send: page.getByRole('button', { name: SEND_BUTTON })
    };
  }

  test('느린 응답 동안 박스 없는 대기 표시와 요약 인용을 보여준다', async ({ page }) => {
    await mockBuildChat(page, { delayMs: 1500 });
    const { panel, input, send } = await openAssistant(page);

    await input.fill('200만원 게이밍 PC 추천');
    await send.click();

    const pending = panel.getByTestId('ai-chat-pending');
    await expect(pending).toBeVisible();
    await expect(pending).toHaveAttribute('data-response-surface', 'plain');
    const pendingSurface = await pending.evaluate((element) => {
      const style = window.getComputedStyle(element);
      return {
        backgroundColor: style.backgroundColor,
        borderTopWidth: style.borderTopWidth,
        boxShadow: style.boxShadow
      };
    });
    expect(pendingSurface).toEqual({
      backgroundColor: 'rgba(0, 0, 0, 0)',
      borderTopWidth: '0px',
      boxShadow: 'none'
    });
    await expect(pending).toContainText('답변을 준비하고 있어요');
    await expect(panel.getByTestId('ai-chat-pending-excerpt')).toContainText('200만원 게이밍 PC 추천');
  });

  test('요약 인용은 30자에서 잘리고 말줄임표가 붙는다', async ({ page }) => {
    await mockBuildChat(page, { delayMs: 1500 });
    const { panel, input, send } = await openAssistant(page);

    const unit = '가나다라마바사아자차'; // 10자
    await input.fill(unit.repeat(4)); // 40자
    await send.click();

    const excerpt = panel.getByTestId('ai-chat-pending-excerpt');
    await expect(excerpt).toBeVisible();
    const text = (await excerpt.textContent()) ?? '';
    expect(text).toContain(unit.repeat(3)); // 앞 30자는 남는다
    expect(text).toContain('…');
    expect(text).not.toContain(unit.repeat(4)); // 원문 40자는 잘려서 없다
  });

  test('응답이 도착하면 대기 버블이 사라지고 실제 답변만 남는다', async ({ page }) => {
    await mockBuildChat(page, { delayMs: 700, message: '실제 추천 답변이 도착했습니다.' });
    const { panel, input, send } = await openAssistant(page);

    await input.fill('200만원 PC 추천');
    await send.click();

    await expect(panel.getByTestId('ai-chat-pending')).toBeVisible();
    await expect(panel).toContainText('실제 추천 답변이 도착했습니다.');
    await expect(panel.getByTestId('ai-chat-pending')).toHaveCount(0); // 실답과 대기 버블은 공존하지 않는다
  });

  test('300ms 이내 빠른 응답에는 대기 버블이 뜨지 않는다', async ({ page }) => {
    const state = await mockBuildChat(page, { delayMs: 40, message: '빠른 응답 도착.' });
    const { panel, input, send } = await openAssistant(page);

    await input.fill('200만원 PC 추천');
    await send.click();

    await expect(panel).toContainText('빠른 응답 도착.');
    expect(state.calls).toBe(1);
    await expect(panel.getByTestId('ai-chat-pending')).toHaveCount(0);
  });

  test('빠른 카테고리 이동은 API 없이 처리되고 대기 버블을 만들지 않는다', async ({ page }) => {
    const state = await mockBuildChat(page, { delayMs: 1500 });
    const { input, send } = await openAssistant(page);

    await input.fill('메인보드 보여줘');
    await send.click();

    await expect(page).toHaveURL(/\/self-quote\?category=MOTHERBOARD/);
    expect(state.calls).toBe(0);
    await expect(page.getByTestId('ai-chat-pending')).toHaveCount(0);
  });

  test('서버 오류 시 대기 버블이 사라지고 기존 오류 안내가 뜬다', async ({ page }) => {
    await mockBuildChat(page, { delayMs: 500, status: 500 });
    const { panel, input, send } = await openAssistant(page);

    await input.fill('200만원 PC 추천');
    await send.click();

    await expect(panel.getByRole('alert')).toContainText('AI 추천 API 호출에 실패했습니다');
    await expect(panel.getByTestId('ai-chat-pending')).toHaveCount(0);
  });

  test('전송 중 중복 제출은 무시되어 대기 버블이 중복되지 않는다', async ({ page }) => {
    const state = await mockBuildChat(page, { delayMs: 900, message: '단일 응답만 반영.' });
    const { panel, input, send } = await openAssistant(page);

    await input.fill('200만원 PC 추천');
    await send.click();
    await expect(panel.getByTestId('ai-chat-pending')).toBeVisible();
    await send.click({ force: true }); // isSending 가드로 두 번째 제출은 막혀야 한다

    await expect(panel.getByTestId('ai-chat-pending')).toHaveCount(1);
    await expect(panel).toContainText('단일 응답만 반영.');
    await expect(panel.getByTestId('ai-chat-pending')).toHaveCount(0);
    expect(state.calls).toBe(1);
  });

  test('모션 최소화 설정에서는 점 애니메이션이 정지한다', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await mockBuildChat(page, { delayMs: 1500 });
    const { panel, input, send } = await openAssistant(page);

    await input.fill('200만원 PC 추천');
    await send.click();

    const dot = panel.getByTestId('ai-chat-pending-dot').first();
    await expect(dot).toBeVisible();
    const animationName = await dot.evaluate((element) => window.getComputedStyle(element).animationName);
    expect(animationName).toBe('none');
  });

  test('다중 문장 답변은 문장 단위로 순차 노출된다', async ({ page }) => {
    const first = '첫 번째 문장입니다.';
    const last = '마지막 세 번째 문장으로 끝납니다.';
    await mockBuildChat(page, { delayMs: 300, message: `${first} 두 번째 문장이 이어집니다. ${last}` });
    const { panel, input, send } = await openAssistant(page);

    await input.fill('200만원 PC 추천');
    await send.click();

    // 첫 문장은 곧바로 보이지만 마지막 문장은 아직 노출되지 않는다(한 번에 팍 뜨지 않음).
    await expect(panel).toContainText(first);
    await expect(panel).not.toContainText(last, { timeout: 150 });

    // 잠시 뒤 세 문장이 모두 노출된다.
    await expect(panel).toContainText(last);
    await expect(panel.getByTestId('ai-message-sentence')).toHaveCount(3);
  });

  test('모션 최소화 설정에서는 답변이 문장 분할 없이 한 번에 노출된다', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const last = '마지막 세 번째 문장으로 끝납니다.';
    await mockBuildChat(page, { delayMs: 300, message: `첫 번째 문장입니다. 두 번째 문장이 이어집니다. ${last}` });
    const { panel, input, send } = await openAssistant(page);

    await input.fill('200만원 PC 추천');
    await send.click();

    await expect(panel).toContainText(last); // 전체가 즉시 노출
    await expect(panel.getByTestId('ai-message-sentence')).toHaveCount(0); // 문장 span 없이 통짜 렌더
  });

  test('긴 설명과 번호 후보를 읽기 좋은 문단과 줄바꿈으로 표시한다', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await mockBuildChat(page, {
      delayMs: 50,
      message: '조건을 만족하는 케이스 TOP3입니다. 1) 첫 번째 케이스 — 100,000원 2) 두 번째 케이스 — 120,000원 3) 세 번째 케이스 — 140,000원 담고 싶은 부품이 있으면 말씀해 주세요.'
    });
    const { panel, input, send } = await openAssistant(page);

    await input.fill('케이스 추천해줘');
    await send.click();

    const paragraphs = panel.getByTestId('ai-chat-message-assistant').last().getByTestId('ai-message-paragraph');
    await expect(paragraphs).toHaveCount(5);
    await expect(paragraphs.nth(0)).toHaveText('조건을 만족하는 케이스 TOP3입니다.');
    await expect(paragraphs.nth(1)).toHaveText('1) 첫 번째 케이스 — 100,000원');
    await expect(paragraphs.nth(2)).toHaveText('2) 두 번째 케이스 — 120,000원');
    await expect(paragraphs.nth(3)).toHaveText('3) 세 번째 케이스 — 140,000원');
    await expect(paragraphs.nth(4)).toHaveText('담고 싶은 부품이 있으면 말씀해 주세요.');
  });

  test('카드형(견적) 답변은 카드가 하나씩 순차로 노출된다', async ({ page }) => {
    await mockBuildChat(page, { delayMs: 300, message: '요청하신 예산으로 세 조합을 계산했어요.', builds: budgetBuilds(2_000_000) });
    const { panel, input, send } = await openAssistant(page);

    await input.fill('200만원 PC 추천');
    await send.click();

    const cards = panel.getByTestId('ai-build-card');
    // 첫 카드가 뜬 직후엔 3장이 한꺼번에 뜨지 않는다(확 나오지 않고 순차).
    await expect(cards.first()).toBeVisible();
    await expect(cards).not.toHaveCount(3, { timeout: 150 });
    // 잠시 뒤 3장이 모두 노출된다.
    await expect(cards).toHaveCount(3);

    // AI 견적 카드는 툴 통과 상태·부품 목록 없이, 담기 버튼과 조합별 특이점(짧은 설명)을 보여준다.
    const firstCard = cards.first();
    await expect(firstCard).toContainText('이 조합으로 셀프 견적 보기');
    await expect(firstCard).not.toContainText('통과'); // 툴 검증 칩 제거
    await expect(firstCard).not.toContainText('서버 추천'); // 부품명 미노출(잘림 방지)
    // 조합마다 특이점 설명이 달라야 한다: 최저가는 가성비, 최고가는 고사양.
    await expect(cards.first()).toContainText('가성비');
    await expect(cards.nth(2)).toContainText('고사양');
  });

  test('모션 최소화 설정에서는 카드가 한 번에 노출된다', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await mockBuildChat(page, { delayMs: 300, message: '요청하신 예산으로 세 조합을 계산했어요.', builds: budgetBuilds(2_000_000) });
    const { panel, input, send } = await openAssistant(page);

    await input.fill('200만원 PC 추천');
    await send.click();

    // 첫 카드가 보이는 순간 이미 3장이 모두 있어야 한다(순차 노출 없음).
    await expect(panel.getByTestId('ai-build-card').first()).toBeVisible();
    await expect(panel.getByTestId('ai-build-card')).toHaveCount(3);
  });
});
