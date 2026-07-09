import { expect, test, type Page } from '@playwright/test';

type BuildItem = {
  category: string;
  partId: string;
  name: string;
  manufacturer: string;
  price: number;
};

const savedBuilds = [
  {
    id: 'build-qhd-balanced',
    name: 'QHD 균형 저장 견적',
    recommendedFor: 'QHD 게임/작업',
    summary: '게임과 개발 작업을 함께 고려한 저장 견적입니다.',
    totalPrice: 2_180_000,
    confidence: 'HIGH',
    createdAt: '2026-07-03T10:20:00Z',
    warnings: [],
    evidenceIds: [],
    items: [
      item('CPU', 'part-cpu-9700x', 'AMD Ryzen 7 9700X', 'AMD', 430_000),
      item('GPU', 'part-gpu-5070', 'GeForce RTX 5070', 'NVIDIA', 960_000),
      item('MOTHERBOARD', 'part-board-b650', 'B650 WiFi 메인보드', 'ASUS', 260_000)
    ]
  },
  {
    id: 'build-workstation',
    name: '작업용 저장 견적',
    recommendedFor: '영상 편집',
    summary: 'GPU와 메모리를 높인 작업용 견적입니다.',
    totalPrice: 3_140_000,
    confidence: 'MEDIUM',
    createdAt: '2026-07-02T08:10:00Z',
    warnings: [{ code: 'PRICE', message: '예산 상단에 가까운 조합입니다.' }],
    evidenceIds: [],
    items: [
      item('GPU', 'part-gpu-5080', 'GeForce RTX 5080', 'MSI', 1_540_000),
      item('RAM', 'part-ram-64', 'DDR5 64GB Kit', 'Samsung', 320_000)
    ]
  }
];

const priceAlerts = [
  {
    partId: 'part-gpu-5070',
    partName: 'GeForce RTX 5070',
    targetPrice: 900_000,
    currentPrice: 960_000,
    status: 'ACTIVE',
    createdAt: '2026-07-03T11:00:00Z'
  },
  {
    partId: 'part-ssd-990pro',
    partName: 'Samsung 990 PRO 1TB',
    targetPrice: 190_000,
    currentPrice: 180_000,
    status: 'TRIGGERED',
    createdAt: '2026-07-01T09:00:00Z'
  }
];

function item(category: string, partId: string, name: string, manufacturer: string, price: number): BuildItem {
  return {
    category,
    partId,
    name,
    manufacturer,
    price
  };
}

function compositeScoreFixture(score: number, label: string) {
  return {
    policyVersion: 'build-composite-score-v1',
    score,
    rawScore: score,
    maxScore: 1000,
    grade: score >= 850 ? 'A' : 'B',
    label,
    summary: `${label} 저장 견적입니다.`,
    components: [
      { key: 'performance', label: '성능', score: Math.round(score * 0.43), maxScore: 430, percent: 80, summary: '성능 참고' },
      { key: 'compatibility', label: '호환·장착 안전성', score: 220, maxScore: 220, percent: 100, summary: '호환 통과' },
      { key: 'balance', label: '병목·여유 균형', score: 130, maxScore: 160, percent: 81, summary: '균형 참고' }
    ],
    caps: [],
    requestFit: {
      status: 'PASS',
      score: 100,
      budgetWon: 0,
      totalPrice: 0,
      priceDiff: 0,
      summary: '요청 예산 적합'
    },
    curve: [],
    missingCategories: []
  };
}

async function openMyQuotesAsUser(page: Page) {
  const priceAlertRequests: unknown[] = [];
  const applyBuildRequests: unknown[] = [];
  const graphRequests: unknown[] = [];
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-1004',
      email: 'user@example.com',
      name: '테스트 사용자',
      role: 'USER'
    }));
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
  await page.route('**/api/builds/history', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: savedBuilds, page: 0, size: 20, total: savedBuilds.length })
    });
  });
  await page.route('**/api/quote-drafts/current/apply-ai-build', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}');
    applyBuildRequests.push(body);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-from-saved-build',
        status: 'ACTIVE',
        name: '저장 견적 구매 준비',
        items: (body.items ?? []).map((next: { partId: string; category: string; quantity: number }, index: number) => {
          const source = savedBuilds.flatMap((build) => build.items).find((item) => item.partId === next.partId);
          return {
            id: `draft-item-${index}`,
            partId: next.partId,
            category: next.category,
            name: source?.name ?? next.partId,
            manufacturer: source?.manufacturer ?? 'BuildGraph',
            quantity: next.quantity,
            unitPriceAtAdd: source?.price ?? 100_000,
            currentPrice: source?.price ?? 100_000,
            lineTotal: (source?.price ?? 100_000) * next.quantity,
            attributes: {},
            externalOffer: {
              supplierName: '테스트 구매처',
              offerUrl: `https://example.test/${next.partId}`,
              lowPrice: source?.price ?? 100_000,
              source: 'TEST',
              refreshedAt: '2026-07-04T00:00:00Z'
            }
          };
        }),
        totalPrice: (body.items ?? []).reduce((sum: number, next: { partId: string; quantity: number }) => {
          const source = savedBuilds.flatMap((build) => build.items).find((item) => item.partId === next.partId);
          return sum + (source?.price ?? 100_000) * next.quantity;
        }, 0),
        itemCount: (body.items ?? []).length
      })
    });
  });
  await page.route('**/api/price-alerts', async (route) => {
    if (route.request().method() === 'POST') {
      const body = JSON.parse(route.request().postData() ?? '{}');
      priceAlertRequests.push(body);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: body.partId,
          partName: '새 목표가 부품',
          targetPrice: body.targetPrice,
          currentPrice: 960_000,
          status: 'ACTIVE',
          createdAt: '2026-07-04T00:00:00Z'
        })
      });
      return;
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: priceAlerts, page: 0, size: 20, total: priceAlerts.length })
    });
  });
  // 이전 CPU/GPU 개별 점수 endpoint는 더 이상 비교 대표점수에 쓰지 않는다.
  await page.route('**/api/tools/performance/check', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        tool: 'performance', status: 'PASS', confidence: 'HIGH', summary: '',
        details: { cpuBenchmarkScore: 72, gpuBenchmarkScore: 78, benchmarkSource: 'benchmark_summaries', gameFpsEvidence: [] }
      })
    });
  });
  await page.route('**/api/build-graphs/resolve', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}');
    graphRequests.push(body);
    const partIds = (body.items ?? []).map((item: { partId?: string }) => item.partId).join('|');
    const compositeScore = partIds.includes('part-gpu-5080')
      ? compositeScoreFixture(924, '고성능')
      : compositeScoreFixture(854, '고성능');
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        mode: 'BUILD_OVERVIEW',
        summary: '저장 견적 부품 관계를 읽기 전용으로 표시합니다.',
        nodes: [
          { id: 'part-CPU', type: 'PART', category: 'CPU', label: 'AMD Ryzen 7 9700X', status: 'PASS', detail: '저장 견적 CPU' },
          { id: 'part-GPU', type: 'PART', category: 'GPU', label: 'GeForce RTX 5070', status: 'PASS', detail: '저장 견적 GPU' },
          { id: 'constraint-total-price', type: 'CONSTRAINT', category: 'PRICE', label: '총액', status: 'PASS', detail: '2,180,000원' }
        ],
        edges: [
          {
            id: 'edge-cpu-gpu-performance',
            source: 'part-CPU',
            target: 'part-GPU',
            type: 'AFFECTS',
            status: 'PASS',
            label: '성능 균형',
            summary: 'CPU와 GPU 조합의 성능 균형을 확인합니다.'
          }
        ],
        focusNodeIds: ['part-CPU', 'part-GPU'],
        insights: [],
        compositeScore,
        toolResults: [
          {
            tool: 'performance',
            status: 'PASS',
            confidence: 'HIGH',
            summary: '저장 견적 성능 균형을 확인했습니다.',
            details: { cpu: 'AMD Ryzen 7 9700X', gpu: 'GeForce RTX 5070', cpuBenchmarkScore: 72, gpuBenchmarkScore: 78, benchmarkSource: 'benchmark_summaries' }
          }
        ]
      })
    });
  });

  await page.goto('/my/quotes');
  return { priceAlertRequests, applyBuildRequests, graphRequests };
}

test('shows saved quotes, actionable price alert setup, and alert progress', async ({ page }) => {
  const { priceAlertRequests } = await openMyQuotesAsUser(page);

  await expect(page.getByRole('heading', { name: '내 견적함 / 목표가 알림' })).toBeVisible();
  await expect(page.getByTestId('my-quotes-build-count')).toContainText('2개');
  await expect(page.getByTestId('my-quotes-alert-count')).toContainText('2개');
  await expect(page.getByTestId('my-quotes-achieved-count')).toContainText('1개');

  const firstBuild = page.getByTestId('saved-build-card-build-qhd-balanced');
  await expect(firstBuild).toContainText('QHD 균형 저장 견적');
  await expect(firstBuild.getByRole('link', { name: '견적 상세' })).toHaveAttribute('href', '/builds/build-qhd-balanced');
  await expect(firstBuild.getByRole('button', { name: '부품 변경' })).toBeVisible();
  // 저장 견적 비교 — 비교할 견적을 고르면 전 카테고리 부품 + 성능을 좌우로 나열.
  const perfMatrix = page.getByTestId('saved-builds-comparison');
  await expect(perfMatrix).toContainText('견적 골라 부품·성능 비교');
  // 기본으로 앞 2개 견적이 선택되어 열로 노출된다.
  await expect(perfMatrix).toContainText('QHD 균형 저장 견적');
  await expect(perfMatrix).toContainText('작업용 저장 견적');
  // 구성 부품 섹션 — 카테고리별 실제 부품이 나열된다.
  await expect(perfMatrix).toContainText('구성 부품');
  await expect(perfMatrix).toContainText('메인보드');
  await expect(perfMatrix).toContainText('AMD Ryzen 7 9700X');
  await expect(perfMatrix).toContainText('GeForce RTX 5070');
  await expect(perfMatrix).toContainText('GeForce RTX 5080');
  // 성능 참고 섹션 — CPU/GPU 평균이 아니라 완성 견적 1000점 종합 점수.
  await expect(perfMatrix).toContainText('종합');
  await expect(perfMatrix).toContainText('1000점');
  await expect(perfMatrix.getByTestId('quote-compare-composite-gauge')).toHaveCount(2);
  await expect(perfMatrix).toContainText('854');
  await expect(perfMatrix).toContainText('924');
  // 견적 선택 해제 시 해당 열(부품)이 사라진다.
  await perfMatrix.getByTestId('compare-toggle-build-workstation').click();
  await expect(perfMatrix).not.toContainText('GeForce RTX 5080');
  await expect(perfMatrix).toContainText('GeForce RTX 5070');
  await firstBuild.getByRole('button', { name: '목표가 등록' }).click();

  await expect(page.getByLabel('저장 견적 부품')).toHaveValue('part-cpu-9700x');
  await expect(page.getByLabel('저장 견적 부품').locator('option')).toHaveCount(savedBuilds[0].items.length);
  await expect(page.getByLabel('저장 견적 부품')).toContainText('AMD Ryzen 7 9700X');
  await expect(page.getByLabel('저장 견적 부품')).toContainText('GeForce RTX 5070');
  await expect(page.getByLabel('저장 견적 부품')).not.toContainText('GeForce RTX 5080');
  await page.getByLabel('목표가').fill('880000');
  await page.getByRole('button', { name: '알림 등록' }).click();

  await expect.poll(() => priceAlertRequests.length).toBe(1);
  expect(priceAlertRequests[0]).toEqual({ partId: 'part-cpu-9700x', targetPrice: 880_000 });
  await expect(page.getByText('알림 등록 완료')).toBeVisible();

  const activeAlert = page.getByTestId('price-alert-row-part-gpu-5070');
  await expect(activeAlert).toContainText('GeForce RTX 5070');
  await expect(activeAlert).toContainText('목표까지 60,000원');

  const triggeredAlert = page.getByTestId('price-alert-row-part-ssd-990pro');
  await expect(triggeredAlert).toContainText('Samsung 990 PRO 1TB');
  await expect(triggeredAlert).toContainText('목표 달성');
});

test('limits target price dropdown to the selected quote and opens checkout for that quote', async ({ page }) => {
  const { applyBuildRequests } = await openMyQuotesAsUser(page);

  const secondBuild = page.getByTestId('saved-build-card-build-workstation');
  await secondBuild.getByRole('button', { name: '목표가 등록' }).click();

  const savedPartSelect = page.getByLabel('저장 견적 부품');
  await expect(savedPartSelect).toHaveValue('part-gpu-5080');
  await expect(savedPartSelect.locator('option')).toHaveCount(savedBuilds[1].items.length);
  await expect(savedPartSelect).toContainText('GeForce RTX 5080');
  await expect(savedPartSelect).toContainText('DDR5 64GB Kit');
  await expect(savedPartSelect).not.toContainText('AMD Ryzen 7 9700X');

  await secondBuild.getByRole('button', { name: '구매하기' }).click();

  await expect.poll(() => applyBuildRequests.length).toBe(1);
  expect(applyBuildRequests[0]).toEqual({
    buildId: 'build-workstation',
    conflictPolicy: 'REPLACE',
    items: [
      { partId: 'part-gpu-5080', category: 'GPU', quantity: 1 },
      { partId: 'part-ram-64', category: 'RAM', quantity: 1 }
    ]
  });
  await expect(page).toHaveURL('/checkout');
});

test('applies the selected saved quote before opening self quote for part changes', async ({ page }) => {
  const { applyBuildRequests } = await openMyQuotesAsUser(page);

  const firstBuild = page.getByTestId('saved-build-card-build-qhd-balanced');
  await firstBuild.getByRole('button', { name: '부품 변경' }).click();

  await expect.poll(() => applyBuildRequests.length).toBe(1);
  expect(applyBuildRequests[0]).toEqual({
    buildId: 'build-qhd-balanced',
    conflictPolicy: 'REPLACE',
    items: [
      { partId: 'part-cpu-9700x', category: 'CPU', quantity: 1 },
      { partId: 'part-gpu-5070', category: 'GPU', quantity: 1 },
      { partId: 'part-board-b650', category: 'MOTHERBOARD', quantity: 1 }
    ]
  });
  await expect(page).toHaveURL('/self-quote');
});

test('does not expose manual part id entry for target price alerts', async ({ page }) => {
  await openMyQuotesAsUser(page);

  await expect(page.getByRole('button', { name: '직접 입력' })).toHaveCount(0);
  await expect(page.getByLabel('부품 ID 직접 입력')).toHaveCount(0);
  await expect(page.getByLabel('저장 견적 부품')).toBeVisible();
});

test('opens a read-only dependency graph popup for each saved quote', async ({ page }) => {
  const { graphRequests } = await openMyQuotesAsUser(page);

  const firstBuild = page.getByTestId('saved-build-card-build-qhd-balanced');
  const secondBuild = page.getByTestId('saved-build-card-build-workstation');
  await expect(firstBuild).not.toContainText('근거 높음');
  await expect(secondBuild).not.toContainText('주의 1건');

  await firstBuild.getByRole('button', { name: '견적 관계 그래프 보기' }).click();

  await expect.poll(() => graphRequests.length).toBeGreaterThanOrEqual(1);
  const firstBuildGraphRequest = graphRequests.find((request) => JSON.stringify(request).includes('part-cpu-9700x'));
  expect(firstBuildGraphRequest).toMatchObject({
    source: 'AI_BUILD',
    budgetWon: 2_180_000,
    items: [
      { partId: 'part-cpu-9700x', category: 'CPU', quantity: 1 },
      { partId: 'part-gpu-5070', category: 'GPU', quantity: 1 },
      { partId: 'part-board-b650', category: 'MOTHERBOARD', quantity: 1 }
    ]
  });

  const dialog = page.getByRole('dialog', { name: '저장 견적 관계 그래프' });
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('QHD 균형 저장 견적');
  await expect(dialog).toContainText('읽기 전용');
  await expect(dialog.getByTestId('graph-flow-canvas')).toBeVisible();
  await expect(dialog.getByTestId('build-composite-score-gauge')).toBeVisible();
  await dialog.getByRole('button', { name: '관계 그래프 닫기' }).click();
  await expect(dialog).toHaveCount(0);
});

test('renames, duplicates, and soft-deletes a saved quote', async ({ page }) => {
  const patchRequests: Array<{ id: string; name: string }> = [];
  const duplicateRequests: string[] = [];
  const deleteRequests: string[] = [];
  let builds = savedBuilds.map((build) => ({ ...build }));

  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({ id: 'user-1004', email: 'user@example.com', name: '테스트 사용자', role: 'USER' }));
  });
  await page.route('**/api/auth/me', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 'user-1004', email: 'user@example.com', name: '테스트 사용자', role: 'USER' }) }));
  await page.route('**/api/price-alerts**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) }));
  await page.route('**/api/tools/performance/check', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ tool: 'performance', status: 'PASS', confidence: 'HIGH', summary: '', details: { cpuBenchmarkScore: 70, gpuBenchmarkScore: 75, gameFpsEvidence: [] } }) }));
  await page.route('**/api/builds/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

    if (path.endsWith('/history')) {
      return json({ items: builds });
    }
    const dupMatch = path.match(/\/api\/builds\/([^/]+)\/duplicate$/);
    if (method === 'POST' && dupMatch) {
      const source = builds.find((build) => build.id === dupMatch[1]) ?? builds[0];
      const copy = { ...source, id: `${source.id}-copy`, name: `${source.name} (사본)` };
      builds = [copy, ...builds];
      duplicateRequests.push(dupMatch[1]);
      return json(copy);
    }
    const idMatch = path.match(/\/api\/builds\/([^/]+)$/);
    if (method === 'PATCH' && idMatch) {
      const body = JSON.parse(request.postData() ?? '{}') as { name: string };
      builds = builds.map((build) => (build.id === idMatch[1] ? { ...build, name: body.name } : build));
      patchRequests.push({ id: idMatch[1], name: body.name });
      return json(builds.find((build) => build.id === idMatch[1]));
    }
    if (method === 'DELETE' && idMatch) {
      builds = builds.filter((build) => build.id !== idMatch[1]);
      deleteRequests.push(idMatch[1]);
      return json({ id: idMatch[1], deleted: true });
    }
    return json({});
  });

  await page.goto('/my/quotes');
  const firstCard = page.getByTestId('saved-build-card-build-qhd-balanced');
  await expect(firstCard).toBeVisible();

  // 이름 변경(인라인 편집)
  await firstCard.getByTestId('rename-build-qhd-balanced').click();
  await firstCard.getByTestId('rename-input-build-qhd-balanced').fill('내 메인 견적');
  await firstCard.getByTestId('rename-save-build-qhd-balanced').click();
  await expect.poll(() => patchRequests.some((request) => request.name === '내 메인 견적')).toBe(true);
  await expect(firstCard.getByRole('heading', { name: '내 메인 견적' })).toBeVisible();

  // 복제 → 사본 카드가 생긴다
  await firstCard.getByTestId('duplicate-build-qhd-balanced').click();
  await expect.poll(() => duplicateRequests.length).toBe(1);
  await expect(page.getByTestId('saved-build-card-build-qhd-balanced-copy')).toBeVisible();

  // 삭제(인라인 확인) → 카드가 사라진다
  const workstationCard = page.getByTestId('saved-build-card-build-workstation');
  await workstationCard.getByTestId('delete-build-workstation').click();
  await workstationCard.getByTestId('confirm-delete-build-workstation').click();
  await expect.poll(() => deleteRequests).toContain('build-workstation');
  await expect(page.getByTestId('saved-build-card-build-workstation')).toHaveCount(0);
});
