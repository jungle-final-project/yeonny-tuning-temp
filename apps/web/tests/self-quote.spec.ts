import { expect, test } from '@playwright/test';

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
      { id: 'part-STORAGE', type: 'PART', category: 'STORAGE', label: 'SSD', status: 'PASS', detail: '저장장치' },
      { id: 'constraint-PRICE', type: 'CONSTRAINT', category: 'PRICE', label: '총액', status: 'PASS', detail: '예산 범위 확인' }
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
        id: 'edge-board-ram-ddr',
        source: 'part-MOTHERBOARD',
        target: 'part-RAM',
        type: 'REQUIRES',
        status: 'PASS',
        label: 'DDR 규격',
        summary: '메인보드와 RAM 규격을 확인합니다.'
      },
      {
        id: 'edge-gpu-psu-power',
        source: 'part-GPU',
        target: 'part-PSU',
        type: 'AFFECTS',
        status: 'WARN',
        label: '전력 여유',
        summary: 'GPU 권장 정격 파워를 기준으로 PSU 여유를 확인합니다.'
      },
      {
        id: 'edge-gpu-case-length',
        source: 'part-GPU',
        target: 'part-CASE',
        type: 'REQUIRES',
        status: 'PASS',
        label: '장착 길이',
        summary: 'GPU 길이가 케이스 허용 길이 안에 있습니다.'
      },
      {
        id: 'edge-cooler-case-height',
        source: 'part-COOLER',
        target: 'part-CASE',
        type: 'REQUIRES',
        status: 'PASS',
        label: '높이 여유',
        summary: '쿨러 높이가 케이스 허용 높이 안에 있습니다.'
      }
    ],
    focusNodeIds: ['part-GPU', 'part-PSU'],
    insights: [
      {
        id: 'insight-power',
        status: 'WARN',
        title: '파워 여유 확인',
        description: '현재 GPU 선택은 PSU 용량과 케이스 장착 조건을 같이 제한합니다.',
        relatedNodeIds: ['part-GPU', 'part-PSU']
      }
    ],
    toolResults: [
      { tool: 'power', status: 'WARN', confidence: 'MEDIUM', summary: 'PSU 정격 출력 여유를 확인해야 합니다.' }
    ]
  };
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

test('filters internal assets by sidebar category on self quote page', async ({ page }) => {
  const requestedCategories: string[] = [];
  const emptyDraft = {
    id: 'draft-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [],
    totalPrice: 0,
    itemCount: 0
  };
  const gpuDraft = {
    id: 'draft-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [
      {
        id: 'draft-item-gpu-test',
        partId: 'part-gpu-test',
        category: 'GPU',
        name: 'RTX 4070 SUPER 테스트',
        manufacturer: 'NVIDIA',
        quantity: 1,
        unitPriceAtAdd: 890000,
        currentPrice: 890000,
        lineTotal: 890000,
        attributes: {},
        externalOffer: {
          imageUrl: 'https://example.test/rtx4070.png',
          supplierName: '테스트몰',
          offerUrl: 'https://example.test/rtx4070',
          lowPrice: 890000,
          source: 'NAVER_SHOPPING_SEARCH'
        }
      }
    ],
    totalPrice: 890000,
    itemCount: 1
  };
  let draft: unknown = emptyDraft;

  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const method = route.request().method();
    if (method === 'PUT') {
      draft = gpuDraft;
    }
    if (method === 'DELETE') {
      draft = emptyDraft;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(draft)
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-gpu-test',
          partName: 'RTX 4070 SUPER 테스트',
          currentPrice: 890000,
          days: 3650,
          source: 'NAVER_SHOPPING_SEARCH',
          items: [
            { price: 890000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-29T00:00:00Z' }
          ],
          summary: {
            sampleCount: 1,
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
    const category = url.searchParams.get('category') ?? '';
    requestedCategories.push(category);

    const items = category === 'GPU'
      ? [
          {
            id: 'part-gpu-test',
            category: 'GPU',
            name: 'RTX 4070 SUPER 테스트',
            manufacturer: 'NVIDIA',
            price: 890000,
            status: 'ACTIVE',
            benchmarkSummary: { score: 92.4 },
            externalOffer: {
              imageUrl: 'https://example.test/rtx4070.png',
              supplierName: '테스트몰',
              offerUrl: 'https://example.test/rtx4070',
              lowPrice: 890000,
              source: 'NAVER_SHOPPING_SEARCH'
            }
          }
        ]
      : [
          {
            id: 'part-cpu-test',
            category: 'CPU',
            name: 'Ryzen 5 테스트',
            manufacturer: 'AMD',
            price: 260000,
            status: 'ACTIVE',
            benchmarkSummary: { score: 81.1 },
            externalOffer: {
              imageUrl: 'https://example.test/ryzen.png',
              supplierName: 'CPU테스트몰',
              offerUrl: 'https://example.test/ryzen',
              lowPrice: 260000,
              source: 'NAVER_SHOPPING_SEARCH'
            }
          }
        ];

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        page: 0,
        size: 50,
        total: items.length
      })
    });
  });

  await page.goto('/self-quote');
  await expect(page.getByText('셀프 견적 / 전체 부품 목록')).toBeVisible();
  await expect(page.getByText('Ryzen 5 테스트')).toBeVisible();
  await expect(page.getByRole('img', { name: 'Ryzen 5 테스트 제품 사진' })).toBeVisible();
  await expect(page.getByText('CPU테스트몰')).toBeVisible();

  await page.getByRole('button', { name: 'GPU' }).click();

  await expect(page.getByText('GPU 부품 목록')).toBeVisible();
  await expect(page.getByText('RTX 4070 SUPER 테스트')).toBeVisible();
  await expect(page.getByRole('img', { name: 'RTX 4070 SUPER 테스트 제품 사진' })).toBeVisible();
  await expect(page.getByText('테스트몰')).toBeVisible();
  await expect(page.getByText('ACTIVE')).toHaveCount(0);
  await expect(page.getByText('92.4')).toHaveCount(0);
  expect(requestedCategories).toContain('GPU');

  await page.getByRole('button', { name: 'RTX 4070 SUPER 테스트 견적 담기' }).click();
  await expect(page.getByText('견적 합계')).toBeVisible();
  await expect(page.getByText('890,000원')).toHaveCount(3);
  await expect(page.getByText('가격 기록 1개')).toBeVisible();

  await page.getByRole('button', { name: 'RTX 4070 SUPER 테스트 견적에서 제거' }).click();
  await expect(page.getByText('왼쪽 목록에서 부품을 담으면 이곳에 내 견적이 쌓입니다.')).toBeVisible();
});

test('updates quote dependency graph after self quote cart changes', async ({ page }) => {
  const graphRequests: unknown[] = [];
  const compatibleCandidateRequests: unknown[] = [];
  const candidateApplyRequests: unknown[] = [];
  const emptyDraft = {
    id: 'draft-graph-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [],
    totalPrice: 0,
    itemCount: 0
  };
  const gpuDraft = {
    id: 'draft-graph-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [
      {
        id: 'draft-item-graph-gpu',
        partId: 'part-gpu-graph',
        category: 'GPU',
        name: 'RTX 5070 그래프 테스트',
        manufacturer: 'NVIDIA',
        quantity: 1,
        unitPriceAtAdd: 890000,
        currentPrice: 890000,
        lineTotal: 890000,
        attributes: {}
      }
    ],
    totalPrice: 890000,
    itemCount: 1
  };
  const compatibleDraft = {
    id: 'draft-graph-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [
      {
        id: 'draft-item-compatible-gpu',
        partId: 'part-gpu-compatible',
        category: 'GPU',
        name: 'RTX 5070 Ti 그래프 호환 후보',
        manufacturer: 'NVIDIA',
        quantity: 1,
        unitPriceAtAdd: 990000,
        currentPrice: 990000,
        lineTotal: 990000,
        attributes: {}
      }
    ],
    totalPrice: 990000,
    itemCount: 1
  };
  let draft: unknown = emptyDraft;

  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/build-graphs/resolve', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}');
    graphRequests.push(body);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(buildGraphResponse(body.focus?.mode ?? 'ISSUE_PATH'))
    });
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const method = route.request().method();
    const url = new URL(route.request().url());
    if (method === 'PUT') {
      if (url.pathname.endsWith('/items/part-gpu-compatible')) {
        candidateApplyRequests.push(JSON.parse(route.request().postData() ?? '{}'));
        draft = compatibleDraft;
      } else {
        draft = gpuDraft;
      }
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(draft)
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/parts/compatible-candidates') {
      compatibleCandidateRequests.push(JSON.parse(route.request().postData() ?? '{}'));
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          category: 'GPU',
          items: [
            {
              part: {
                id: 'part-gpu-compatible',
                category: 'GPU',
                name: 'RTX 5070 Ti 그래프 호환 후보',
                manufacturer: 'NVIDIA',
                price: 990000,
                status: 'ACTIVE',
                attributes: {
                  wattage: 285,
                  lengthMm: 310,
                  imageUrl: 'https://example.test/graph-candidate-rtx5070ti.png'
                }
              },
              status: 'PASS',
              statusLabel: '여유 있음',
              summary: '현재 PSU/케이스 기준 장착 가능합니다.',
              checkedTools: ['power', 'size', 'performance']
            }
          ],
          rejectedCount: 1,
          warnings: []
        })
      });
      return;
    }
    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-gpu-graph',
          partName: 'RTX 5070 그래프 테스트',
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
        items: [
          {
            id: 'part-gpu-graph',
            category: 'GPU',
            name: 'RTX 5070 그래프 테스트',
            manufacturer: 'NVIDIA',
            price: 890000,
            status: 'ACTIVE',
            externalOffer: {
              imageUrl: 'https://example.test/graph-gpu.png',
              supplierName: '그래프테스트몰',
              offerUrl: 'https://example.test/graph-gpu',
              lowPrice: 890000,
              source: 'NAVER_SHOPPING_SEARCH'
            }
          }
        ],
        page: 0,
        size: 50,
        total: 1
      })
    });
  });

  await page.goto('/self-quote?category=GPU');

  await expect(page.getByTestId('build-dependency-graph')).toContainText('견적 관계도');
  await expect(page.getByTestId('build-dependency-graph')).toContainText('파워 여유 확인');
  const initialGraphCalls = graphRequests.length;

  await page.getByRole('button', { name: 'RTX 5070 그래프 테스트 견적 담기' }).click();

  await expect.poll(() => graphRequests.length).toBeGreaterThan(initialGraphCalls);
  await expect(page.getByRole('link', { name: 'RTX 5070 그래프 테스트', exact: true })).toBeVisible();
  await expect(page.getByTestId('build-dependency-graph')).toContainText('현재 GPU 선택은 PSU 용량과 케이스 장착 조건');
  await expect(page.getByTestId('build-dependency-graph')).not.toContainText('선택한 그래픽카드');
  expect((graphRequests[graphRequests.length - 1] as { source?: string }).source).toBe('QUOTE_DRAFT_CURRENT');

  await page.getByTestId('build-dependency-graph').getByText('RTX 5070', { exact: true }).click();
  const candidatePanel = page.getByTestId('graph-flow-canvas').getByTestId('graph-node-candidate-panel');
  await expect(candidatePanel).toContainText('선택한 부품 상세');
  await expect(candidatePanel).toContainText('선택한 그래픽카드');
  await expect(candidatePanel).toContainText('RTX 5070 Ti 그래프 호환 후보');
  await expect(candidatePanel.getByRole('button', { name: 'RTX 5070 Ti 그래프 호환 후보 사진 확대' })).toBeVisible();
  await expect.poll(() => compatibleCandidateRequests.length).toBe(1);
  expect((compatibleCandidateRequests[0] as { source?: string; category?: string }).source).toBe('QUOTE_DRAFT_CURRENT');
  expect((compatibleCandidateRequests[0] as { category?: string }).category).toBe('GPU');

  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  const floatingGraph = page.getByTestId('floating-dependency-graph');
  await expect(floatingGraph).toBeVisible();
  await floatingGraph.getByText('RTX 5070', { exact: true }).click();
  const floatingCandidatePanel = page.getByTestId('floating-graph-candidate-panel');
  await expect(floatingCandidatePanel).toContainText('RTX 5070 Ti 그래프 호환 후보');

  await floatingCandidatePanel.getByRole('button', { name: 'RTX 5070 Ti 그래프 호환 후보 담기/교체' }).click();
  await expect.poll(() => candidateApplyRequests.length).toBe(1);
});

test('opens checkout from self quote purchase CTA without using the build result route', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(checkoutDraft)
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-checkout-gpu',
          partName: 'RTX 5070 구매 테스트',
          currentPrice: 980000,
          days: 3650,
          source: 'NAVER_SHOPPING_SEARCH',
          items: [],
          summary: {
            sampleCount: 0,
            currentPrice: 980000,
            minPrice: 980000,
            maxPrice: 980000,
            firstPrice: 980000,
            lastPrice: 980000,
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
      body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 })
    });
  });

  await page.goto('/self-quote');

  const purchaseLink = page.getByRole('link', { name: '구매하기' });
  await expect(purchaseLink).toHaveAttribute('href', '/checkout');
  await purchaseLink.click();

  await expect(page).toHaveURL('/checkout');
  await expect(page).not.toHaveURL(/\/builds\/00000000-0000-4000-8000-000000002001/);
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

test('self quote chatbot sends current draft and applies a remove action after confirmation', async ({ page }) => {
  const buildChatBodies: unknown[] = [];
  let deleteRequests = 0;
  const gpuDraft = {
    id: 'draft-chat-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [
      {
        id: 'draft-item-gpu-chat',
        partId: 'part-gpu-chat',
        category: 'GPU',
        name: 'RTX 5070 챗봇 테스트',
        manufacturer: 'NVIDIA',
        quantity: 1,
        unitPriceAtAdd: 890000,
        currentPrice: 890000,
        lineTotal: 890000,
        attributes: {}
      }
    ],
    totalPrice: 890000,
    itemCount: 1
  };
  const emptyDraft = {
    id: 'draft-chat-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [],
    totalPrice: 0,
    itemCount: 0
  };
  let draft = gpuDraft;

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
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/items/part-gpu-chat') && route.request().method() === 'DELETE') {
      deleteRequests += 1;
      draft = emptyDraft;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(draft)
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-gpu-chat',
          partName: 'RTX 5070 챗봇 테스트',
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
        answerType: 'GENERAL',
        message: '현재 견적에서 GPU 제거 변경안을 만들었습니다.',
        builds: [],
        partRecommendation: null,
        actions: [
          {
            id: 'action-remove-gpu',
            type: 'REMOVE_DRAFT_PART',
            label: 'GPU 빼기',
            description: 'RTX 5070 챗봇 테스트를 견적에서 제거합니다.',
            payload: { partId: 'part-gpu-chat', category: 'GPU', source: 'AI_BUILD_CHAT' },
            requiresConfirmation: true
          }
        ],
        warnings: []
      })
    });
  });

  await page.goto('/self-quote');
  await expect(page.getByText('RTX 5070 챗봇 테스트')).toBeVisible();
  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('GPU 빼줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatBodies.length).toBe(1);
  expect((buildChatBodies[0] as { currentQuoteDraft?: { items?: Array<{ partId: string }> } }).currentQuoteDraft?.items?.[0]?.partId).toBe('part-gpu-chat');
  await expect(page.getByText('견적 장바구니 변경안')).toBeVisible();
  await page.getByRole('button', { name: '적용' }).click();

  await expect.poll(() => deleteRequests).toBe(1);
  await expect(page.getByText('왼쪽 목록에서 부품을 담으면 이곳에 내 견적이 쌓입니다.')).toBeVisible();
});

test('opens cooler internal assets from home category link', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

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
        size: 50,
        total: items.length
      })
    });
  });

  await page.goto('/');
  await page.getByRole('link', { name: '쿨러' }).click();

  await expect(page).toHaveURL('/self-quote?category=COOLER');
  await expect(page.getByText('쿨러 부품 목록')).toBeVisible();
  await expect(page.getByText('Liquid Freezer III 360 테스트')).toBeVisible();
  await expect(page.getByText('쿨러테스트몰')).toBeVisible();
  await expect(page.getByText('ACTIVE')).toHaveCount(0);
  await expect(page.getByText('77.7')).toHaveCount(0);
});

test('opens GPU internal assets from home category link', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

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
        size: 50,
        total: items.length
      })
    });
  });

  await page.goto('/');
  await page.getByRole('link', { name: 'GPU', exact: true }).click();

  await expect(page).toHaveURL('/self-quote?category=GPU');
  await expect(page.getByText('GPU 부품 목록')).toBeVisible();
  await expect(page.getByText('홈에서 열린 RTX 테스트')).toBeVisible();
  await expect(page.getByText('홈테스트몰')).toBeVisible();
});

test('shows selected AI build separately from the manual quote draft and marks duplicate parts', async ({ page }) => {
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
        items: [
          {
            id: 'draft-item-gpu-test',
            partId: 'part-gpu-test',
            category: 'GPU',
            name: 'RTX 4070 SUPER 테스트',
            manufacturer: 'NVIDIA',
            quantity: 1,
            unitPriceAtAdd: 890000,
            currentPrice: 890000,
            lineTotal: 890000,
            attributes: {}
          }
        ],
        totalPrice: 890000,
        itemCount: 1
      })
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-gpu-test',
          partName: 'RTX 4070 SUPER 테스트',
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
        items: [
          {
            id: 'part-gpu-test',
            category: 'GPU',
            name: 'RTX 4070 SUPER 테스트',
            manufacturer: 'NVIDIA',
            price: 890000,
            status: 'ACTIVE'
          }
        ],
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
  await expect(aiPanel.getByText('이미 담김', { exact: true })).toBeVisible();
  await expect(aiPanel.getByText('별도 표시')).toBeVisible();
  await expect(page.getByRole('heading', { name: '견적 장바구니', exact: true })).toBeVisible();
  await expect(page.getByText('견적 합계')).toBeVisible();
});

test('paginates self quote assets in 20 item pages', async ({ page }) => {
  const requestedPages: string[] = [];
  const requestedSizes: string[] = [];

  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    const pageParam = url.searchParams.get('page') ?? '0';
    const sizeParam = url.searchParams.get('size') ?? '';
    requestedPages.push(pageParam);
    requestedSizes.push(sizeParam);

    const currentPage = Number.parseInt(pageParam, 10);
    const start = currentPage * 20;
    const items = Array.from({ length: 20 }, (_, index) => {
      const itemNumber = start + index + 1;
      return {
        id: `part-psu-page-${itemNumber}`,
        category: 'PSU',
        name: `페이징 파워 ${itemNumber}`,
        manufacturer: '테스트파워',
        price: 50000 + itemNumber,
        status: 'ACTIVE',
        attributes: {
          shortSpec: `${itemNumber}번 파워`
        },
        externalOffer: {
          imageUrl: `https://example.test/psu-${itemNumber}.png`,
          supplierName: '페이징몰',
          offerUrl: `https://example.test/psu-${itemNumber}`,
          lowPrice: 50000 + itemNumber,
          source: 'NAVER_SHOPPING_SEARCH'
        }
      };
    });

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        page: currentPage,
        size: 20,
        total: 45
      })
    });
  });

  await page.goto('/self-quote?category=PSU');
  await expect(page.getByText('45개 중 1-20개 표시')).toBeVisible();
  await expect(page.getByText('페이지 1 / 3')).toBeVisible();
  await expect(page.getByText('페이징 파워 1', { exact: true })).toBeVisible();
  expect(requestedPages).toContain('0');
  expect(requestedSizes).toContain('20');

  await page.getByRole('button', { name: '다음' }).click();

  await expect(page).toHaveURL('/self-quote?category=PSU&page=1');
  await expect(page.getByText('45개 중 21-40개 표시')).toBeVisible();
  await expect(page.getByText('페이지 2 / 3')).toBeVisible();
  await expect(page.getByText('페이징 파워 21', { exact: true })).toBeVisible();
  expect(requestedPages).toContain('1');
});

test('keeps self quote shopping workspace usable on mobile width', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-mobile-test',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [
          {
            id: 'draft-item-mobile-test',
            partId: 'part-mobile-gpu-test',
            category: 'GPU',
            name: '모바일 RTX 테스트',
            manufacturer: 'NVIDIA',
            quantity: 1,
            unitPriceAtAdd: 890000,
            currentPrice: 890000,
            lineTotal: 890000,
            attributes: {}
          }
        ],
        totalPrice: 890000,
        itemCount: 1
      })
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-mobile-gpu-test',
          partName: '모바일 RTX 테스트',
          currentPrice: 890000,
          days: 3650,
          source: 'NAVER_SHOPPING_SEARCH',
          items: [{ price: 890000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-29T00:00:00Z' }],
          summary: {
            sampleCount: 1,
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
        items: [
          {
            id: 'part-mobile-gpu-test',
            category: 'GPU',
            name: '모바일 RTX 테스트',
            manufacturer: 'NVIDIA',
            price: 890000,
            status: 'ACTIVE',
            attributes: { shortSpec: 'QHD gaming mobile test' },
            externalOffer: {
              imageUrl: 'https://example.test/mobile-gpu.png',
              supplierName: '모바일테스트몰',
              offerUrl: 'https://example.test/mobile-gpu',
              lowPrice: 890000,
              source: 'NAVER_SHOPPING_SEARCH'
            }
          }
        ],
        page: 0,
        size: 20,
        total: 1
      })
    });
  });

  await page.goto('/self-quote?category=GPU');

  await expect(page.getByText('GPU 부품 목록')).toBeVisible();
  await expect(page.getByRole('link', { name: '모바일 RTX 테스트', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '견적 장바구니', exact: true })).toBeVisible();
  await expect(page.getByTestId('graph-flow-canvas').locator('.react-flow__minimap')).toHaveCount(0);
  await expect(page.getByTestId('graph-flow-canvas').locator('.react-flow__controls')).toHaveCount(0);

  const hasBodyOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  expect(hasBodyOverflow).toBe(false);
});

test('renders quote dependency graph with circular nodes in the reference layout', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-layout-test',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [],
        totalPrice: 0,
        itemCount: 0
      })
    });
  });

  await page.route('**/api/parts**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 })
    });
  });

  await page.goto('/self-quote');

  const graphCanvas = page.getByTestId('graph-flow-canvas');
  await expect(graphCanvas.getByText('소켓 일치')).toBeVisible();

  const nodeBox = async (label: string, categoryLabel: string) => {
    const node = graphCanvas.locator('.react-flow__node').filter({ hasText: label }).first();
    await expect(node).toHaveClass(/buildgraph-flow-node/);
    await expect(node).toHaveCSS('border-radius', '50%');
    await expect(node.locator('.buildgraph-node-category-label')).toHaveText(categoryLabel);
    await expect(node.locator('.buildgraph-node-main-label')).toContainText(label);
    await expect(node.locator('.buildgraph-node-status-label')).toHaveText(/호환됨|간섭 주의|장착 불가/);
    const box = await node.boundingBox();
    expect(box).not.toBeNull();
    expect(Math.abs((box?.width ?? 0) - (box?.height ?? 0))).toBeLessThanOrEqual(2);
    return box!;
  };
  const center = (box: { x: number; y: number; width: number; height: number }) => ({
    x: box.x + box.width / 2,
    y: box.y + box.height / 2
  });

  const cpu = center(await nodeBox('CPU', 'CPU'));
  const motherboard = center(await nodeBox('메인보드', '메인보드'));
  const ram = center(await nodeBox('RAM', 'RAM'));
  const gpu = center(await nodeBox('RTX 5070', 'GPU'));
  const psu = center(await nodeBox('750W 파워', '파워'));
  const pcCase = center(await nodeBox('Airflow Case', '케이스'));
  const cooler = center(await nodeBox('쿨러', '쿨러'));
  const storage = center(await nodeBox('SSD', 'SSD'));
  const price = center(await nodeBox('총액', '총액'));

  expect(cpu.x).toBeLessThan(motherboard.x);
  expect(motherboard.x).toBeLessThan(ram.x);
  expect(motherboard.y).toBeLessThan(gpu.y);
  expect(gpu.x).toBeLessThan(psu.x);
  expect(psu.y).toBeLessThan(pcCase.y);
  expect(cooler.x).toBeLessThan(pcCase.x);
  expect(cooler.y).toBeGreaterThan(gpu.y);
  expect(storage.x).toBeLessThan(price.x);
  expect(price.x).toBeLessThan(pcCase.x);
  expect(price.y).toBeGreaterThan(cooler.y);
});

test('updates quantity only for repeatable quote draft categories', async ({ page }) => {
  let ramQuantity = 1;
  const ramItem = {
    id: 'draft-item-ram-test',
    partId: 'part-ram-quantity-test',
    category: 'RAM',
    name: '삼성 DDR5 32GB 테스트',
    manufacturer: 'Samsung',
    quantity: ramQuantity,
    unitPriceAtAdd: 120000,
    currentPrice: 120000,
    lineTotal: 120000,
    attributes: {}
  };
  const cpuItem = {
    id: 'draft-item-cpu-test',
    partId: 'part-cpu-quantity-test',
    category: 'CPU',
    name: 'Ryzen 9 수량고정 테스트',
    manufacturer: 'AMD',
    quantity: 1,
    unitPriceAtAdd: 820000,
    currentPrice: 820000,
    lineTotal: 820000,
    attributes: {}
  };

  const draft = () => ({
    id: 'draft-quantity-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [
      { ...ramItem, quantity: ramQuantity, lineTotal: ramQuantity * ramItem.currentPrice },
      cpuItem
    ],
    totalPrice: ramQuantity * ramItem.currentPrice + cpuItem.currentPrice,
    itemCount: ramQuantity + 1
  });

  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    if (route.request().method() === 'PATCH') {
      const body = JSON.parse(route.request().postData() ?? '{}') as { quantity?: number };
      ramQuantity = body.quantity ?? ramQuantity;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(draft())
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-ram-quantity-test',
          partName: '삼성 DDR5 32GB 테스트',
          currentPrice: 120000,
          days: 3650,
          source: 'NAVER_SHOPPING_SEARCH',
          items: [{ price: 120000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-29T00:00:00Z' }],
          summary: {
            sampleCount: 1,
            currentPrice: 120000,
            minPrice: 120000,
            maxPrice: 120000,
            firstPrice: 120000,
            lastPrice: 120000,
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
      body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 })
    });
  });

  await page.goto('/self-quote');

  await expect(page.getByText('삼성 DDR5 32GB 테스트')).toBeVisible();
  await expect(page.getByLabel('삼성 DDR5 32GB 테스트 수량 선택')).toBeVisible();
  await expect(page.getByLabel('Ryzen 9 수량고정 테스트 수량 선택')).toHaveCount(0);
  await expect(page.getByText('수량 1개')).toHaveCount(2);

  await page.getByRole('button', { name: '삼성 DDR5 32GB 테스트 수량 증가' }).click();

  await expect(page.getByText('수량 2개')).toBeVisible();
  await expect(page.getByText('1,060,000원')).toBeVisible();
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
            { price: 1020000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-27T00:00:00Z' },
            { price: 990000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-28T00:00:00Z' },
            { price: 950000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-29T00:00:00Z' }
          ],
          summary: {
            sampleCount: 3,
            currentPrice: 950000,
            minPrice: 950000,
            maxPrice: 1020000,
            firstPrice: 1020000,
            lastPrice: 950000,
            changeAmount: -70000,
            changeRatePercent: -6.86
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
  await expect(page.getByText('-70,000원 (-6.86%)')).toBeVisible();
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
