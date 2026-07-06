import { expect, test, type Page } from '@playwright/test';

const checkoutDraft = {
  id: 'draft-checkout-test',
  status: 'ACTIVE',
  name: '셀프 견적',
  items: [
    {
      id: 'draft-item-checkout-gpu',
      partId: 'part-checkout-gpu',
      category: 'GPU',
      name: 'RTX 5070 구매 테스트',
      manufacturer: 'NVIDIA',
      quantity: 1,
      unitPriceAtAdd: 980000,
      currentPrice: 980000,
      lineTotal: 980000,
      attributes: {},
      externalOffer: {
        imageUrl: 'https://example.test/checkout-gpu.png',
        supplierName: '그래픽테스트몰',
        offerUrl: 'https://example.test/checkout-gpu',
        lowPrice: 980000,
        source: 'NAVER_SHOPPING_SEARCH'
      }
    },
    {
      id: 'draft-item-checkout-cpu',
      partId: 'part-checkout-cpu',
      category: 'CPU',
      name: 'Ryzen 7 구매 테스트',
      manufacturer: 'AMD',
      quantity: 1,
      unitPriceAtAdd: 420000,
      currentPrice: 420000,
      lineTotal: 420000,
      attributes: {},
      externalOffer: {
        imageUrl: 'https://example.test/checkout-cpu.png',
        supplierName: '구매처 미확인',
        offerUrl: null,
        lowPrice: 420000,
        source: 'MANUAL_CURRENT_LINEUP'
      }
    }
  ],
  totalPrice: 1400000,
  itemCount: 2
};

function buildGraphResponse(mode = 'ISSUE_PATH') {
  return {
    mode,
    summary: '현재 장바구니 기준으로 GPU, 파워, 케이스 영향 관계를 확인했습니다.',
    nodes: [
      { id: 'part-CPU', type: 'PART', category: 'CPU', label: 'CPU', status: 'PASS', detail: '소켓 기준 부품' },
      { id: 'part-MOTHERBOARD', type: 'PART', category: 'MOTHERBOARD', label: '메인보드', status: 'PASS', detail: 'DDR 규격 확인' },
      { id: 'part-RAM', type: 'PART', category: 'RAM', label: 'RAM', status: 'PASS', detail: '메모리 규격' },
      { id: 'part-GPU', type: 'PART', category: 'GPU', label: 'RTX 5070', status: 'PASS', detail: '선택한 그래픽카드' },
      { id: 'part-PSU', type: 'PART', category: 'PSU', label: '750W 파워', status: 'WARN', detail: '전력 여유 확인' },
      { id: 'part-CASE', type: 'PART', category: 'CASE', label: 'Airflow Case', status: 'PASS', detail: '장착 길이 확인' },
      { id: 'part-COOLER', type: 'PART', category: 'COOLER', label: '쿨러', status: 'PASS', detail: '높이 여유 확인' },
      { id: 'part-STORAGE', type: 'PART', category: 'STORAGE', label: 'SSD', status: 'PASS', detail: '저장장치' }
    ],
    edges: [
      {
        id: 'edge-cpu-board-socket',
        source: 'part-CPU',
        target: 'part-MOTHERBOARD',
        type: 'REQUIRES',
        status: 'PASS',
        label: '소켓 일치',
        summary: 'CPU와 메인보드 소켓이 일치합니다.'
      },
      {
        id: 'edge-gpu-psu-power',
        source: 'part-GPU',
        target: 'part-PSU',
        type: 'AFFECTS',
        status: 'WARN',
        label: '전력 여유',
        summary: 'GPU 권장 정격 파워를 기준으로 PSU 여유를 확인합니다.'
      }
    ],
    focusNodeIds: ['part-GPU', 'part-PSU'],
    insights: [],
    toolResults: []
  };
}

type MockPartOptions = {
  compatibility?: { status: 'PASS' | 'WARN' | 'FAIL'; statusLabel?: string; summary?: string } | null;
  price?: number;
  supplierName?: string;
};

function candidatePart(id: string, category: string, name: string, options: MockPartOptions = {}) {
  return {
    id,
    category,
    name,
    manufacturer: '테스트제조사',
    price: options.price ?? 100000,
    status: 'ACTIVE',
    attributes: { shortSpec: `${name} 사양` },
    externalOffer: {
      imageUrl: `https://example.test/${id}.png`,
      supplierName: options.supplierName ?? '후보테스트몰',
      offerUrl: `https://example.test/${id}`,
      lowPrice: options.price ?? 100000,
      source: 'NAVER_SHOPPING_SEARCH'
    },
    compatibility: options.compatibility === undefined
      ? { status: 'PASS', statusLabel: '호환됨', summary: '현재 견적과 호환됩니다.', checkedTools: ['compatibility'] }
      : options.compatibility
  };
}

const emptyDraft = {
  id: 'draft-slot-test',
  status: 'ACTIVE',
  name: '셀프 견적',
  items: [],
  totalPrice: 0,
  itemCount: 0
};

function draftItem(
  partId: string,
  category: string,
  name: string,
  price: number,
  quantity = 1,
  attributes: Record<string, unknown> = {}
) {
  return {
    id: `draft-item-${partId}`,
    partId,
    category,
    name,
    manufacturer: '테스트제조사',
    quantity,
    unitPriceAtAdd: price,
    currentPrice: price,
    lineTotal: price * quantity,
    attributes
  };
}

const fullDraftItems = [
  draftItem('part-cpu-full', 'CPU', '풀보드 CPU', 420000),
  draftItem('part-board-full', 'MOTHERBOARD', '풀보드 메인보드', 250000),
  draftItem('part-ram-full', 'RAM', '풀보드 DDR5 램', 90000, 5),
  draftItem('part-gpu-full', 'GPU', '풀보드 RTX GPU', 980000),
  draftItem('part-ssd-full-1', 'STORAGE', '풀보드 NVMe SSD 1', 120000),
  draftItem('part-ssd-full-2', 'STORAGE', '풀보드 NVMe SSD 2', 130000),
  draftItem('part-ssd-full-3', 'STORAGE', '풀보드 NVMe SSD 3', 140000),
  draftItem('part-psu-full', 'PSU', '풀보드 850W 파워', 160000),
  draftItem('part-case-full', 'CASE', '풀보드 케이스', 110000),
  draftItem('part-cooler-full', 'COOLER', '풀보드 쿨러', 90000)
];

const fullDraft = {
  id: 'draft-slot-full',
  status: 'ACTIVE',
  name: '셀프 견적',
  items: fullDraftItems,
  totalPrice: fullDraftItems.reduce((sum, item) => sum + item.lineTotal, 0),
  itemCount: fullDraftItems.reduce((sum, item) => sum + item.quantity, 0)
};

async function loginAsUser(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });
}

async function mockEmptyPriceHistory(route: Parameters<Parameters<Page['route']>[1]>[0], partId: string) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      partId,
      partName: partId,
      currentPrice: 0,
      days: 3650,
      source: 'NAVER_SHOPPING_SEARCH',
      items: [],
      summary: {
        sampleCount: 0,
        currentPrice: 0,
        minPrice: 0,
        maxPrice: 0,
        firstPrice: 0,
        lastPrice: 0,
        changeAmount: 0,
        changeRatePercent: 0
      }
    })
  });
}

test.beforeEach(async ({ page }) => {
  await page.route('**/api/build-graphs/resolve', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}');
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(buildGraphResponse(body.focus?.mode ?? 'ISSUE_PATH'))
    });
  });
});

test('renders 8 empty slots on the slot board without the legacy list workspace', async ({ page }) => {
  await loginAsUser(page);

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(emptyDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  await expect(page.getByRole('heading', { name: '셀프 견적 · 구성 관계도' })).toBeVisible();
  const board = page.getByTestId('slot-board');
  await expect(board).toBeVisible();
  for (const category of ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER']) {
    await expect(page.getByTestId(`slot-${category}`)).toBeVisible();
  }
  await expect(board.getByText('+ 부품 선택')).toHaveCount(8);
  await expect(page.getByTestId('slot-CPU').locator('img')).toHaveAttribute('src', '/slot-board/parts/cpu.svg');
  await expect(page.getByTestId('slot-STORAGE').locator('img')).toHaveAttribute('src', '/slot-board/parts/ssd.svg');

  const statusBar = page.getByTestId('slot-status-bar');
  await expect(statusBar.getByText('장착 0/8')).toBeVisible();
  await expect(statusBar.getByText('미장착 슬롯 8개가 있습니다')).toBeVisible();
  await expect(statusBar.getByText('견적 합계')).toBeVisible();
  await expect(statusBar.getByRole('button', { name: '구매하기' })).toBeDisabled();

  // 구 목록/장바구니/노드 그래프 UI는 렌더링하지 않는다.
  await expect(page.getByRole('heading', { name: '견적 장바구니', exact: true })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: '견적 관계도' })).toHaveCount(0);
  await expect(page.getByPlaceholder('부품명, 제조사, 사양 검색')).toHaveCount(0);
  await expect(page.getByTestId('graph-flow-canvas')).toHaveCount(0);
});

test('fills all 8 slots from the current quote draft and shows mini slot overflow', async ({ page }) => {
  await loginAsUser(page);

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fullDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  // 상품명·가격 전문은 체크리스트(품목 지도)가 담당하고, 보드 카드는 이미지+카테고리 요약 카드다.
  await expect(page.getByTestId('checklist-CPU')).toContainText('풀보드 CPU');
  await expect(page.getByTestId('checklist-MOTHERBOARD')).toContainText('풀보드 메인보드');
  await expect(page.getByTestId('checklist-GPU')).toContainText('풀보드 RTX GPU');
  await expect(page.getByTestId('checklist-PSU')).toContainText('풀보드 850W 파워');
  await expect(page.getByTestId('checklist-CASE')).toContainText('풀보드 케이스');
  await expect(page.getByTestId('checklist-COOLER')).toContainText('풀보드 쿨러');
  await expect(page.getByTestId('checklist-GPU')).toContainText('980,000원');
  await expect(page.getByTestId('slot-GPU')).not.toContainText('풀보드 RTX GPU');
  await expect(page.getByTestId('slot-GPU')).toHaveAttribute('title', '풀보드 RTX GPU');
  await expect(page.getByTestId('slot-GPU').getByTestId('slot-part-image')).toBeVisible();

  // RAM mini slot 4칸: quantity 합산(5) 기준 4칸 + 초과 +1
  const ramSlot = page.getByTestId('slot-RAM');
  await expect(ramSlot.locator('[data-mini-slot-filled="true"]')).toHaveCount(4);
  await expect(ramSlot.getByText('+1')).toBeVisible();

  // SSD mini slot 2칸: item 개수(3) 기준 2칸 + 초과 +1. 복수 장착은 hover 툴팁에 '외 N개'로 요약.
  const ssdSlot = page.getByTestId('slot-STORAGE');
  await expect(ssdSlot.locator('[data-mini-slot-filled="true"]')).toHaveCount(2);
  await expect(ssdSlot.getByText('+1')).toBeVisible();
  await expect(ssdSlot).toHaveAttribute('title', '풀보드 NVMe SSD 1 외 2개');

  const statusBar = page.getByTestId('slot-status-bar');
  await expect(statusBar.getByText('장착 8/8')).toBeVisible();
  await expect(statusBar.getByText(/미장착 슬롯/)).toHaveCount(0);
  await expect(statusBar.getByText(`${fullDraft.totalPrice.toLocaleString()}원`)).toBeVisible();
});

test('renders the slot board as an information-first compatibility diagram with mounted part media', async ({ page }) => {
  await loginAsUser(page);
  const visualDraftItems = [
    {
      ...draftItem('part-visual-cpu', 'CPU', 'AMD Ryzen 7 7800X3D', 420000),
      attributes: { shortSpec: 'AM5 / 8코어' }
    },
    {
      ...draftItem('part-visual-board', 'MOTHERBOARD', 'B650 메인보드', 250000),
      attributes: { shortSpec: 'AM5 / DDR5 / PCIe 4.0' }
    },
    {
      ...draftItem('part-visual-gpu', 'GPU', 'NVIDIA GeForce RTX 4070 Ti SUPER', 1229000),
      attributes: { shortSpec: 'PCIe x16 4.0 / 16GB', interface: 'PCIe x16 4.0' },
      externalOffer: {
        imageUrl: 'https://example.test/visual-gpu.png',
        supplierName: '그래픽테스트몰',
        offerUrl: 'https://example.test/visual-gpu',
        lowPrice: 1229000,
        source: 'NAVER_SHOPPING_SEARCH'
      }
    },
    {
      ...draftItem('part-visual-psu', 'PSU', 'Classic II 750W', 120000),
      attributes: { shortSpec: '750W / ATX 3.1' }
    }
  ];

  await page.route('**/api/build-graphs/resolve', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...buildGraphResponse(),
        edges: [
          {
            id: 'edge-gpu-board-pcie',
            source: 'part-GPU',
            target: 'part-MOTHERBOARD',
            type: 'REQUIRES',
            status: 'PASS',
            label: 'PCIe x16 4.0',
            summary: '그래픽카드를 PCIe x16 슬롯에 장착할 수 있습니다.'
          },
          {
            id: 'edge-psu-board-power',
            source: 'part-PSU',
            target: 'part-MOTHERBOARD',
            type: 'REQUIRES',
            status: 'PASS',
            label: '24핀 전원',
            summary: '메인보드 주 전원 연결입니다.'
          }
        ]
      })
    });
  });
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...emptyDraft,
        items: visualDraftItems,
        totalPrice: visualDraftItems.reduce((sum, item) => sum + item.lineTotal, 0),
        itemCount: visualDraftItems.reduce((sum, item) => sum + item.quantity, 0)
      })
    });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  const board = page.getByTestId('slot-board');
  await expect(page.getByText('구성 관계도 — 부품 간 호환 상태')).toBeVisible();
  await expect(board).toHaveAttribute('data-visual-mode', 'motherboard');
  // 장식용 배경 평면도는 리디자인에서 제거됨 — 범례가 색 체계를 설명한다.
  await expect(page.getByTestId('slot-board-motherboard-art')).toHaveCount(0);
  // 범례는 보드 헤더에 있다 ('호환 가능'은 장착 슬롯 뱃지에도 쓰이므로 first = 헤더).
  await expect(page.getByText('호환 가능', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('장착 불가', { exact: true })).toBeVisible();
  await expect(page.getByText('미장착', { exact: true })).toBeVisible();

  // 카드는 카테고리 통일 에셋+짧은 요약(사양) 중심 — 상품명 전문은 hover 툴팁과 체크리스트가 담당한다.
  const gpuSlot = page.getByTestId('slot-GPU');
  await expect(gpuSlot.getByTestId('slot-part-image')).toHaveAttribute('src', '/slot-board/parts/gpu.svg');
  await expect(gpuSlot).toHaveAttribute('title', 'NVIDIA GeForce RTX 4070 Ti SUPER');
  await expect(gpuSlot).toContainText('PCIe x16 4.0');

  // 정상 관계선은 상태 점만 — 상세 문장은 title 툴팁으로 확인한다.
  const gpuEdge = page.getByTestId('slot-edge-GPU-MOTHERBOARD');
  await expect(gpuEdge).toHaveAttribute('data-status', 'PASS');
  await expect(gpuEdge).toHaveAttribute('title', '그래픽카드를 PCIe x16 슬롯에 장착할 수 있습니다.');
  const psuEdge = page.getByTestId('slot-edge-PSU-MOTHERBOARD');
  await expect(psuEdge).toHaveAttribute('data-status', 'PASS');
  await expect(psuEdge).toHaveText('');
});

test('shows the AI start banner on an empty quote with manual and AI entry points', async ({ page }) => {
  await loginAsUser(page);
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(emptyDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  const banner = page.getByTestId('quote-start-banner');
  await expect(banner).toBeVisible();
  await expect(banner).toContainText('AI에게 예산과 용도만 알려주세요');

  // 견적이 다 비어 있으면 체크리스트 1번(CPU)과 보드의 CPU 실장 지점이 반짝이며 시작점을 알려준다.
  await expect(page.getByTestId('checklist-CPU')).toHaveAttribute('data-next', 'true');
  await expect(page.getByTestId('checklist-CPU')).toHaveClass(/slot-hint-shimmer/);
  await expect(page.getByTestId('checklist-CPU')).toHaveClass(/slot-empty-pulse/);
  await expect(page.getByTestId('slot-CPU')).toHaveClass(/slot-hint-shimmer/);

  // 직접 고르기 → CPU 후보 패널이 열린다.
  await page.getByTestId('quote-manual-start').click();
  await expect(page).toHaveURL('/self-quote?category=CPU');
  await expect(page.getByTestId('slot-candidate-panel')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByTestId('slot-candidate-panel')).toHaveCount(0);

  // AI로 시작하기 → 챗봇 패널이 열린다.
  await page.getByTestId('quote-ai-start').click();
  await expect(page.getByTestId('ai-chatbot-panel')).toBeVisible();
});

test('renders the quote checklist with progress, next-slot guide, and total', async ({ page }) => {
  await loginAsUser(page);
  const checklistItems = [
    draftItem('part-check-cpu', 'CPU', '체크 CPU', 300000),
    draftItem('part-check-board', 'MOTHERBOARD', '체크 보드', 200000)
  ];

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...emptyDraft, items: checklistItems, totalPrice: 500000, itemCount: 2 })
    });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  const checklist = page.getByTestId('quote-checklist');
  await expect(checklist.getByTestId('quote-checklist-progress')).toHaveText('2/8 완료');
  // 합계는 하단 상태바(견적 합계)만 담당한다 — 체크리스트 안의 중복 총액 행은 제거됨.
  await expect(checklist.getByTestId('quote-checklist-total')).toHaveCount(0);

  await expect(checklist.getByTestId('checklist-CPU')).toHaveAttribute('data-filled', 'true');
  await expect(checklist.getByTestId('checklist-CPU')).toContainText('체크 CPU');
  await expect(checklist.getByTestId('checklist-CPU')).toContainText('300,000원');

  // 권장 순서상 다음 슬롯(RAM)이 체크리스트·보드 양쪽에서 강조된다.
  await expect(checklist.getByTestId('checklist-RAM')).toHaveAttribute('data-next', 'true');
  await expect(checklist.getByTestId('checklist-RAM')).toContainText('다음 선택');
  await expect(page.getByTestId('slot-RAM')).toHaveAttribute('data-next', 'true');
  await expect(page.getByTestId('quote-next-guide')).toContainText('다음: 3. RAM를 선택해 주세요');

  // 담은 부품 견적 테이블 — 체크리스트 아래 별도 박스에 상품별 행(부품/수량/금액)으로 나온다.
  const itemsTable = page.getByTestId('quote-items-table');
  await expect(itemsTable).toContainText('담은 부품 2개');
  await expect(itemsTable.getByTestId('quote-items-row-CPU')).toContainText('체크 CPU');
  await expect(itemsTable.getByTestId('quote-items-row-CPU')).toContainText('300,000원');
  await expect(itemsTable.getByTestId('quote-items-row-MOTHERBOARD')).toContainText('체크 보드');

  // 체크리스트 클릭 = 해당 후보 패널 열기 (가이드는 강제가 아니라서 아무 항목이나 열 수 있다).
  await checklist.getByTestId('checklist-PSU').click();
  await expect(page).toHaveURL('/self-quote?category=PSU');
  await expect(page.getByTestId('slot-candidate-panel')).toBeVisible();
});

test('blocks purchase when a tool check fails without a matching edge', async ({ page }) => {
  await loginAsUser(page);
  // 감사 P1-3①: GPU가 없어 GPU-PSU 엣지가 안 생기는 견적에서 파워 용량 부족(power 툴 FAIL)이
  // 화면 어디에도 안 뜨고 구매가 열려 있던 사각 — 툴 FAIL도 차단·표기에 반영돼야 한다.
  const powerFailItems = [
    draftItem('part-cpu-hot', 'CPU', '고전력 CPU', 500000),
    draftItem('part-psu-small', 'PSU', '300W 파워', 40000)
  ];
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...emptyDraft, items: powerFailItems, totalPrice: 540000, itemCount: 2 })
    });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });
  await page.route('**/api/build-graphs/resolve', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        mode: 'BUILD_OVERVIEW',
        summary: '파워 용량을 확인했습니다.',
        nodes: [
          { id: 'part-CPU', type: 'PART', category: 'CPU', label: '고전력 CPU', status: 'PASS', detail: '' },
          { id: 'part-PSU', type: 'PART', category: 'PSU', label: '300W 파워', status: 'PASS', detail: '' }
        ],
        edges: [],
        focusNodeIds: [],
        insights: [],
        toolResults: [
          { tool: 'power', status: 'FAIL', confidence: 'HIGH', summary: 'PSU 정격 출력이 예상 부하와 GPU 권장 파워에 못 미쳐 상위 용량이 필요합니다.' }
        ]
      })
    });
  });

  await page.goto('/self-quote');

  await expect(page.getByTestId('quote-summary-bar').getByText('조건 미충족')).toBeVisible();
  await expect(page.getByText('안 맞는 부품이 있어 구매할 수 없습니다', { exact: false })).toBeVisible();
});

test('does not block purchase for a tool fail about a category that is not mounted', async ({ page }) => {
  await loginAsUser(page);
  // PSU를 아직 안 담았는데 power 툴이 FAIL(파워 없음)인 경우는 '비호환'이 아니라 '미장착'이다 — 차단하지 않는다.
  const cpuOnlyItems = [draftItem('part-cpu-only', 'CPU', '조립 시작 CPU', 300000)];
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...emptyDraft, items: cpuOnlyItems, totalPrice: 300000, itemCount: 1 })
    });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });
  await page.route('**/api/build-graphs/resolve', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        mode: 'BUILD_OVERVIEW',
        summary: '파워 미장착 상태입니다.',
        nodes: [{ id: 'part-CPU', type: 'PART', category: 'CPU', label: '조립 시작 CPU', status: 'PASS', detail: '' }],
        edges: [],
        focusNodeIds: [],
        insights: [],
        toolResults: [
          { tool: 'power', status: 'FAIL', confidence: 'MEDIUM', summary: 'PSU가 없어 부하를 감당할 수 없습니다.' }
        ]
      })
    });
  });

  await page.goto('/self-quote');

  await expect(page.getByTestId('quote-summary-bar')).toBeVisible();
  await expect(page.getByTestId('quote-summary-bar').getByText('조건 미충족')).toHaveCount(0);
  await expect(page.getByText('안 맞는 부품이 있어 구매할 수 없습니다', { exact: false })).toHaveCount(0);
});

test('shows the completion guide when all eight slots are filled', async ({ page }) => {
  await loginAsUser(page);
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fullDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  await expect(page.getByTestId('quote-complete-guide')).toContainText('8개 품목이 모두 채워졌어요');
  await expect(page.getByTestId('quote-next-guide')).toHaveCount(0);
  await expect(page.getByTestId('quote-start-banner')).toHaveCount(0);
  await expect(page.getByTestId('quote-checklist-progress')).toHaveText('8/8 완료');
});

test('keeps fixed placement coordinates even when the graph response carries saved positions', async ({ page }) => {
  await loginAsUser(page);

  await page.route('**/api/build-graphs/resolve', async (route) => {
    const base = buildGraphResponse();
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...base,
        nodes: base.nodes.map((node) => {
          if (node.category === 'GPU') {
            return { ...node, position: { x: 54, y: 8 } };
          }
          if (node.category === 'PSU') {
            return { ...node, position: { x: 8, y: 64 } };
          }
          return node;
        })
      })
    });
  });
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fullDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  // 실장도는 평면도 아트(소켓/DIMM/PCIe)와 실장 지점이 픽셀 정합해야 하므로 배치가 고정이다 —
  // 그래프 응답에 저장 좌표가 있어도 무시한다(구 관리자 드래그 배치는 실장도에서 미사용).
  await expect(page.getByTestId('slot-GPU')).toHaveAttribute('style', /--sx:\s*37\.5%;\s*--sy:\s*62%/);
  await expect(page.getByTestId('slot-PSU')).toHaveAttribute('style', /--sx:\s*1\.25%;\s*--sy:\s*66%/);
  await expect(page.getByTestId('slot-CPU')).toHaveAttribute('style', /--sx:\s*48\.75%;\s*--sy:\s*30%/);
});

test('shows graph edge labels on the fallback topology relationships', async ({ page }) => {
  await loginAsUser(page);

  await page.route('**/api/build-graphs/resolve', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...buildGraphResponse(),
        edges: [
          {
            id: 'edge-cpu-board-socket',
            source: 'part-CPU',
            target: 'part-MOTHERBOARD',
            type: 'REQUIRES',
            status: 'PASS',
            label: '소켓 AM5 일치',
            summary: 'CPU와 메인보드 소켓이 일치합니다.'
          },
          {
            id: 'edge-gpu-psu-power',
            source: 'part-GPU',
            target: 'part-PSU',
            type: 'AFFECTS',
            status: 'WARN',
            label: '전력 여유 확인 필요',
            summary: 'GPU 권장 정격 파워 기준으로 여유가 빠듯합니다.'
          }
        ]
      })
    });
  });
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fullDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  const edges = page.getByTestId('slot-board-edges');
  await expect(edges).toBeVisible();
  // 정상 관계선은 텍스트 라벨 대신 상태 점만 — 전문용어(소켓/PCIe)는 평상시에 노출하지 않는다.
  const passEdge = page.getByTestId('slot-edge-CPU-MOTHERBOARD');
  await expect(passEdge).toHaveAttribute('data-status', 'PASS');
  await expect(passEdge).toHaveText('');
  await expect(passEdge).toHaveAttribute('title', 'CPU와 메인보드 소켓이 일치합니다.');
  // 문제가 있는 관계선만 서버가 내려준 사유 라벨을 그대로 보여준다.
  const warnEdge = page.getByTestId('slot-edge-GPU-PSU');
  await expect(warnEdge).toHaveAttribute('data-status', 'WARN');
  await expect(warnEdge).toHaveText('전력 여유 확인 필요');
  // graph 응답에 없는 정상 관계선도 점으로만 표시된다.
  await expect(page.getByTestId('slot-edge-MOTHERBOARD-RAM')).toHaveText('');
  await expect(page.getByTestId('slot-edge-GPU-CASE')).toHaveText('');
});

test('keeps fallback topology edges when the graph api fails', async ({ page }) => {
  await loginAsUser(page);

  await page.route('**/api/build-graphs/resolve', async (route) => {
    await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ message: 'graph resolve failed' }) });
  });
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fullDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  // graph API가 실패해도 슬롯 보드와 기본 topology 관계선(상태 점)은 항상 렌더링된다.
  await expect(page.getByTestId('slot-board')).toBeVisible();
  await expect(page.getByTestId('slot-edge-CPU-MOTHERBOARD')).toHaveAttribute('data-status', 'BASE');
  await expect(page.getByTestId('slot-edge-MOTHERBOARD-RAM')).toHaveAttribute('data-status', 'BASE');
  // P1-1: 보드 규격 vs 케이스 지원 — 실장 관계(implied)라 선 없이 상태 점으로 표시된다.
  await expect(page.getByTestId('slot-edge-MOTHERBOARD-CASE')).toHaveAttribute('data-status', 'BASE');
  // P1-2: 보드 M.2 슬롯 vs SSD 수 — 실장 관계(implied)라 SSD 실장 지점의 상태 점으로 표시된다.
  await expect(page.getByTestId('slot-edge-MOTHERBOARD-STORAGE')).toHaveAttribute('data-status', 'BASE');
  await expect(page.getByTestId('slot-edge-GPU-PSU')).toHaveAttribute('data-status', 'BASE');
  await expect(page.getByTestId('slot-edge-GPU-CASE')).toHaveAttribute('data-status', 'BASE');
  await expect(page.getByTestId('slot-edge-COOLER-CASE')).toHaveAttribute('data-status', 'BASE');
  await expect(page.getByTestId('slot-status-bar').getByText('장착 8/8')).toBeVisible();
});

test('shows the current build performance panel from the resolve performance tool result', async ({ page }) => {
  await loginAsUser(page);
  const draft = {
    ...emptyDraft,
    items: [
      draftItem('part-perf-cpu', 'CPU', '라이젠 9600X', 300000),
      draftItem('part-perf-gpu', 'GPU', 'RTX 5060', 500000)
    ],
    totalPrice: 800000,
    itemCount: 2
  };
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(draft) });
  });
  await page.route('**/api/build-graphs/resolve', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...buildGraphResponse(),
        toolResults: [
          {
            tool: 'performance',
            status: 'WARN',
            confidence: 'HIGH',
            summary: '성능 여유가 낮아 상위 부품을 검토해야 합니다. 점수는 참고용입니다.',
            details: {
              cpu: '라이젠 9600X',
              gpu: 'RTX 5060',
              cpuBenchmarkScore: 68,
              gpuBenchmarkScore: 63,
              vramGb: 8,
              benchmarkSource: 'benchmark_summaries',
              guaranteePolicy: 'NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE'
            }
          }
        ]
      })
    });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  const panel = page.getByTestId('quote-performance-panel');
  await expect(panel).toBeVisible();
  // 용도 적합도(PASS/WARN → 사용자 언어) + 점수 막대 + 근거 표기.
  await expect(panel.getByTestId('quote-performance-fit')).toHaveText('여유 낮음');
  await expect(panel.getByTestId('quote-performance-cpu-score')).toContainText('68');
  await expect(panel.getByTestId('quote-performance-gpu-score')).toContainText('63');
  await expect(panel.getByTestId('quote-performance-cpu')).toContainText('라이젠 9600X');
  await expect(panel).toContainText('공개 벤치마크 기준');
  // 정책: 정확 FPS·실성능 보장 아님 문구 노출.
  await expect(panel).toContainText('보장하지 않습니다');
});

test('offers a performance comparison entry point on CPU candidates that prefills the assistant', async ({ page }) => {
  await loginAsUser(page);
  const draft = {
    ...emptyDraft,
    items: [draftItem('part-perf-cpu', 'CPU', '라이젠 9600X', 300000)],
    totalPrice: 300000,
    itemCount: 1
  };
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(draft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: 'cand-cpu-1',
            category: 'CPU',
            name: '인텔 245K',
            manufacturer: '인텔',
            price: 350000,
            attributes: {},
            compatibility: { status: 'PASS', statusLabel: '호환', summary: '' }
          }
        ],
        page: 0,
        size: 20,
        total: 1
      })
    });
  });

  await page.goto('/self-quote?category=CPU');

  // CPU 슬롯에 현재 부품이 있으면 후보마다 '성능 비교' 진입점이 뜬다.
  const compareBtn = page.getByTestId('candidate-perf-compare').first();
  await expect(compareBtn).toBeVisible();
  await expect(compareBtn).toHaveAttribute('aria-label', /현재 라이젠 9600X.*인텔 245K.*성능/);
});

test('highlights WARN and FAIL slots with edges and blocks purchase on FAIL', async ({ page }) => {
  await loginAsUser(page);
  const saveRequests: unknown[] = [];

  await page.route('**/api/build-graphs/resolve', async (route) => {
    const base = buildGraphResponse();
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...base,
        nodes: base.nodes.map((node) => {
          if (node.category === 'GPU') {
            return { ...node, status: 'FAIL', detail: '파워 용량이 부족합니다.' };
          }
          if (node.category === 'PSU') {
            return { ...node, status: 'WARN', detail: '전력 여유가 빠듯합니다.' };
          }
          return node;
        }),
        edges: [
          {
            id: 'edge-gpu-psu-power',
            source: 'part-GPU',
            target: 'part-PSU',
            type: 'AFFECTS',
            status: 'FAIL',
            label: '전력 150W 부족',
            summary: 'GPU 권장 정격 파워보다 PSU 용량이 부족합니다.'
          }
        ]
      })
    });
  });
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fullDraft) });
  });
  await page.route('**/api/builds/from-chat', async (route) => {
    saveRequests.push(JSON.parse(route.request().postData() ?? '{}'));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 'saved-fail-build' }) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  // 문제 슬롯 강조: FAIL은 숨기지 않고 표시한다.
  const gpuSlot = page.getByTestId('slot-GPU');
  await expect(gpuSlot).toHaveAttribute('data-status', 'FAIL');
  await expect(gpuSlot.getByText('안 맞음')).toBeVisible();
  const psuSlot = page.getByTestId('slot-PSU');
  await expect(psuSlot).toHaveAttribute('data-status', 'WARN');
  await expect(psuSlot.getByText('간섭 주의')).toBeVisible();

  // 문제 관계선 강조
  const failEdge = page.getByTestId('slot-edge-GPU-PSU');
  await expect(failEdge).toHaveAttribute('data-status', 'FAIL');
  await expect(failEdge).toHaveText('전력 150W 부족');

  // FAIL이 있으면 구매하기는 비활성화되고 사유를 보여준다.
  const statusBar = page.getByTestId('slot-status-bar');
  await expect(statusBar.getByRole('button', { name: '구매하기' })).toBeDisabled();
  await expect(statusBar.getByRole('link', { name: '구매하기' })).toHaveCount(0);
  await expect(statusBar.getByText('안 맞는 부품이 있어 구매할 수 없습니다. 문제 슬롯을 교체해 주세요.')).toBeVisible();

  // 내 견적함 저장은 FAIL이 있어도 허용한다.
  const saveButton = statusBar.getByRole('button', { name: '내 견적함에 추가' });
  await expect(saveButton).toBeEnabled();
  await saveButton.click();
  await expect.poll(() => saveRequests.length).toBe(1);
  await expect(statusBar.getByText('내 견적함에 추가했습니다.')).toBeVisible();
});

test('keeps purchase enabled when the current quote has only WARN issues', async ({ page }) => {
  await loginAsUser(page);

  // beforeEach 기본 graph 응답은 PSU WARN만 포함한다.
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fullDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  const psuSlot = page.getByTestId('slot-PSU');
  await expect(psuSlot).toHaveAttribute('data-status', 'WARN');
  await expect(psuSlot.getByText('간섭 주의')).toBeVisible();
  const statusBar = page.getByTestId('slot-status-bar');
  await expect(statusBar.getByRole('link', { name: '구매하기' })).toHaveAttribute('href', '/checkout');
});

test('marks fallback topology edges as pending while a related slot is empty', async ({ page }) => {
  await loginAsUser(page);

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(emptyDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  await expect(page.getByTestId('slot-edge-CPU-MOTHERBOARD')).toHaveAttribute('data-status', 'PENDING');
  await expect(page.getByTestId('slot-edge-GPU-PSU')).toHaveAttribute('data-status', 'PENDING');
});

test('removes a single-part slot item from the slot board', async ({ page }) => {
  await loginAsUser(page);
  let draft: unknown = fullDraft;
  const deletedPartIds: string[] = [];

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() === 'DELETE') {
      const partId = url.pathname.split('/').pop() ?? '';
      deletedPartIds.push(partId);
      draft = {
        ...fullDraft,
        items: fullDraftItems.filter((item) => item.partId !== partId)
      };
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(draft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  await page.getByTestId('slot-GPU').hover();
  await page.getByRole('button', { name: '풀보드 RTX GPU 견적에서 제거' }).click();

  await expect.poll(() => deletedPartIds).toEqual(['part-gpu-full']);
  await expect(page.getByTestId('slot-GPU')).toContainText('+ 부품 선택');
  await expect(page.getByTestId('slot-status-bar').getByText('장착 7/8')).toBeVisible();
  await expect(page.getByTestId('slot-status-bar').getByText('미장착 슬롯 1개가 있습니다')).toBeVisible();
});

test('opens the candidate panel from a slot and requests QUOTE_DRAFT_CURRENT compatibility in 20 item pages', async ({ page }) => {
  await loginAsUser(page);
  const partRequests: Array<Record<string, string | null>> = [];

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(emptyDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    partRequests.push({
      category: url.searchParams.get('category'),
      size: url.searchParams.get('size'),
      sort: url.searchParams.get('sort'),
      compatibilitySource: url.searchParams.get('compatibilitySource')
    });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          candidatePart('part-gpu-pass', 'GPU', '패스 GPU 후보'),
          candidatePart('part-gpu-warn', 'GPU', '간섭 GPU 후보', {
            compatibility: { status: 'WARN', statusLabel: '간섭 주의', summary: '케이스 장착 길이가 빠듯합니다.' }
          }),
          candidatePart('part-gpu-fail', 'GPU', '실패 GPU 후보', {
            compatibility: { status: 'FAIL', statusLabel: '안 맞음', summary: '파워 용량이 부족합니다.' }
          })
        ],
        page: 0,
        size: 20,
        total: 3
      })
    });
  });

  await page.goto('/self-quote');
  await page.getByRole('button', { name: 'GPU 슬롯 열기' }).click();

  const panel = page.getByTestId('slot-candidate-panel');
  await expect(panel).toBeVisible();
  await expect(panel.getByRole('heading', { name: 'GPU 부품 목록' })).toBeVisible();
  await expect(page.getByTestId('slot-GPU')).toHaveAttribute('data-selected', 'true');
  await expect(page).toHaveURL('/self-quote?category=GPU');

  await expect.poll(() => partRequests.length).toBeGreaterThan(0);
  expect(partRequests[0]).toEqual({
    category: 'GPU',
    size: '20',
    sort: 'price_asc',
    compatibilitySource: 'QUOTE_DRAFT_CURRENT'
  });

  await expect(panel.getByText('패스 GPU 후보')).toBeVisible();
  await expect(panel.getByText('간섭 GPU 후보')).toBeVisible();
  await expect(panel.getByText('간섭 주의')).toBeVisible();
  await expect(panel.getByRole('button', { name: '간섭 GPU 후보 담기' })).toBeEnabled();
  // FAIL 후보는 숨기지 않고 회색 비활성 + 선택 불가 사유를 함께 보여준다.
  await expect(panel.getByText('실패 GPU 후보')).toBeVisible();
  const failCard = panel.locator('[data-compat="FAIL"]');
  await expect(failCard).toHaveCount(1);
  // '선택 불가'는 뱃지와 비활성 버튼 양쪽에 붙는다 — 뱃지(first)와 버튼을 각각 확인.
  await expect(failCard.getByText('선택 불가', { exact: true }).first()).toBeVisible();
  await expect(failCard.getByText('파워 용량이 부족합니다.')).toBeVisible();
  await expect(panel.getByRole('button', { name: '실패 GPU 후보 선택 불가' })).toBeDisabled();
  await expect(panel.getByText('안 맞는 후보는 회색으로 표시하고 사유를 알려드려요')).toBeVisible();

  await page.keyboard.press('Escape');
  await expect(page.getByTestId('slot-candidate-panel')).toHaveCount(0);
  await expect(page).toHaveURL('/self-quote');
  await expect(page.getByTestId('slot-GPU')).toHaveAttribute('data-selected', 'false');
});

test('adds a candidate part into an empty slot from the panel', async ({ page }) => {
  await loginAsUser(page);
  const putRequests: Array<{ partId: string; quantity: number }> = [];
  let draft: unknown = emptyDraft;

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() === 'PUT') {
      const partId = url.pathname.split('/').pop() ?? '';
      const body = JSON.parse(route.request().postData() ?? '{}') as { quantity: number };
      putRequests.push({ partId, quantity: body.quantity });
      draft = {
        ...emptyDraft,
        items: [draftItem(partId, 'GPU', '패스 GPU 후보', 100000)],
        totalPrice: 100000,
        itemCount: 1
      };
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(draft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [candidatePart('part-gpu-pass', 'GPU', '패스 GPU 후보')], page: 0, size: 20, total: 1 })
    });
  });

  await page.goto('/self-quote?category=GPU');
  await page.getByRole('button', { name: '패스 GPU 후보 담기' }).click();

  await expect.poll(() => putRequests).toEqual([{ partId: 'part-gpu-pass', quantity: 1 }]);
  await expect(page.getByTestId('checklist-GPU')).toContainText('패스 GPU 후보');
  await expect(page.getByTestId('slot-GPU')).toHaveAttribute('title', '패스 GPU 후보');
  await expect(page.getByTestId('slot-status-bar').getByText('장착 1/8')).toBeVisible();
  await expect(page.getByTestId('slot-status-bar').getByText('미장착 슬롯 7개가 있습니다')).toBeVisible();
});

test('shows a whole FAIL page greyed out with reasons instead of auto-fetching the next page', async ({ page }) => {
  await loginAsUser(page);
  const requestedPages: string[] = [];

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(emptyDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    const pageParam = url.searchParams.get('page') ?? '0';
    requestedPages.push(pageParam);
    const items = pageParam === '0'
      ? Array.from({ length: 20 }, (_, index) => candidatePart(`part-psu-fail-${index}`, 'PSU', `실패 파워 ${index + 1}`, {
          compatibility: { status: 'FAIL', statusLabel: '안 맞음', summary: '용량 부족' }
        }))
      : [candidatePart('part-psu-pass', 'PSU', '통과 파워 후보')];
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items, page: Number.parseInt(pageParam, 10), size: 20, total: 21 })
    });
  });

  await page.goto('/self-quote?category=PSU');

  // 전부 FAIL이어도 자동으로 다음 페이지를 당기지 않고, 회색 카드 20개 + 사유를 그대로 보여준다.
  const panel = page.getByTestId('slot-candidate-panel');
  await expect(panel.getByText('실패 파워 1', { exact: true })).toBeVisible();
  await expect(panel.locator('[data-compat="FAIL"]')).toHaveCount(20);
  await expect(panel.getByText('용량 부족').first()).toBeVisible();
  expect(requestedPages).toEqual(['0']);

  // 다음 페이지는 사용자가 직접 '후보 더 보기'로 불러온다.
  await panel.getByRole('button', { name: '후보 더 보기' }).dispatchEvent('click');
  await expect(panel.getByText('통과 파워 후보')).toBeVisible();
  expect(requestedPages).toContain('1');
});

test('loads more candidates in 20 item pages from the panel', async ({ page }) => {
  await loginAsUser(page);
  const requestedPages: string[] = [];

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(emptyDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    const pageParam = url.searchParams.get('page') ?? '0';
    requestedPages.push(pageParam);
    const currentPage = Number.parseInt(pageParam, 10);
    const start = currentPage * 20;
    const items = Array.from({ length: Math.min(20, 45 - start) }, (_, index) => {
      const itemNumber = start + index + 1;
      return candidatePart(`part-psu-page-${itemNumber}`, 'PSU', `페이징 파워 ${itemNumber}`, { price: 50000 + itemNumber });
    });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items, page: currentPage, size: 20, total: 45 })
    });
  });

  await page.goto('/self-quote?category=PSU');

  const panel = page.getByTestId('slot-candidate-panel');
  await expect(panel.getByText('페이징 파워 1', { exact: true })).toBeVisible();
  expect(requestedPages).toContain('0');

  await panel.getByRole('button', { name: '후보 더 보기' }).click();
  await expect(panel.getByText('페이징 파워 21', { exact: true })).toBeVisible();
  expect(requestedPages).toContain('1');
});

test('manages RAM items with remove and replace target selection in the panel', async ({ page }) => {
  await loginAsUser(page);
  const putRequests: string[] = [];
  const deleteRequests: string[] = [];
  let items = [
    draftItem('part-ram-a', 'RAM', '기존 램 A', 90000, 2),
    draftItem('part-ram-b', 'RAM', '기존 램 B', 80000, 1)
  ];

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    if (method === 'PUT') {
      const partId = url.pathname.split('/').pop() ?? '';
      putRequests.push(partId);
      items = [...items, draftItem(partId, 'RAM', '교체 램 후보', 95000, 1)];
    }
    if (method === 'DELETE') {
      const partId = url.pathname.split('/').pop() ?? '';
      deleteRequests.push(partId);
      items = items.filter((item) => item.partId !== partId);
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...emptyDraft,
        items,
        totalPrice: items.reduce((sum, item) => sum + item.lineTotal, 0),
        itemCount: items.reduce((sum, item) => sum + item.quantity, 0)
      })
    });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [candidatePart('part-ram-new', 'RAM', '교체 램 후보', { price: 95000 })], page: 0, size: 20, total: 1 })
    });
  });

  await page.goto('/self-quote?category=RAM');

  const panel = page.getByTestId('slot-candidate-panel');
  await expect(panel.getByText('기존 램 A')).toBeVisible();
  await expect(panel.getByText('기존 램 B')).toBeVisible();

  // 개별 제거
  await panel.getByRole('button', { name: '기존 램 A 견적에서 제거' }).click();
  await expect.poll(() => deleteRequests).toEqual(['part-ram-a']);
  await expect(panel.getByText('기존 램 A')).toHaveCount(0);

  // 교체 대상 선택 후 후보로 교체
  await panel.getByRole('button', { name: '기존 램 B 교체 대상 선택' }).click();
  await panel.getByRole('button', { name: '교체 램 후보(으)로 교체' }).click();

  await expect.poll(() => putRequests).toEqual(['part-ram-new']);
  await expect.poll(() => deleteRequests).toEqual(['part-ram-a', 'part-ram-b']);
  await expect(page.getByTestId('checklist-RAM')).toContainText('교체 램 후보');
});

test('sends ADD evaluation mode for multi-item categories and replace target for targeted replace', async ({ page }) => {
  await loginAsUser(page);
  const partRequests: Array<{ mode: string | null; target: string | null }> = [];
  const ramItems = [draftItem('part-ram-a', 'RAM', '기존 램 A', 90000, 1)];

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...emptyDraft, items: ramItems, totalPrice: 90000, itemCount: 1 })
    });
  });
  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    partRequests.push({
      mode: url.searchParams.get('compatibilityMode'),
      target: url.searchParams.get('replaceTargetPartId')
    });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [candidatePart('part-ram-new', 'RAM', '후보 램 킷')], page: 0, size: 20, total: 1 })
    });
  });

  await page.goto('/self-quote?category=RAM');

  // 복수 장착 카테고리(RAM)는 담기 기준(ADD)으로 평가를 요청한다.
  await expect.poll(() => partRequests.length).toBeGreaterThan(0);
  expect(partRequests[0]).toEqual({ mode: 'ADD', target: null });

  // 교체 대상을 지정하면 그 행만 제외하는 REPLACE 평가로 다시 요청한다.
  const panel = page.getByTestId('slot-candidate-panel');
  await panel.getByRole('button', { name: '기존 램 A 교체 대상 선택' }).click();
  await expect.poll(() => partRequests.some((request) => request.target === 'part-ram-a')).toBe(true);
  const targeted = partRequests.find((request) => request.target === 'part-ram-a');
  expect(targeted?.mode).toBeNull();
});

test('flashes the slot after attaching a part without breaking the flow', async ({ page }) => {
  await loginAsUser(page);
  let draft: unknown = emptyDraft;

  await page.route('**/api/quote-drafts/current**', async (route) => {
    if (route.request().method() === 'PUT') {
      draft = {
        ...emptyDraft,
        items: [draftItem('part-gpu-flash', 'GPU', '플래시 GPU 후보', 100000)],
        totalPrice: 100000,
        itemCount: 1
      };
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(draft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [candidatePart('part-gpu-flash', 'GPU', '플래시 GPU 후보')], page: 0, size: 20, total: 1 })
    });
  });

  await page.goto('/self-quote?category=GPU');

  const gpuSlot = page.getByTestId('slot-GPU');
  await expect(gpuSlot).toHaveAttribute('data-flash', 'false');
  await page.getByRole('button', { name: '플래시 GPU 후보 담기' }).click();

  // 장착 직후 flash 상태가 켜졌다가 자동으로 꺼지고, 조작 흐름은 그대로 동작한다.
  await expect(gpuSlot).toHaveAttribute('data-flash', 'true');
  await expect(gpuSlot).toHaveAttribute('title', '플래시 GPU 후보');
  await expect(gpuSlot).toHaveAttribute('data-flash', 'false', { timeout: 3000 });
});

test('keeps attach and remove flows working with reduced motion', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await loginAsUser(page);
  let draft: unknown = emptyDraft;

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const method = route.request().method();
    if (method === 'PUT') {
      draft = {
        ...emptyDraft,
        items: [draftItem('part-gpu-motion', 'GPU', '모션 감소 GPU', 100000)],
        totalPrice: 100000,
        itemCount: 1
      };
    }
    if (method === 'DELETE') {
      draft = emptyDraft;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(draft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [candidatePart('part-gpu-motion', 'GPU', '모션 감소 GPU')], page: 0, size: 20, total: 1 })
    });
  });

  await page.goto('/self-quote?category=GPU');
  await page.getByRole('button', { name: '모션 감소 GPU 담기' }).click();
  const gpuSlot = page.getByTestId('slot-GPU');
  await expect(gpuSlot).toHaveAttribute('title', '모션 감소 GPU');

  const panel = page.getByTestId('slot-candidate-panel');
  await panel.getByRole('button', { name: '모션 감소 GPU 견적에서 제거' }).click();
  await expect(gpuSlot).toContainText('+ 부품 선택');
});

test('shows current item spec details in the panel for a single-part slot', async ({ page }) => {
  await loginAsUser(page);
  const deleteRequests: string[] = [];
  const gpuItem = {
    ...draftItem('part-gpu-detail', 'GPU', '상세 스펙 RTX GPU', 890000),
    attributes: { shortSpec: 'QHD 게임용 GPU 스펙', vramGb: 16, wattage: 220 }
  };
  let draft: unknown = { ...emptyDraft, items: [gpuItem], totalPrice: 890000, itemCount: 1 };

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() === 'DELETE') {
      deleteRequests.push(url.pathname.split('/').pop() ?? '');
      draft = emptyDraft;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(draft) });
  });
  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-gpu-detail',
          partName: '상세 스펙 RTX GPU',
          currentPrice: 890000,
          days: 3650,
          source: 'NAVER_SHOPPING_SEARCH',
          items: [
            { price: 900000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-20T00:00:00Z' },
            { price: 890000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-07-01T00:00:00Z' }
          ],
          summary: {
            sampleCount: 2,
            currentPrice: 890000,
            minPrice: 890000,
            maxPrice: 900000,
            firstPrice: 900000,
            lastPrice: 890000,
            changeAmount: -10000,
            changeRatePercent: -1.11
          }
        })
      });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote?category=GPU');

  const panel = page.getByTestId('slot-candidate-panel');
  await expect(panel.getByText('현재 장착')).toBeVisible();
  await expect(panel.getByText('상세 스펙 RTX GPU')).toBeVisible();
  await expect(panel.getByText('QHD 게임용 GPU 스펙')).toBeVisible();
  await expect(panel.getByText('VRAM 16GB')).toBeVisible();
  await expect(panel.getByText('사용전력 220W')).toBeVisible();
  await expect(panel.getByText('직전 기록 대비 -10,000원 (-1.11%)')).toBeVisible();

  await panel.getByRole('button', { name: '상세 스펙 RTX GPU 견적에서 제거' }).click();
  await expect.poll(() => deleteRequests).toEqual(['part-gpu-detail']);
  await expect(page.getByTestId('slot-GPU')).toContainText('+ 부품 선택');
});

test('updates RAM quantity with the panel stepper', async ({ page }) => {
  await loginAsUser(page);
  const patchRequests: Array<{ partId: string; quantity: number }> = [];
  let quantity = 2;

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() === 'PATCH') {
      const body = JSON.parse(route.request().postData() ?? '{}') as { quantity: number };
      patchRequests.push({ partId: url.pathname.split('/').pop() ?? '', quantity: body.quantity });
      quantity = body.quantity;
    }
    const item = draftItem('part-ram-qty', 'RAM', '수량 조절 램', 90000, quantity);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...emptyDraft, items: [item], totalPrice: item.lineTotal, itemCount: quantity })
    });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote?category=RAM');

  const panel = page.getByTestId('slot-candidate-panel');
  await expect(panel.getByText('수량 조절 램')).toBeVisible();
  await panel.getByRole('button', { name: '수량 조절 램 수량 증가' }).click();

  await expect.poll(() => patchRequests).toEqual([{ partId: 'part-ram-qty', quantity: 3 }]);
  await expect(page.getByTestId('slot-RAM').locator('[data-mini-slot-filled="true"]')).toHaveCount(3);
});

test('counts a dual-stick RAM kit as two mini slots', async ({ page }) => {
  await loginAsUser(page);

  // "32GB(16Gx2)" 킷: 상품 1개(quantity 1)지만 moduleCount=2라 스틱 2개를 차지한다.
  const kit = draftItem('part-ram-kit', 'RAM', '32GB 2개들이 킷', 180000, 1, { moduleCount: 2 });
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...emptyDraft, items: [kit], totalPrice: kit.lineTotal, itemCount: 1 })
    });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  await expect(page.getByTestId('slot-RAM').locator('[data-mini-slot-filled="true"]')).toHaveCount(2);
});

test('opens the candidate panel from the category deep link', async ({ page }) => {
  await loginAsUser(page);

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(emptyDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [candidatePart('part-gpu-deeplink', 'GPU', '딥링크 GPU 후보')], page: 0, size: 20, total: 1 })
    });
  });

  await page.goto('/self-quote?category=GPU');

  const panel = page.getByTestId('slot-candidate-panel');
  await expect(panel).toBeVisible();
  await expect(panel.getByRole('heading', { name: 'GPU 부품 목록' })).toBeVisible();
  await expect(page.getByTestId('slot-GPU')).toHaveAttribute('data-selected', 'true');
  await expect(panel.getByText('딥링크 GPU 후보')).toBeVisible();

  await panel.getByRole('button', { name: '후보 패널 닫기' }).click();
  await expect(page.getByTestId('slot-candidate-panel')).toHaveCount(0);
  await expect(page).toHaveURL('/self-quote');
});

test('redirects logged-out slot board access to login', async ({ page }) => {
  await page.goto('/self-quote');

  // 기존 인증 정책 유지: 비로그인 진입은 로그인으로 리다이렉트된다.
  await expect(page).toHaveURL('/login?redirect=%2Fself-quote');
  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible();
});

test('keeps the slot board usable on mobile width with a bottom sheet panel', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await loginAsUser(page);

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...emptyDraft,
        items: [draftItem('part-mobile-gpu-test', 'GPU', '모바일 RTX 테스트', 890000)],
        totalPrice: 890000,
        itemCount: 1
      })
    });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [candidatePart('part-mobile-gpu-candidate', 'GPU', '모바일 후보 GPU')], page: 0, size: 20, total: 1 })
    });
  });

  await page.goto('/self-quote');

  await expect(page.getByTestId('slot-board')).toBeVisible();
  await expect(page.getByTestId('checklist-GPU')).toContainText('모바일 RTX 테스트');
  await expect(page.getByTestId('slot-GPU')).toHaveAttribute('title', '모바일 RTX 테스트');
  await expect(page.getByTestId('slot-status-bar')).toBeVisible();

  await page.getByRole('button', { name: 'GPU 슬롯 열기' }).click();
  const panel = page.getByTestId('slot-candidate-panel');
  await expect(panel).toBeVisible();
  await expect(panel.getByText('모바일 후보 GPU')).toBeVisible();

  const hasBodyOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  expect(hasBodyOverflow).toBe(false);
});

test('opens checkout from self quote purchase CTA without using the build result route', async ({ page }) => {
  await loginAsUser(page);

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(checkoutDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  const purchaseLink = page.getByTestId('slot-status-bar').getByRole('link', { name: '구매하기' });
  await expect(purchaseLink).toHaveAttribute('href', '/checkout');
  await purchaseLink.click();

  await expect(page).toHaveURL('/checkout');
  await expect(page).not.toHaveURL(/\/builds\/00000000-0000-4000-8000-000000002001/);
});

test('saves current self quote slots into my quotes', async ({ page }) => {
  const saveRequests: unknown[] = [];
  await loginAsUser(page);

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(checkoutDraft) });
  });
  await page.route('**/api/builds/from-chat', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}');
    saveRequests.push(body);
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 'saved-self-quote-build' }) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');
  const statusBar = page.getByTestId('slot-status-bar');
  await statusBar.getByRole('button', { name: '내 견적함에 추가' }).click();

  await expect.poll(() => saveRequests.length).toBe(1);
  expect(saveRequests[0]).toMatchObject({
    sourceBuildId: 'self-quote-draft-checkout-test',
    lastUserMessage: '셀프 견적에서 저장',
    build: {
      id: 'self-quote-draft-checkout-test',
      title: '셀프 견적 저장 조합',
      totalPrice: 1_400_000,
      items: [
        { partId: 'part-checkout-gpu', category: 'GPU', quantity: 1, price: 980_000 },
        { partId: 'part-checkout-cpu', category: 'CPU', quantity: 1, price: 420_000 }
      ]
    }
  });
  // 저장 후 현재 화면을 유지한 채 성공 메시지와 내 견적함 링크를 보여준다.
  await expect(page).toHaveURL('/self-quote');
  await expect(statusBar.getByText('내 견적함에 추가했습니다.')).toBeVisible();
  await expect(statusBar.getByRole('link', { name: '내 견적함 보기' })).toHaveAttribute('href', '/my/quotes');
});

test('renders checkout from current quote draft and completes demo payment snapshot', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    sessionStorage.clear();
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(checkoutDraft)
    });
  });

  await page.goto('/checkout');

  await expect(page.getByRole('heading', { name: '구매 전 확인' })).toBeVisible();
  await expect(page.getByText('주문 부품 2개')).toBeVisible();
  await expect(page.getByText('RTX 5070 구매 테스트')).toBeVisible();
  await expect(page.getByText('Ryzen 7 구매 테스트')).toBeVisible();
  await expect(page.getByText('1,400,000원').first()).toBeVisible();
  await expect(page.getByRole('link', { name: 'RTX 5070 구매 테스트 구매처 이동' })).toHaveAttribute('href', 'https://example.test/checkout-gpu');
  await expect(page.getByRole('button', { name: 'Ryzen 7 구매 테스트 구매처 정보 없음' })).toBeDisabled();

  await page.getByRole('button', { name: '1,400,000원 데모 결제하기' }).click();

  await expect(page).toHaveURL('/checkout/complete');
  await expect(page.getByRole('heading', { name: '데모 결제 완료' })).toBeVisible();
  await expect(page.getByText('RTX 5070 구매 테스트')).toBeVisible();
  await expect(page.getByText('Ryzen 7 구매 테스트')).toBeVisible();
  await expect(page.getByText(/BG-\d{8}-/).first()).toBeVisible();
  await expect(page.getByRole('link', { name: '구매처 링크 다시 확인' })).toHaveAttribute('href', '/checkout');
});

test('shows checkout empty state and keeps mobile layout within viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    sessionStorage.clear();
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-empty-checkout',
        status: 'EMPTY',
        name: '셀프 견적',
        items: [],
        totalPrice: 0,
        itemCount: 0
      })
    });
  });

  await page.goto('/checkout');

  await expect(page.getByRole('heading', { name: '구매할 부품이 없습니다' })).toBeVisible();
  await expect(page.getByRole('link', { name: '셀프 견적으로 돌아가기' })).toHaveAttribute('href', '/self-quote');

  const hasBodyOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  expect(hasBodyOverflow).toBe(false);
});

test('self quote chatbot sends current draft and never mutates the draft automatically', async ({ page }) => {
  const buildChatBodies: unknown[] = [];
  const draftMutationMethods: string[] = [];
  const gpuDraft = {
    id: 'draft-chat-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [draftItem('part-gpu-chat', 'GPU', 'RTX 5070 챗봇 테스트', 890000)],
    totalPrice: 890000,
    itemCount: 1
  };
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-test',
      email: 'user@example.com',
      name: 'Demo User',
      role: 'USER'
    }));
    sessionStorage.clear();
  });

  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'user-test',
        email: 'user@example.com',
        name: 'Demo User',
        role: 'USER'
      })
    });
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const method = route.request().method();
    if (method !== 'GET') {
      draftMutationMethods.push(method);
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(gpuDraft)
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await mockEmptyPriceHistory(route, 'part-gpu-chat');
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 })
    });
  });

  await page.route('**/api/ai/build-chat', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}');
    buildChatBodies.push(body);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'BUDGET',
        message: '현재 견적에 담긴 부품은 유지하고 나머지 카테고리를 내부 자산 기준으로 채웠습니다.',
        builds: [],
        warnings: []
      })
    });
  });

  await page.goto('/self-quote');
  await expect(page.getByTestId('checklist-GPU')).toContainText('RTX 5070 챗봇 테스트');
  await page.getByRole('button', { name: 'AI에게 물어보기' }).click();
  const chatbotPanel = page.getByTestId('ai-chatbot-panel');
  await expect(chatbotPanel.getByRole('button', { name: '200만원 게이밍 PC' })).toBeVisible();
  await expect(chatbotPanel.getByRole('button', { name: '견적 마저 채우기' })).toBeVisible();
  await expect(chatbotPanel.getByRole('button', { name: '성능 비교' })).toBeVisible();
  await expect(chatbotPanel.getByRole('button', { name: '800만원 PC 추천' })).toHaveCount(0);
  await expect(chatbotPanel.getByRole('button', { name: '9950X3D 상세' })).toHaveCount(0);
  await expect(chatbotPanel.getByRole('button', { name: '내 견적함' })).toHaveCount(0);
  // 견적 완성 요청은 현재 견적(드래프트) 문맥이 필요하므로 서버로 draft가 전송돼야 한다
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('지금 견적 기준으로 나머지 부품 채워줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatBodies.length).toBe(1);
  expect((buildChatBodies[0] as { currentQuoteDraft?: { items?: Array<{ partId: string }> } }).currentQuoteDraft?.items?.[0]?.partId).toBe('part-gpu-chat');
  await expect(page.getByTestId('ai-chat-messages')).toContainText('나머지 카테고리를 내부 자산 기준으로 채웠습니다.');

  expect(draftMutationMethods).toHaveLength(0);
  // 부품명은 체크리스트(품목 지도)와 슬롯 카드 양쪽에 반영된다. 데스크톱(lg)에서 보드 슬롯은 절대위치
  // + overflow-hidden 컴팩트 레이아웃이라 긴 이름이 클리핑될 수 있으므로, 항상 보이는 체크리스트로 확인한다.
  await expect(page.getByTestId('checklist-GPU').getByText('RTX 5070 챗봇 테스트')).toBeVisible();
});

test('opens cooler candidate panel from home category link', async ({ page }) => {
  await loginAsUser(page);

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    const category = url.searchParams.get('category') ?? '';
    const items = category === 'COOLER'
      ? [
          {
            id: 'part-cooler-home-test',
            category: 'COOLER',
            name: 'Liquid Freezer III 360 테스트',
            manufacturer: 'ARCTIC',
            price: 165000,
            status: 'ACTIVE',
            benchmarkSummary: { score: 77.7 },
            attributes: {
              shortSpec: '360mm AIO, AM5/LGA1851'
            },
            externalOffer: {
              imageUrl: 'https://example.test/cooler.png',
              supplierName: '쿨러테스트몰',
              offerUrl: 'https://example.test/cooler',
              lowPrice: 165000,
              source: 'NAVER_SHOPPING_SEARCH'
            }
          }
        ]
      : [];

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        page: 0,
        size: 20,
        total: items.length
      })
    });
  });

  await page.goto('/');
  await page.getByRole('link', { name: /전체 부품/ }).first().click();
  await expect(page).toHaveURL('/self-quote');
  await page.getByRole('button', { name: '쿨러 슬롯 열기' }).click();

  await expect(page).toHaveURL('/self-quote?category=COOLER');
  const panel = page.getByTestId('slot-candidate-panel');
  await expect(panel.getByRole('heading', { name: '쿨러 부품 목록' })).toBeVisible();
  await expect(panel.getByText('Liquid Freezer III 360 테스트')).toBeVisible();
  await expect(panel.getByText('쿨러테스트몰')).toBeVisible();
  await expect(page.getByText('ACTIVE')).toHaveCount(0);
  await expect(page.getByText('77.7')).toHaveCount(0);
});

test('opens GPU candidate panel from home category link', async ({ page }) => {
  await loginAsUser(page);

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    const category = url.searchParams.get('category') ?? '';
    const items = category === 'GPU'
      ? [
          {
            id: 'part-gpu-home-test',
            category: 'GPU',
            name: '홈에서 열린 RTX 테스트',
            manufacturer: 'NVIDIA',
            price: 890000,
            status: 'ACTIVE',
            benchmarkSummary: { score: 92.4 },
            externalOffer: {
              imageUrl: 'https://example.test/home-rtx.png',
              supplierName: '홈테스트몰',
              offerUrl: 'https://example.test/home-rtx',
              lowPrice: 890000,
              source: 'NAVER_SHOPPING_SEARCH'
            }
          }
        ]
      : [];

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        page: 0,
        size: 20,
        total: items.length
      })
    });
  });

  await page.goto('/');
  await page.getByRole('link', { name: /전체 부품/ }).first().click();
  await expect(page).toHaveURL('/self-quote');
  await page.getByRole('button', { name: 'GPU', exact: true }).click();

  await expect(page).toHaveURL('/self-quote?category=GPU');
  const panel = page.getByTestId('slot-candidate-panel');
  await expect(panel.getByRole('heading', { name: 'GPU 부품 목록' })).toBeVisible();
  await expect(panel.getByText('홈에서 열린 RTX 테스트')).toBeVisible();
  await expect(panel.getByText('홈테스트몰')).toBeVisible();
});

test('compares recent AI recommendation builds side by side and applies one', async ({ page }) => {
  // R1 견적 비교: 챗봇이 sessionStorage에 남긴 최근 추천 배치(3안)를 나란히 보여주고,
  // 현재 견적 대비 diff(동일/교체됨/추가됨)를 표시하며, 기존 일괄 적용 API로 안을 적용한다.
  const compareBuild = (tier: string, tierLabel: string, gpuPartId: string, gpuName: string, cpuPartId: string, cpuName: string, totalPrice: number) => ({
    id: `ai-${tier}`,
    tier,
    label: tierLabel.replace('형', ''),
    title: `${tierLabel} 추천 조합`,
    summary: `${tierLabel} 데모 조합`,
    totalPrice,
    badges: [],
    budgetWon: 2000000,
    budgetLabel: '200만원',
    tierLabel,
    appliedPartCategories: ['GPU', 'CPU'],
    items: [
      { partId: gpuPartId, category: 'GPU', name: gpuName, manufacturer: 'NVIDIA', quantity: 1, price: Math.round(totalPrice * 0.6), note: '' },
      { partId: cpuPartId, category: 'CPU', name: cpuName, manufacturer: 'AMD', quantity: 1, price: Math.round(totalPrice * 0.4), note: '' }
    ]
  });

  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({ id: 'user-test', email: 'user@example.com', name: 'Demo User', role: 'USER' }));
  });
  await page.addInitScript((builds) => {
    sessionStorage.setItem('buildgraph.ai.assistantSession:user-test', JSON.stringify({
      messages: [
        { id: 'msg-user', role: 'user', text: '게이밍 200만원', createdAt: '2026-07-06T09:00:00.000Z' },
        { id: 'msg-assistant', role: 'assistant', text: '추천 조합 3개를 구성했습니다.', createdAt: '2026-07-06T09:00:05.000Z', builds }
      ],
      latestBuilds: builds,
      savedBuildIds: {},
      updatedAt: '2026-07-06T09:00:05.000Z'
    }));
  }, [
    compareBuild('budget', '가성비형', 'part-gpu-budget', '가성비 GPU', 'part-cpu-budget', '가성비 CPU', 1500000),
    compareBuild('balanced', '균형형', 'part-gpu-current', '현재 장착 GPU', 'part-cpu-balanced', '균형 CPU', 1900000),
    compareBuild('performance', '고성능형', 'part-gpu-perf', '고성능 GPU', 'part-cpu-perf', '고성능 CPU', 2400000)
  ]);

  const applyRequests: Array<{ buildId: string; itemCount: number }> = [];
  let compareDraftItems = [draftItem('part-gpu-current', 'GPU', '현재 장착 GPU', 1140000)];

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() === 'PUT' && url.pathname.endsWith('/apply-ai-build')) {
      const body = JSON.parse(route.request().postData() ?? '{}') as { buildId: string; items: Array<{ partId: string; category: string; quantity: number }> };
      applyRequests.push({ buildId: body.buildId, itemCount: body.items.length });
      compareDraftItems = body.items.map((item, index) =>
        draftItem(item.partId, item.category, item.partId === 'part-gpu-budget' ? '가성비 GPU' : item.partId === 'part-cpu-budget' ? '가성비 CPU' : `적용 부품 ${index}`, 750000)
      );
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...emptyDraft,
        items: compareDraftItems,
        totalPrice: compareDraftItems.reduce((sum, item) => sum + item.lineTotal, 0),
        itemCount: compareDraftItems.reduce((sum, item) => sum + item.quantity, 0)
      })
    });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  // 트리거 → 패널 열기
  await page.getByTestId('quote-compare-open').click();
  const panel = page.getByTestId('quote-compare-panel');
  await expect(panel).toBeVisible();

  // 3안이 티어 순서로 나란히, 총액 표시
  await expect(panel.getByTestId('quote-compare-column-budget')).toHaveAttribute('data-tier', 'budget');
  await expect(panel.getByTestId('quote-compare-total-budget')).toHaveText('1,500,000원');
  await expect(panel.getByTestId('quote-compare-total-performance')).toHaveText('2,400,000원');

  // diff: 균형안의 GPU는 현재 장착과 동일(same), 가성비안의 GPU는 교체됨(changed), CPU는 드래프트에 없어 추가됨(added)
  await expect(panel.getByTestId('quote-compare-column-balanced').locator('[data-diff="same"]')).toHaveCount(1);
  await expect(panel.getByTestId('quote-compare-column-budget').locator('[data-diff="changed"]')).toHaveCount(1);
  await expect(panel.getByTestId('quote-compare-column-budget').locator('[data-diff="added"]')).toHaveCount(1);

  // 가성비안 적용 → 기존 일괄 적용 API 호출 + 해당 컬럼이 '현재 적용됨'으로 전환
  await panel.getByTestId('quote-compare-apply-budget').click();
  await expect.poll(() => applyRequests).toEqual([{ buildId: 'ai-budget', itemCount: 2 }]);
  await expect(panel.getByTestId('quote-compare-column-budget')).toHaveAttribute('data-current', 'true');
  await expect(panel.getByTestId('quote-compare-apply-budget')).toBeDisabled();
  // 적용 후 선택 빌드 패널도 나타난다(챗봇 선택과 같은 경로).
  await expect(page.getByTestId('ai-selected-build-panel')).toBeVisible();
});

test('hides the compare entry when there is no recent AI recommendation batch', async ({ page }) => {
  await loginAsUser(page);
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(emptyDraft) });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  await expect(page.getByTestId('quote-start-banner')).toBeVisible();
  await expect(page.getByTestId('quote-compare-open')).toHaveCount(0);
});

test('upgrade advisor turns a symptom into a replacement simulation question', async ({ page }) => {
  // R1 업그레이드 진단: 증상 선택 → 현재 장착 부품보다 상위 후보 2안(최소/고성능) 제시 →
  // 클릭하면 기존 교체 시뮬레이션 질문("…로 바꾸면 어때?")을 챗봇에 자동 전송한다.
  // 챗봇 전송은 ownerKey(authUser)가 필요하다 — 토큰만으로는 로그인 안내로 빠진다.
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({ id: 'user-test', email: 'user@example.com', name: 'Demo User', role: 'USER' }));
  });
  const partSearches: Array<{ category: string | null; minPrice: string | null }> = [];
  const chatMessages: string[] = [];

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...emptyDraft,
        items: [draftItem('part-gpu-now', 'GPU', '현재 GPU', 900000)],
        totalPrice: 900000,
        itemCount: 1
      })
    });
  });
  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    const minPrice = url.searchParams.get('minPrice');
    partSearches.push({ category: url.searchParams.get('category'), minPrice });
    const items = minPrice === '900001'
      ? [candidatePart('part-gpu-step', 'GPU', '한 단계 위 GPU', { price: 1100000 })]
      : minPrice === '1440000'
        ? [candidatePart('part-gpu-max', 'GPU', '고성능 GPU', { price: 1600000 })]
        : [];
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items, page: 0, size: 1, total: items.length }) });
  });
  await page.route('**/api/ai/build-chat', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}') as { message?: string };
    chatMessages.push(body.message ?? '');
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ answerType: 'GENERAL', message: '교체 성능을 비교했습니다.', builds: [], warnings: [] })
    });
  });

  await page.goto('/self-quote');

  await page.getByTestId('upgrade-advisor-open').click();
  await expect(page.getByTestId('upgrade-advisor-panel')).toBeVisible();
  await page.getByTestId('upgrade-symptom-frame').click();

  // 현재가(90만) 기준 상위 후보 두 티어를 조회한다.
  await expect(page.getByTestId('upgrade-option-minimal')).toContainText('한 단계 위 GPU');
  await expect(page.getByTestId('upgrade-option-minimal')).toContainText('(+200,000원)');
  await expect(page.getByTestId('upgrade-option-performance')).toContainText('고성능 GPU');
  expect(partSearches.filter((search) => search.category === 'GPU').map((search) => search.minPrice)).toEqual(
    expect.arrayContaining(['900001', '1440000'])
  );

  // 후보 클릭 → 챗봇이 열리고 교체 시뮬레이션 질문이 자동 전송된다.
  await page.getByTestId('upgrade-option-minimal').click();
  await expect(page.getByTestId('ai-chatbot-panel')).toBeVisible();
  await expect.poll(() => chatMessages).toEqual(['한 단계 위 GPU로 바꾸면 어때?']);
});

test('upgrade advisor guides to mount the category first when it is missing', async ({ page }) => {
  await loginAsUser(page);
  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...emptyDraft,
        items: [draftItem('part-cpu-now', 'CPU', '현재 CPU', 400000)],
        totalPrice: 400000,
        itemCount: 1
      })
    });
  });
  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) });
  });

  await page.goto('/self-quote');

  await page.getByTestId('upgrade-advisor-open').click();
  await page.getByTestId('upgrade-symptom-frame').click();

  // GPU가 견적에 없으므로 진단 대신 장착 안내 + 후보 패널 열기로 이어준다.
  await expect(page.getByTestId('upgrade-advisor-panel')).toContainText('먼저 GPU를 견적에 담아 주세요');
  await page.getByTestId('upgrade-open-slot').click();
  await expect(page).toHaveURL('/self-quote?category=GPU');
  await expect(page.getByTestId('slot-candidate-panel')).toBeVisible();
});

test('shows selected AI build separately from the slot board and marks duplicate parts', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-test',
      email: 'user@example.com',
      name: 'Demo User',
      role: 'USER'
    }));
    sessionStorage.setItem('buildgraph.ai.selectedBuild:user-test', JSON.stringify({
      id: 'ai-balanced',
      tier: 'balanced',
      title: '균형 추천 조합',
      summary: 'QHD 게임과 개발을 함께 고려한 데모 조합입니다.',
      totalPrice: 1980000,
      appliedPartCategories: ['GPU'],
      selectedAt: '2026-06-30T09:00:00.000Z',
      items: [
        {
          partId: 'part-gpu-test',
          category: 'GPU',
          name: 'RTX 4070 SUPER 테스트',
          manufacturer: 'NVIDIA',
          quantity: 1,
          price: 890000,
          note: 'QHD 게임용 그래픽카드'
        },
        {
          partId: 'ai-cpu-balanced',
          category: 'CPU',
          name: 'Ryzen 7 AI 균형 CPU',
          manufacturer: 'AMD',
          quantity: 1,
          price: 420000,
          note: '게임과 개발 균형'
        }
      ]
    }));
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-ai-panel-test',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [draftItem('part-gpu-test', 'GPU', 'RTX 4070 SUPER 테스트', 890000)],
        totalPrice: 890000,
        itemCount: 1
      })
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await mockEmptyPriceHistory(route, 'part-gpu-test');
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [candidatePart('part-gpu-test', 'GPU', 'RTX 4070 SUPER 테스트', { price: 890000 })],
        page: 0,
        size: 20,
        total: 1
      })
    });
  });

  await page.goto('/self-quote?category=GPU');

  const aiPanel = page.getByTestId('ai-selected-build-panel');
  await expect(aiPanel).toBeVisible();
  await expect(aiPanel.getByRole('heading', { name: 'AI 선택 조합' })).toBeVisible();
  await expect(aiPanel.getByText('균형 추천 조합')).toBeVisible();
  await expect(aiPanel.getByText('GPU 반영됨')).toBeVisible();
  await expect(aiPanel.getByText('실제 장바구니 적용 기록')).toBeVisible();
  await expect(aiPanel.getByText('현재 견적 합계')).toBeVisible();
  await expect(aiPanel.getByText('최초 AI 조합: 1,310,000원')).toBeVisible();
  await expect(aiPanel.getByText('담김', { exact: true })).toBeVisible();
  await expect(aiPanel.getByText('미반영', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '셀프 견적 · 구성 관계도' })).toBeVisible();
  await expect(page.getByText('견적 합계', { exact: true })).toBeVisible();
});

test('keeps selected AI build current total without slot board AI price movement summary', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-test',
      email: 'user@example.com',
      name: 'Demo User',
      role: 'USER'
    }));
    sessionStorage.setItem('buildgraph.ai.selectedBuild:user-test', JSON.stringify({
      id: 'ai-price-change-savings',
      tier: 'balanced',
      title: '가격 비교 추천 조합',
      summary: '추천 시점 대비 현재가 비교 테스트 조합입니다.',
      totalPrice: 1310000,
      appliedPartCategories: ['GPU', 'CPU'],
      selectedAt: '2026-06-30T09:00:00.000Z',
      items: [
        {
          partId: 'part-gpu-price-change',
          category: 'GPU',
          name: 'RTX 가격 비교 GPU',
          manufacturer: 'NVIDIA',
          quantity: 1,
          price: 890000,
          note: '추천 시점 GPU 가격'
        },
        {
          partId: 'part-cpu-price-change',
          category: 'CPU',
          name: 'Ryzen 가격 비교 CPU',
          manufacturer: 'AMD',
          quantity: 1,
          price: 420000,
          note: '추천 시점 CPU 가격'
        }
      ]
    }));
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-price-change-savings',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [
          {
            ...draftItem('part-gpu-price-change', 'GPU', 'RTX 가격 비교 GPU', 890000),
            currentPrice: 850000,
            lineTotal: 850000
          },
          {
            ...draftItem('part-cpu-price-change', 'CPU', 'Ryzen 가격 비교 CPU', 420000),
            currentPrice: 410000,
            lineTotal: 410000
          }
        ],
        totalPrice: 1260000,
        itemCount: 2
      })
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await mockEmptyPriceHistory(route, 'part-price-change');
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 })
    });
  });

  await page.goto('/self-quote');

  const aiPanel = page.getByTestId('ai-selected-build-panel');
  const statusBar = page.getByTestId('slot-status-bar');

  await expect(aiPanel.getByTestId('ai-selected-build-current-total')).toHaveText('1,260,000원');
  await expect(aiPanel.getByText('현재 견적 합계')).toBeVisible();
  await expect(aiPanel.getByText('최초 AI 조합: 1,310,000원')).toBeVisible();
  await expect(statusBar.getByTestId('quote-price-change-summary')).toHaveCount(0);
  await expect(statusBar.getByTestId('quote-price-change-list')).toHaveCount(0);
  await expect(statusBar.getByText(/AI 추천 시점 대비/)).toHaveCount(0);
  await expect(aiPanel.getByText(/절감|상승|변동 없음/)).toHaveCount(0);
});

test('does not show selected AI build increase summary in the slot status bar', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-test',
      email: 'user@example.com',
      name: 'Demo User',
      role: 'USER'
    }));
    sessionStorage.setItem('buildgraph.ai.selectedBuild:user-test', JSON.stringify({
      id: 'ai-price-change-increase',
      tier: 'balanced',
      title: '상승 비교 추천 조합',
      summary: '추천 시점 대비 현재가 상승 비교 테스트 조합입니다.',
      totalPrice: 1000000,
      appliedPartCategories: ['GPU'],
      selectedAt: '2026-06-30T09:00:00.000Z',
      items: [
        {
          partId: 'part-gpu-price-increase',
          category: 'GPU',
          name: 'RTX 상승 비교 GPU',
          manufacturer: 'NVIDIA',
          quantity: 1,
          price: 1000000,
          note: '추천 시점 GPU 가격'
        }
      ]
    }));
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-price-change-increase',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [
          {
            ...draftItem('part-gpu-price-increase', 'GPU', 'RTX 상승 비교 GPU', 1000000),
            currentPrice: 1080000,
            lineTotal: 1080000
          }
        ],
        totalPrice: 1080000,
        itemCount: 1
      })
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await mockEmptyPriceHistory(route, 'part-gpu-price-increase');
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 })
    });
  });

  await page.goto('/self-quote');

  const statusBar = page.getByTestId('slot-status-bar');
  await expect(statusBar).toBeVisible();
  await expect(statusBar.getByTestId('quote-price-change-summary')).toHaveCount(0);
  await expect(statusBar.getByTestId('quote-price-change-list')).toHaveCount(0);
  await expect(statusBar.getByText(/AI 추천 시점 대비/)).toHaveCount(0);
});

test('does not show selected AI build no-movement summary in the slot status bar', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-test',
      email: 'user@example.com',
      name: 'Demo User',
      role: 'USER'
    }));
    sessionStorage.setItem('buildgraph.ai.selectedBuild:user-test', JSON.stringify({
      id: 'ai-price-change-same',
      tier: 'balanced',
      title: '동일 가격 추천 조합',
      summary: '추천 시점 대비 현재가 동일 테스트 조합입니다.',
      totalPrice: 890000,
      appliedPartCategories: ['GPU'],
      selectedAt: '2026-06-30T09:00:00.000Z',
      items: [
        {
          partId: 'part-gpu-price-same',
          category: 'GPU',
          name: 'RTX 동일 가격 GPU',
          manufacturer: 'NVIDIA',
          quantity: 1,
          price: 890000,
          note: '추천 시점 GPU 가격'
        }
      ]
    }));
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-price-change-same',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [draftItem('part-gpu-price-same', 'GPU', 'RTX 동일 가격 GPU', 890000)],
        totalPrice: 890000,
        itemCount: 1
      })
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await mockEmptyPriceHistory(route, 'part-gpu-price-same');
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 })
    });
  });

  await page.goto('/self-quote');

  const statusBar = page.getByTestId('slot-status-bar');
  await expect(statusBar).toBeVisible();
  await expect(statusBar.getByTestId('quote-price-change-summary')).toHaveCount(0);
  await expect(statusBar.getByTestId('quote-price-change-list')).toHaveCount(0);
  await expect(statusBar.getByText(/AI 추천 시점 대비/)).toHaveCount(0);
});

test('syncs selected AI panel total and item state after chatbot part replacement', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-test',
      email: 'user@example.com',
      name: 'Demo User',
      role: 'USER'
    }));
    sessionStorage.setItem('buildgraph.ai.selectedBuild:user-test', JSON.stringify({
      id: 'ai-performance',
      tier: 'performance',
      title: '고성능형 추천 조합',
      summary: 'RTX 5090을 포함한 AI 추천 조합입니다.',
      totalPrice: 11606530,
      appliedPartCategories: ['GPU'],
      selectedAt: '2026-06-30T09:00:00.000Z',
      items: [
        {
          partId: 'part-gpu-5090-original',
          category: 'GPU',
          name: '조텍 GAMING 지포스 RTX 5090 SOLID OC D7 32GB',
          manufacturer: '조텍',
          quantity: 1,
          price: 5002190,
          note: 'AI 최초 추천 GPU'
        },
        {
          partId: 'part-cpu-ai-original',
          category: 'CPU',
          name: 'AI 최초 추천 CPU',
          manufacturer: 'AMD',
          quantity: 1,
          price: 6604340,
          note: 'AI 최초 추천 CPU'
        }
      ]
    }));
  });

  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'user-test',
        email: 'user@example.com',
        name: 'Demo User',
        role: 'USER'
      })
    });
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-ai-replaced-panel-test',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [draftItem('part-gpu-5080-replaced', 'GPU', 'MSI 지포스 RTX 5080 쉐도우 3X OC D7 16GB MSI코리아', 2078000)],
        totalPrice: 8682340,
        itemCount: 1
      })
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await mockEmptyPriceHistory(route, 'part-gpu-5080-replaced');
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 })
    });
  });

  await page.goto('/self-quote?category=GPU');

  const aiPanel = page.getByTestId('ai-selected-build-panel');
  await expect(aiPanel.getByText('현재 견적 합계')).toBeVisible();
  await expect(aiPanel.getByText('8,682,340원')).toBeVisible();
  await expect(aiPanel).toContainText('교체');
  await expect(aiPanel).toContainText('RTX 5080');
  await expect(page.getByText('견적 합계', { exact: true })).toBeVisible();
  await expect(page.getByText('8,682,340원').first()).toBeVisible();
});

test('shows price trend chart on product detail page', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'user-test',
        email: 'user@example.com',
        name: 'Demo User',
        role: 'USER'
      })
    });
  });

  await page.route('**/api/parts/part-gpu-trend-test**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-gpu-trend-test',
          partName: '가격 추이 RTX 테스트',
          currentPrice: 950000,
          days: 3650,
          source: 'NAVER_SHOPPING_SEARCH',
          items: [
            { price: 1030000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-05T00:00:00Z' },
            { price: 1020000, source: 'DANAWA_PRICE_TREND', collectedAt: '2026-06-01T00:00:00Z' },
            { price: 990000, source: 'DANAWA_PRICE_TREND', collectedAt: '2026-06-20T00:00:00Z' },
            { price: 950000, source: 'DANAWA_PRICE_TREND', collectedAt: '2026-07-01T00:00:00Z' }
          ],
          summary: {
            sampleCount: 4,
            currentPrice: 950000,
            minPrice: 950000,
            maxPrice: 1030000,
            firstPrice: 1030000,
            lastPrice: 950000,
            changeAmount: -80000,
            changeRatePercent: -7.77
          }
        })
      });
      return;
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'part-gpu-trend-test',
        category: 'GPU',
        name: '가격 추이 RTX 테스트',
        manufacturer: 'NVIDIA',
        price: 950000,
        status: 'ACTIVE',
        latestPriceCollectedAt: '2026-06-29T00:00:00Z',
        attributes: {
          shortSpec: 'RTX price trend test',
          vramGb: 12,
          wattage: 220
        },
        externalOffer: {
          imageUrl: 'https://example.test/trend-gpu.png',
          supplierName: '가격테스트몰',
          offerUrl: 'https://example.test/trend-gpu',
          lowPrice: 950000,
          source: 'NAVER_SHOPPING_SEARCH'
        }
      })
    });
  });

  await page.goto('/parts/part-gpu-trend-test');

  await expect(page.getByRole('heading', { name: '가격 추이 RTX 테스트' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '주요 스펙' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '가격 변동 추이' })).toBeVisible();
  await expect(page.getByRole('img', { name: '가격 변동 추이 그래프' })).toBeVisible();
  await expect(page.getByText('950,000원').first()).toBeVisible();
  await expect(page.getByText('26.06')).toBeVisible();
  await expect(page.getByText('26.07')).toBeVisible();
  await expect(page.getByLabel('가격 변동 추이 그래프').getByText('현재가')).toBeVisible();
  await expect(page.getByText('103만')).toBeVisible();
  await expect(page.getByText('99만')).toBeVisible();
  await expect(page.getByText('95만')).toBeVisible();
  await expect(page.getByText('-80,000원 (-7.77%)')).toBeVisible();
  await page.getByTestId('price-trend-point').first().hover();
  await expect(page.getByTestId('price-trend-tooltip')).toBeVisible();
  await expect(page.getByTestId('price-trend-tooltip').getByText(/다나와 추이/)).toBeVisible();
  await expect(page.getByText('전체 내부 스펙')).toHaveCount(0);
});

test('goes home after login from product detail redirect', async ({ page }) => {
  let savedToDraft = false;

  await page.route('**/api/parts/part-gpu-detail-test', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'part-gpu-detail-test',
        category: 'GPU',
        name: '상세 담기 RTX 테스트',
        manufacturer: 'NVIDIA',
        price: 1200000,
        status: 'ACTIVE',
        attributes: {
          shortSpec: 'RTX detail save test',
          toolReady: true
        },
        externalOffer: {
          imageUrl: 'https://example.test/detail-gpu.png',
          supplierName: '상세테스트몰',
          offerUrl: 'https://example.test/detail-gpu',
          lowPrice: 1200000,
          source: 'NAVER_SHOPPING_SEARCH'
        }
      })
    });
  });

  await page.route('**/api/auth/login', async (route) => {
    expect(JSON.parse(route.request().postData() ?? '{}')).toEqual({
      email: 'user@example.com',
      password: 'passw0rd!'
    });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'jwt-user-token',
        refreshToken: 'demo-refresh-user',
        user: {
          id: 'user-test',
          email: 'user@example.com',
          name: 'Demo User',
          role: 'USER'
        }
      })
    });
  });

  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'user-test',
        email: 'user@example.com',
        name: 'Demo User',
        role: 'USER'
      })
    });
  });

  await page.route('**/api/quote-drafts/current/items/part-gpu-detail-test', async (route) => {
    expect(route.request().method()).toBe('PUT');
    expect(route.request().headers().authorization).toBe('Bearer jwt-user-token');
    savedToDraft = true;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-detail-test',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [
          {
            id: 'draft-item-detail-test',
            partId: 'part-gpu-detail-test',
            category: 'GPU',
            name: '상세 담기 RTX 테스트',
            quantity: 1,
            currentPrice: 1200000,
            lineTotal: 1200000
          }
        ],
        totalPrice: 1200000,
        itemCount: 1
      })
    });
  });

  await page.goto('/parts/part-gpu-detail-test');
  await expect(page).toHaveURL('/login?redirect=%2Fparts%2Fpart-gpu-detail-test');
  await page.getByLabel('이메일').fill('user@example.com');
  await page.getByLabel('비밀번호').fill('passw0rd!');
  await page.getByRole('button', { name: '로그인' }).click();

  await expect(page).toHaveURL('/');
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBe('demo-refresh-user');
  await expect(page.getByText('로그인됨 · user@example.com · USER')).toBeVisible();
  await expect(page.getByText('Demo User')).toBeVisible();
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();

  expect(savedToDraft).toBe(false);
});

test('records home recommendation detail and draft add events on product detail page', async ({ page }) => {
  const events: Array<{ eventType?: string; sourceSurface?: string; recommendationId?: string; rankPosition?: number; partId?: string }> = [];

  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/parts/part-home-rec-test**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-home-rec-test',
          partName: '홈 추천 상세 이벤트 GPU',
          currentPrice: 1500000,
          days: 3650,
          items: [{ price: 1500000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-07-03T00:00:00Z' }],
          summary: {
            sampleCount: 1,
            currentPrice: 1500000,
            minPrice: 1500000,
            maxPrice: 1500000,
            firstPrice: 1500000,
            lastPrice: 1500000,
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
        id: 'part-home-rec-test',
        category: 'GPU',
        name: '홈 추천 상세 이벤트 GPU',
        manufacturer: 'NVIDIA',
        price: 1500000,
        status: 'ACTIVE',
        attributes: { shortSpec: '추천 이벤트 저장 테스트', toolReady: true },
        externalOffer: {
          imageUrl: 'https://example.test/home-rec-gpu.png',
          supplierName: '추천테스트몰',
          offerUrl: null,
          lowPrice: 1500000,
          source: 'NAVER_SHOPPING_SEARCH'
        }
      })
    });
  });

  await page.route('**/api/recommendation-events', async (route) => {
    const body = route.request().postDataJSON() as { eventType?: string; sourceSurface?: string; recommendationId?: string; rankPosition?: number; partId?: string };
    events.push(body);
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        id: `event-${events.length}`,
        eventType: body.eventType,
        labelScore: body.eventType === 'ADD_PART_TO_DRAFT' ? 3 : 1,
        sourceSurface: body.sourceSurface,
        recommendationId: body.recommendationId,
        rankPosition: body.rankPosition,
        createdAt: '2026-07-03T10:00:00Z'
      })
    });
  });

  await page.route('**/api/quote-drafts/current/items/part-home-rec-test', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-home-rec-test',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [],
        totalPrice: 1500000,
        itemCount: 1
      })
    });
  });

  await page.goto('/parts/part-home-rec-test?recId=home-part-part-home-rec-test&recSurface=HOME_RECOMMENDED_PARTS&rank=2');
  await expect(page.getByRole('heading', { name: '홈 추천 상세 이벤트 GPU' })).toBeVisible();
  await expect.poll(() => events.some((event) => event.eventType === 'DETAIL_VIEW')).toBe(true);

  await page.getByRole('button', { name: '견적에 담기' }).click();
  await expect.poll(() => events.some((event) => event.eventType === 'ADD_PART_TO_DRAFT')).toBe(true);
  expect(events).toEqual(expect.arrayContaining([
    expect.objectContaining({
      eventType: 'DETAIL_VIEW',
      sourceSurface: 'HOME_RECOMMENDED_PARTS',
      recommendationId: 'home-part-part-home-rec-test',
      rankPosition: 2,
      partId: 'part-home-rec-test'
    }),
    expect.objectContaining({
      eventType: 'ADD_PART_TO_DRAFT',
      sourceSurface: 'HOME_RECOMMENDED_PARTS',
      recommendationId: 'home-part-part-home-rec-test',
      rankPosition: 2,
      partId: 'part-home-rec-test'
    })
  ]));
});
