import { expect, test, type Page } from '@playwright/test';

const adminRoutes = [
  '/admin',
  '/admin/agent-sessions',
  '/admin/agent-sessions/demo-session',
  '/admin/tool-invocations',
  '/admin/tool-invocations/tool-power-001',
  '/admin/rag-evidence',
  '/admin/rag-evidence/rag-psu-001',
  '/admin/parts',
  '/admin/price-jobs',
  '/admin/build-graph-layouts',
  '/admin/load-tests',
  '/admin/support-chat-sessions',
  '/admin/as-tickets',
  '/admin/as-tickets/AS-1031'
];

async function mockRecommendationModelSummary(page: Page) {
  await page.route('**/api/admin/recommendation-models/summary', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        latestModel: {
          modelName: 'xgboost-reranker',
          modelVersion: 'xgb-20260703100000',
          status: 'SHADOW'
        },
        homeParts: {
          windowDays: 7,
          impressions: 10,
          clicks: 2,
          ctr: 0.2,
          recentShadowScores: 40,
          scoreSources: [
            { scoreSource: 'XGBOOST', count: 10, share: 1.0 }
          ],
          recentCandidates: [
            {
              partId: '00000000-0000-4000-8000-000000010001',
              category: 'GPU',
              name: 'RTX 5090 추천 후보',
              manufacturer: 'NVIDIA',
              price: 4000000,
              score: 9.5,
              rankPosition: 0,
              modelVersion: 'xgb-20260703100000',
              createdAt: '2026-07-03T10:00:00Z'
            }
          ]
        },
        generatedAt: '2026-07-03T10:00:00Z'
      })
    });
  });
  await page.route('**/api/admin/recommendation-training/overview', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        eligibleEvents: 24,
        trainedDistinctEvents: 8,
        untrainedEligibleEvents: 16,
        excludedDatasetItems: 2,
        recentSevenDayEvents: 12,
        activeModel: null,
        latestJob: { id: 'job-001', datasetId: 'dataset-001', status: 'SKIPPED_LOW_DATASET' },
        generatedAt: '2026-07-03T10:00:00Z'
      })
    });
  });
  await page.route('**/api/admin/recommendation-training-datasets', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 'dataset-002',
          name: '새 데이터셋',
          status: 'DRAFT',
          eligibleCount: 24,
          includedCount: 24,
          excludedCount: 0
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
            id: 'dataset-001',
            name: '홈 추천부품 학습 데이터셋',
            sourceSurface: 'HOME_PARTS',
            status: 'DRAFT',
            eligibleCount: 24,
            includedCount: 22,
            excludedCount: 2,
            createdAt: '2026-07-03T10:00:00Z'
          }
        ],
        page: 0,
        size: 50,
        total: 1
      })
    });
  });
  await page.route('**/api/admin/recommendation-training-datasets/*', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'dataset-001',
        name: '홈 추천부품 학습 데이터셋',
        sourceSurface: 'HOME_PARTS',
        status: 'DRAFT',
        eligibleCount: 24,
        includedCount: 22,
        excludedCount: 2
      })
    });
  });
  await page.route('**/api/admin/recommendation-training-jobs', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ id: 'job-002', datasetId: 'dataset-001', status: 'QUEUED' })
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: 'job-001',
            datasetId: 'dataset-001',
            datasetName: '홈 추천부품 학습 데이터셋',
            status: 'SKIPPED_LOW_DATASET',
            metrics: { rowCount: 12 },
            logSummary: '학습 데이터 부족',
            createdAt: '2026-07-03T10:00:00Z'
          }
        ],
        page: 0,
        size: 50,
        total: 1
      })
    });
  });
  await page.route('**/api/admin/recommendation-models', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: 'model-001',
            modelName: 'xgboost-reranker',
            modelVersion: 'xgb-20260703100000',
            status: 'SHADOW',
            artifactPath: '/models/xgb-20260703100000.json',
            metrics: { mae: 0.42 },
            createdAt: '2026-07-03T10:00:00Z'
          }
        ],
        page: 0,
        size: 50,
        total: 1
      })
    });
  });
}

test('shows permission screen without calling auth/me when token is missing', async ({ page }) => {
  let authMeCalls = 0;
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.getByText('관리자 화면을 보려면 먼저 로그인해야 합니다.')).toBeVisible();
  await page.waitForTimeout(100);
  expect(authMeCalls).toBe(0);
});

for (const route of adminRoutes) {
  test(`guards ${route} when token is missing`, async ({ page }) => {
    await page.goto(route);

    await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
    await expect(page.getByText('관리자 화면을 보려면 먼저 로그인해야 합니다.')).toBeVisible();
    await expect(page.getByRole('link', { name: '로그인으로 이동' })).toBeVisible();
    await expect(page.getByRole('link', { name: '홈으로 이동' })).toBeVisible();
  });
}

test('does not expose protected admin page content without admin permission', async ({ page }) => {
  await page.goto('/admin/parts');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.locator('main')).not.toContainText('부품 DB');
  await expect(page.locator('main')).not.toContainText('가격 Job 상태');
});

test('shows permission screen when auth/me returns USER role', async ({ page }) => {
  let authMeCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'user-1004', email: 'user@example.com', role: 'USER' })
    });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.getByText('현재 로그인한 계정에는 관리자 권한이 없습니다.')).toBeVisible();
  expect(authMeCalls).toBeGreaterThan(0);
});

test('shows login-needed message when auth/me returns 401', async ({ page }) => {
  let authMeCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'invalid-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'UNAUTHORIZED', message: '로그인이 필요합니다.' })
    });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.getByText('관리자 화면을 보려면 먼저 로그인해야 합니다.')).toBeVisible();
  expect(authMeCalls).toBeGreaterThan(0);
});

test('shows permission message when auth/me returns 403', async ({ page }) => {
  let authMeCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({
      status: 403,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'FORBIDDEN', message: '관리자 권한이 필요합니다.' })
    });
  });

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
  await expect(page.getByText('현재 로그인한 계정에는 관리자 권한이 없습니다.')).toBeVisible();
  expect(authMeCalls).toBeGreaterThan(0);
});

test('renders admin page when auth/me returns ADMIN role', async ({ page }) => {
  let authMeCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    authMeCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/agent-sessions/demo-session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'demo-session',
        status: 'SUCCEEDED',
        summary: 'Agent trace completed.',
        purpose: 'BUILD_RECOMMEND',
        stateTimeline: [
          { from: null, to: 'QUEUED', actor: 'USER', at: '2026-06-29T10:00:00Z', reason: 'created' },
          { from: 'QUEUED', to: 'RUNNING', actor: 'SYSTEM', at: '2026-06-29T10:00:01Z', reason: 'started' }
        ],
        toolInvocations: [
          {
            id: 'tool-001',
            agentSessionId: 'demo-session',
            toolName: 'compatibility',
            status: 'PASS',
            confidence: 'HIGH',
            summary: 'Compatibility check passed.',
            latencyMs: 40
          }
        ],
        evidenceIds: ['rag-001']
      })
    });
  });
  await page.route('**/api/admin/rag-evidence/rag-001', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'rag-001',
        agentSessionId: 'demo-session',
        sourceId: 'internal-rule-demo',
        summary: 'Demo RAG evidence.',
        score: 0.91,
        metadata: { sourceType: 'INTERNAL_RULE' }
      })
    });
  });

  await page.goto('/admin/agent-sessions/demo-session');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeHidden();
  await expect(page.locator('body')).toContainText('에이전트 / 검색 근거 / 도구 근거 상세');
  await expect(page.getByRole('main')).toContainText('에이전트 실행 이력');
  await expect(page.getByRole('main')).toContainText('도구 호출 이력');
  await expect(page.getByRole('main')).toContainText('근거 수준');
  await expect(page.getByRole('main')).toContainText('호환성 확인');
  await expect(page.getByRole('main')).toContainText('검색 근거');
  await expect(page.getByRole('main')).toContainText('통과');
  await expect(page.getByRole('main')).toContainText('Compatibility check passed.');
  await expect(page.getByText('에이전트 / 검색 근거 / 도구 근거 상세').first()).toHaveCSS('font-family', /Noto Sans KR/);
  expect(authMeCalls).toBeGreaterThan(0);
});

test('renders admin Agent, Tool, and RAG list pages with detail links', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/agent-sessions', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: 'demo-session',
            status: 'SUCCEEDED',
            userId: 'user-001',
            createdAt: '2026-06-29T10:35:00Z'
          }
        ],
        page: 0,
        size: 20,
        total: 1
      })
    });
  });
  await page.route('**/api/admin/tool-invocations', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: 'tool-power-001',
            agentSessionId: 'demo-session',
            toolName: 'power',
            status: 'PASS',
            confidence: 'HIGH',
            summary: 'Power check passed.',
            latencyMs: 42,
            createdAt: '2026-06-29T10:36:10Z'
          },
          {
            id: 'tool-perf-001',
            agentSessionId: 'demo-session',
            toolName: 'performance',
            status: 'PASS',
            confidence: 'MEDIUM',
            summary: 'QHD gaming and development fit.',
            latencyMs: 73,
            createdAt: '2026-06-29T10:36:12Z'
          },
          {
            id: 'tool-budget-001',
            agentSessionId: 'demo-session',
            toolName: 'price',
            status: 'PASS',
            confidence: 'HIGH',
            summary: 'Budget check passed.',
            latencyMs: 31,
            createdAt: '2026-06-29T10:36:13Z'
          }
        ],
        page: 0,
        size: 20,
        total: 1
      })
    });
  });
  await page.route('**/api/admin/rag-evidence', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: 'rag-psu-001',
            agentSessionId: 'demo-session',
            sourceId: 'psu-rule-001',
            summary: 'PSU capacity evidence.',
            score: 0.88,
            metadata: { sourceType: 'INTERNAL_RULE' }
          }
        ],
        page: 0,
        size: 20,
        total: 1
      })
    });
  });

  await page.goto('/admin/agent-sessions');
  await expect(page.locator('body')).toContainText('에이전트 세션 목록');
  await expect(page.locator('main')).toContainText('식별자');
  await expect(page.locator('main')).toContainText('상태');
  await expect(page.locator('main')).toContainText('사용자');
  await expect(page.locator('main')).toContainText('생성 시간');
  await expect(page.locator('main')).toContainText('성공');
  await expect(page.getByRole('link', { name: 'demo-session' })).toHaveAttribute('href', '/admin/agent-sessions/demo-session');
  await expect(page.getByText('에이전트 세션 목록').first()).toHaveCSS('font-family', /Noto Sans KR/);

  await page.goto('/admin/tool-invocations');
  await expect(page.locator('body')).toContainText('도구 호출 목록');
  await expect(page.locator('main')).toContainText('세션');
  await expect(page.locator('main')).toContainText('도구');
  await expect(page.locator('main')).toContainText('근거 수준');
  await expect(page.locator('main')).not.toContainText('신뢰도');
  await expect(page.locator('main')).toContainText('전력 여유 확인');
  await expect(page.locator('main')).toContainText('성능 적합도');
  await expect(page.locator('main')).toContainText('예산 확인');
  await expect(page.locator('main')).toContainText('통과');
  await expect(page.locator('main')).toContainText('높음');
  await expect(page.getByRole('link', { name: 'tool-power-001' })).toHaveAttribute('href', '/admin/tool-invocations/tool-power-001');

  await page.goto('/admin/rag-evidence');
  await expect(page.locator('body')).toContainText('검색 근거 목록');
  await expect(page.locator('main')).toContainText('출처 식별자');
  await expect(page.locator('main')).toContainText('요약');
  await expect(page.locator('main')).toContainText('점수');
  await expect(page.getByRole('link', { name: 'rag-psu-001' })).toHaveAttribute('href', '/admin/rag-evidence/rag-psu-001');
});

test('renders admin Tool and RAG detail pages in Korean', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/tool-invocations/tool-power-001', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'tool-power-001',
        agentSessionId: 'demo-session',
        toolName: 'power',
        status: 'WARN',
        confidence: 'MEDIUM',
        summary: 'Power margin is low.',
        requestPayload: { tool: 'power' },
        resultPayload: { status: 'WARN' },
        latencyMs: 42,
        createdAt: '2026-06-29T10:36:10Z'
      })
    });
  });
  await page.route('**/api/admin/rag-evidence/rag-psu-001', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'rag-psu-001',
        agentSessionId: 'demo-session',
        sourceId: 'psu-rule-001',
        summary: 'PSU capacity evidence.',
        score: 0.88,
        chunkText: 'Power supply sizing rule chunk.',
        metadata: { sourceType: 'INTERNAL_RULE' }
      })
    });
  });

  await page.goto('/admin/tool-invocations/tool-power-001');
  await expect(page.locator('body')).toContainText('도구 호출 상세');
  await expect(page.locator('main')).toContainText('전력 여유 확인 / tool-power-001');
  await expect(page.locator('main')).toContainText('요청 데이터');
  await expect(page.locator('main')).toContainText('결과 데이터');
  await expect(page.locator('main')).toContainText('상태');
  await expect(page.locator('main')).toContainText('근거 수준');
  await expect(page.locator('main')).not.toContainText('신뢰도');
  await expect(page.locator('main')).toContainText('주의');
  await expect(page.locator('main')).toContainText('보통');
  await expect(page.locator('pre').first()).toContainText('"tool"');
  await expect(page.locator('pre').first()).toContainText('"power"');

  await page.goto('/admin/rag-evidence/rag-psu-001');
  await expect(page.locator('body')).toContainText('검색 근거 상세');
  await expect(page.locator('main')).toContainText('근거 본문');
  await expect(page.locator('main')).toContainText('메타데이터 JSON');
  await expect(page.locator('main')).toContainText('출처 식별자');
  await expect(page.locator('main')).toContainText('점수 0.88');
  await expect(page.locator('pre').first()).toContainText('"sourceType"');
});

test('renders manufacturer release demo intake on admin parts page', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/parts?**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: '00000000-0000-4000-8000-000000009701',
            category: 'GPU',
            name: 'ASUS ROG Astral GeForce RTX 5090 OC 32GB',
            manufacturer: 'ASUS',
            price: 4980000,
            status: 'INACTIVE',
            attributes: { gpuClass: 'RTX_5090', toolReady: false },
            toolReady: false,
            missingRequiredFields: ['vramGb', 'lengthMm'],
            updatedAt: '2026-07-01T09:00:00+09:00',
            externalOffer: null
          }
        ],
        page: 0,
        size: 100,
        total: 1
      })
    });
  });
  await page.route('**/api/admin/manufacturer-sources?**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: '00000000-0000-4000-8000-000000009501',
            manufacturer: 'BuildGraph Demo',
            categoryScope: 'GPU',
            sourceType: 'RSS',
            sourceUrl: 'http://localhost:8080/api/demo/manufacturer-release-feed.xml',
            enabled: false,
            pollIntervalMinutes: 1440,
            lastCheckedAt: null,
            status: 'ACTIVE'
          }
        ]
      })
    });
  });
  await page.route('**/api/admin/manufacturer-posts?**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: '00000000-0000-4000-8000-000000009511',
            manufacturer: 'BuildGraph Demo',
            externalUrl: 'http://localhost:8080/api/demo/manufacturer-release-post/rtx-5090-oc-32gb',
            title: 'ASUS launches ROG Astral GeForce RTX 5090 OC 32GB graphics card',
            classificationStatus: 'PRODUCT_CANDIDATE',
            detectedCategory: 'GPU',
            detectedProductName: 'ROG Astral GeForce RTX 5090 OC 32GB',
            confidence: 0.95,
            catalogCandidateId: '00000000-0000-4000-8000-000000009601',
            createdAt: '2026-07-01T09:00:00+09:00'
          }
        ],
        page: 0,
        size: 10,
        total: 1
      })
    });
  });
  await page.route('**/api/admin/part-catalog-candidates?**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: '00000000-0000-4000-8000-000000009602',
            source: 'MANUFACTURER_RELEASE_NAVER_SEARCH',
            category: 'CASE',
            searchQuery: 'LIAN LI LIAN LI O11 VISION-M',
            title: '리안리 O11 VISION-M 화이트',
            manufacturerGuess: '리안리',
            supplierName: '네이버',
            lowPrice: 129740,
            candidateStatus: 'PUBLISHED',
            publishedPartId: '00000000-0000-4000-8000-000000009701',
            publishedPartStatus: 'INACTIVE'
          },
          {
            id: '00000000-0000-4000-8000-000000009601',
            source: 'MANUFACTURER_RELEASE_NAVER_SEARCH',
            category: 'GPU',
            searchQuery: 'ASUS ROG Astral GeForce RTX 5090 OC 32GB',
            title: 'ASUS ROG Astral GeForce RTX 5090 OC 32GB',
            manufacturerGuess: 'ASUS',
            supplierName: 'Naver Store',
            lowPrice: 4980000,
            candidateStatus: 'DISCOVERED',
            publishedPartId: null,
            publishedPartStatus: null
          }
        ],
        page: 0,
        size: 10,
        total: 1
      })
    });
  });
  await page.route('**/api/admin/part-alias-review-items?**', async (route) => {
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
  await page.route('**/api/admin/part-alias-review-items/summary', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: []
      })
    });
  });
  await page.route('**/api/admin/parts/quality-report', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        summary: {
          activeParts: 1,
          toolReadyMissing: 1,
          requiredSpecMissing: 1,
          benchmarkMissing: 0,
          fpsCoverageGap: 0,
          aliasReviewOpen: 0
        },
        categories: [
          {
            category: 'GPU',
            activeParts: 1,
            toolReadyMissing: 1,
            requiredSpecMissing: 1,
            benchmarkMissing: 0,
            fpsCoverageGap: 0,
            aliasReviewOpen: 0
          }
        ],
        actionItems: [
          {
            type: 'MISSING_REQUIRED_SPEC',
            category: 'GPU',
            partId: '00000000-0000-4000-8000-000000009701',
            label: 'ASUS ROG Astral GeForce RTX 5090 OC 32GB',
            message: '필수 스펙 누락: vramGb, lengthMm'
          }
        ],
        generatedAt: '2026-07-02T09:00:00+09:00'
      })
    });
  });
  await page.route('**/api/admin/part-alias-rules?**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [],
        page: 0,
        size: 50,
        total: 0
      })
    });
  });
  let scanAllCalls = 0;
  await page.route('**/api/admin/manufacturer-sources/scan?**', async (route) => {
    scanAllCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        scannedSources: 1,
        newPosts: 1,
        createdCandidates: 1,
        failedSources: 0,
        results: []
      })
    });
  });
  let scanCalls = 0;
  await page.route('**/api/admin/manufacturer-sources/*/scan?**', async (route) => {
    scanCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        sourceId: '00000000-0000-4000-8000-000000009501',
        failed: false,
        parsedPosts: 1,
        newPosts: 1,
        productPosts: 1,
        createdCandidates: 1
      })
    });
  });
  let approveCalls = 0;
  await page.route('**/api/admin/part-catalog-candidates/*/approve', async (route) => {
    approveCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        candidateId: '00000000-0000-4000-8000-000000009601',
        publishedPartId: '00000000-0000-4000-8000-000000009701',
        created: true,
        partStatus: 'INACTIVE',
        status: 'PUBLISHED'
      })
    });
  });
  let aiAssetDraftCalls = 0;
  await page.route('**/api/admin/manufacturer-posts/*/ai-asset-draft', async (route) => {
    aiAssetDraftCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        postId: '00000000-0000-4000-8000-000000009511',
        aiUsed: true,
        classificationStatus: 'PRODUCT_CANDIDATE',
        detectedCategory: 'GPU',
        detectedProductName: 'ROG Astral GeForce RTX 5090 OC 32GB',
        confidence: 0.95,
        candidateId: '00000000-0000-4000-8000-000000009601',
        // AI 초안은 더 이상 INACTIVE 자산을 자동 연결하지 않는다(감사 A3) — 검수 대기 후보까지만.
        candidateStatus: 'PENDING_REVIEW',
        partId: null,
        partStatus: null,
        messages: ['AI가 제조사 게시글을 신제품 후보로 구조화했습니다.', 'AI 초안이 후보에 반영되었습니다. 검수 후 \'후보 승인\'으로 INACTIVE 자산 초안을 생성하세요.']
      })
    });
  });
  let refreshOfferCalls = 0;
  await page.route('**/api/admin/part-catalog-candidates/*/refresh-offers', async (route) => {
    refreshOfferCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        configured: true,
        candidateId: '00000000-0000-4000-8000-000000009601',
        updated: true,
        attempted: 1,
        title: 'ASUS ROG Astral GeForce RTX 5090 OC 32GB',
        lowPrice: 4980000
      })
    });
  });

  await page.goto('/admin/parts');

  await expect(page.locator('body')).toContainText('부품 / 가격 관리자');
  await expect(page.locator('main')).toContainText('부품 DB 관리');
  await expect(page.locator('main')).toContainText('ASUS ROG Astral GeForce RTX 5090 OC 32GB');
  await page.getByRole('button', { name: '부품 DB 관리 접기' }).click();
  await expect(page.locator('main')).not.toContainText('ASUS ROG Astral GeForce RTX 5090 OC 32GB');
  await expect(page.locator('main')).not.toContainText('부품 상세 패널');
  await page.getByRole('button', { name: '부품 DB 관리 펼치기' }).click();
  await expect(page.locator('main')).toContainText('ASUS ROG Astral GeForce RTX 5090 OC 32GB');
  await expect(page.locator('main')).toContainText('부품 상세 패널');
  await expect(page.locator('main')).toContainText('제조사 신제품 감지');
  await expect(page.locator('main')).not.toContainText('BuildGraph Demo');
  await page.getByRole('button', { name: '제조사 신제품 감지 운영 펼치기' }).click();
  await expect(page.locator('main')).toContainText('BuildGraph Demo');
  await expect(page.locator('main')).toContainText('활성 source 전체 scan');
  await expect(page.getByRole('link', { name: '열기' })).toHaveAttribute('href', 'http://localhost:8080/api/demo/manufacturer-release-feed.xml');
  await page.getByRole('button', { name: 'BuildGraph Demo' }).click();
  await expect(page.locator('main')).toContainText('Source 수정');

  await page.getByRole('button', { name: '전체 scan' }).click();
  expect(scanAllCalls).toBe(1);
  await expect(page.locator('main')).toContainText('전체 scan 완료');

  await page.getByRole('button', { name: 'scan', exact: true }).click();
  expect(scanCalls).toBe(1);
  await expect(page.locator('main')).toContainText('scan 완료');

  await page.getByRole('button', { name: '감지 게시글' }).click();
  await expect(page.locator('main')).toContainText('ASUS launches ROG Astral GeForce RTX 5090 OC 32GB graphics card');
  await page.getByRole('button', { name: 'AI 초안화' }).click();
  expect(aiAssetDraftCalls).toBe(1);
  await expect(page.locator('main')).toContainText('AI 초안 · 후보 검수 대기');
  await page.getByRole('button', { name: '신제품 후보함' }).click();
  await expect(page.locator('main')).toContainText('ASUS ROG Astral GeForce RTX 5090 OC 32GB');
  await expect(page.locator('main')).toContainText('리안리 O11 VISION-M 화이트');
  await expect(page.locator('main')).toContainText('초안 열기');
  await expect(page.locator('main')).not.toContainText('source product key');

  const discoveredCandidateRow = page.getByRole('row', {
    name: /ASUS ROG Astral GeForce RTX 5090 OC 32GB.*DISCOVERED/
  });
  await expect(discoveredCandidateRow.getByRole('button', { name: 'offer 재검색' })).toBeEnabled();
  await Promise.all([
    page.waitForRequest((request) => request.method() === 'POST'
      && request.url().includes('/api/admin/part-catalog-candidates/')
      && request.url().endsWith('/refresh-offers')),
    discoveredCandidateRow.getByRole('button', { name: 'offer 재검색' }).click()
  ]);
  expect(refreshOfferCalls).toBe(1);
  await expect(page.locator('main')).toContainText('offer 재검색 완료');

  await page.getByRole('button', { name: '승인', exact: true }).click();
  expect(approveCalls).toBe(1);
  await expect(page.locator('main')).toContainText('INACTIVE 초안 생성');
});

test('renders ten admin shell navigation entries for ADMIN role', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/dashboard', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        agentRunning: 1,
        openTickets: 3,
        priceJobsRunning: 0,
        degraded: false,
        generatedAt: '2026-06-29T10:50:00Z'
      })
    });
  });
  await page.route('**/api/admin/audit-logs/recent', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [] })
    });
  });
  await mockRecommendationModelSummary(page);

  await page.goto('/admin');

  const navigation = page.getByRole('navigation', { name: '관리자 메뉴' });
  await expect(navigation.getByRole('link')).toHaveCount(10);
  await expect(navigation.getByRole('link', { name: '대시보드' })).toHaveAttribute('href', '/admin');
  await expect(navigation.getByRole('link', { name: '에이전트 세션' })).toHaveAttribute('href', '/admin/agent-sessions');
  await expect(navigation.getByRole('link', { name: '도구 이력' })).toHaveAttribute('href', '/admin/tool-invocations');
  await expect(navigation.getByRole('link', { name: '검색 근거' })).toHaveAttribute('href', '/admin/rag-evidence');
  await expect(navigation.getByRole('link', { name: '부품/가격' })).toHaveAttribute('href', '/admin/parts');
  await expect(navigation.getByRole('link', { name: 'AS 티켓' })).toHaveAttribute('href', '/admin/as-tickets');
  await expect(navigation.getByRole('link', { name: '상담방' })).toHaveAttribute('href', '/admin/support-chat-sessions');
  await expect(navigation.getByRole('link', { name: '가격 작업' })).toHaveAttribute('href', '/admin/price-jobs');
  await expect(navigation.getByRole('link', { name: '슬롯 보드 배치' })).toHaveAttribute('href', '/admin/build-graph-layouts');
  await expect(navigation.getByRole('link', { name: '부하 테스트' })).toHaveAttribute('href', '/admin/load-tests');
  await expect(navigation.getByRole('link', { name: '에이전트 세션' })).toHaveCSS('font-family', /Noto Sans KR/);

  await expect(page.getByRole('searchbox', { name: '관리자 검색' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '내보내기' })).toBeEnabled();
  await expect(page.getByRole('button', { name: '작업 실행' })).toHaveCount(0);
});

test('admin can drag self quote slot cards and save the fixed board layout', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });

  let savedPayload: { positions?: Record<string, { x: number; y: number }> } | null = null;
  let resetCalled = false;
  await page.route('**/api/admin/build-graph-layouts/default', async (route) => {
    const method = route.request().method();
    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          layoutKey: 'DEFAULT',
          source: 'DEFAULT',
          positions: {
            CPU: { x: 9, y: 6 },
            MOTHERBOARD: { x: 31, y: 76 },
            RAM: { x: 43, y: 8 },
            GPU: { x: 41, y: 53 },
            PSU: { x: 58, y: 78 },
            CASE: { x: 8, y: 76 },
            COOLER: { x: 9, y: 30 },
            STORAGE: { x: 74, y: 25 },
            PRICE: { x: 50, y: 50 }
          }
        })
      });
      return;
    }
    if (method === 'PUT') {
      savedPayload = JSON.parse(route.request().postData() ?? '{}');
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          layoutKey: 'DEFAULT',
          source: 'SAVED',
          positions: savedPayload?.positions ?? {}
        })
      });
      return;
    }
    if (method === 'DELETE') {
      resetCalled = true;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          layoutKey: 'DEFAULT',
          source: 'DEFAULT',
          positions: {
            CPU: { x: 9, y: 6 },
            GPU: { x: 41, y: 53 }
          }
        })
      });
      return;
    }
    await route.fallback();
  });

  await page.goto('/admin/build-graph-layouts');

  await expect(page.getByRole('heading', { name: '견적 슬롯 보드 배치' })).toBeVisible();
  await expect(page.getByRole('link', { name: '슬롯 보드 배치' })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByTestId('admin-slot-layout-board')).toBeVisible();
  await expect(page.getByTestId('admin-slot-layout-card-GPU')).toContainText('GPU');
  await expect(page.getByText('x 41 · y 53')).toBeVisible();

  const gpuSlot = page.getByTestId('admin-slot-layout-card-GPU');
  await expect(gpuSlot).toBeVisible();
  const before = await gpuSlot.boundingBox();
  expect(before).not.toBeNull();
  await page.mouse.move((before?.x ?? 0) + 40, (before?.y ?? 0) + 30);
  await page.mouse.down();
  await page.mouse.move((before?.x ?? 0) + 180, (before?.y ?? 0) + 80, { steps: 8 });
  await page.mouse.up();

  await expect(page.getByText('저장되지 않은 변경')).toBeVisible();
  await page.getByRole('button', { name: '고정하기' }).click();
  await expect.poll(() => savedPayload?.positions?.GPU?.x ?? 0).toBeGreaterThan(41);
  // 클램프 상한 = 100 - 카드 폭. 허브 방사형 좌표에서 GPU 카드 폭이 21%라 상한은 79다.
  await expect.poll(() => savedPayload?.positions?.GPU?.x ?? 999).toBeLessThanOrEqual(57.5);
  await expect.poll(() => savedPayload?.positions?.GPU?.y ?? 0).toBeGreaterThan(53);
  await expect.poll(() => savedPayload?.positions?.PRICE).toBeUndefined();
  await expect(page.getByText('저장 완료')).toBeVisible();

  await page.getByRole('button', { name: '기본값으로 초기화' }).click();
  await expect.poll(() => resetCalled).toBe(true);
  await expect(page.getByText('기본 배치로 초기화됨')).toBeVisible();
  await expect(page.getByText('x 41 · y 53')).toBeVisible();
});

test('renders price job and load test admin menu pages for ADMIN role', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/price-jobs', async (route) => {
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

  await page.goto('/admin/price-jobs');
  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeHidden();
  await expect(page.locator('body')).toContainText('가격 작업 관리자');
  await expect(page.locator('main')).toContainText('가격 수집 작업');
  await expect(page.locator('main')).toContainText('네이버 쇼핑 연동');
  await expect(page.locator('main')).toContainText('작업 처리기 실행');
  await expect(page.getByRole('button', { name: '가격 작업 실행' }).first()).toBeEnabled();
  await expect(page.getByText('가격 작업 관리자').first()).toHaveCSS('font-family', /Noto Sans KR/);

  await page.goto('/admin/load-tests');
  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeHidden();
  await expect(page.locator('body')).toContainText('부하 테스트');
  await expect(page.locator('main')).toContainText('k6 Smoke');
  await expect(page.locator('main')).toContainText('npm run test');
  await expect(page.locator('main')).toContainText('300명');
});

test('renders admin dashboard with ADMIN role and dashboard API response', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  let authMeAuthorization: string | undefined;
  await page.route('**/api/auth/me', async (route) => {
    authMeAuthorization = route.request().headers().authorization;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  let dashboardCalls = 0;
  let dashboardAuthorization: string | undefined;
  await page.route('**/api/admin/dashboard', async (route) => {
    dashboardCalls += 1;
    dashboardAuthorization = route.request().headers().authorization;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        agentRunning: 1,
        openTickets: 3,
        priceJobsRunning: 0,
        degraded: false,
        generatedAt: '2026-06-29T10:50:00Z'
      })
    });
  });
  let auditLogCalls = 0;
  let auditLogAuthorization: string | undefined;
  await page.route('**/api/admin/audit-logs/recent', async (route) => {
    auditLogCalls += 1;
    auditLogAuthorization = route.request().headers().authorization;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            action: 'AS_TICKET_UPDATED',
            targetType: 'as_tickets',
            targetId: '4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a',
            metadata: { beforeStatus: 'OPEN', afterStatus: 'IN_PROGRESS' },
            createdAt: '2026-06-29T10:45:00Z'
          }
        ]
      })
    });
  });
  await mockRecommendationModelSummary(page);

  await page.goto('/admin');

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeHidden();
  await expect(page.locator('main')).toContainText('진행 중 Agent');
  await expect(page.locator('main')).toContainText('미해결 AS');
  await expect(page.locator('main')).toContainText('실행 중 Price Job');
  await expect(page.locator('main')).toContainText('운영 상태');
  await expect(page.locator('main')).toContainText('1건');
  await expect(page.locator('main')).toContainText('3건');
  await expect(page.locator('main')).toContainText('0건');
  await expect(page.locator('main')).toContainText('정상');
  await expect(page.locator('main')).toContainText('2026-06-29T10:50:00Z');
  await expect(page.locator('main')).toContainText('최근 Agent 세션 요약');
  await expect(page.locator('main')).toContainText('운영 작업');
  await expect(page.locator('main')).toContainText('관리자 할 일');
  await expect(page.locator('main')).toContainText('최근 관리자 작업');
  await expect(page.locator('main')).toContainText('AI 추천 모델 상태');
  await expect(page.locator('main')).toContainText('xgb-20260703100000');
  await expect(page.locator('main')).toContainText('20%');
  await expect(page.locator('main')).toContainText('RTX 5090 추천 후보');
  await expect(page.getByRole('button', { name: '추천 강화' })).toBeVisible();
  await expect(page.getByRole('button', { name: '추천 낮춤' })).toBeVisible();
  await expect(page.locator('main')).toContainText('XGBoost 학습 운영');
  await expect(page.locator('main')).toContainText('eligible events');
  await expect(page.locator('main')).toContainText('untrained eligible events');
  await page.getByRole('button', { name: '데이터셋' }).click();
  await expect(page.locator('main')).toContainText('홈 추천부품 학습 데이터셋');
  await expect(page.getByRole('button', { name: '현재 HOME/AS 피드백으로 데이터셋 생성' })).toBeVisible();
  await page.getByRole('button', { name: '학습 Job' }).click();
  await expect(page.locator('main')).toContainText('학습 데이터 부족');
  await page.getByRole('button', { name: '모델 버전' }).click();
  await expect(page.getByRole('button', { name: '활성화' })).toBeVisible();
  await expect(page.locator('main')).toContainText('AS_TICKET_UPDATED');
  await expect(page.locator('main')).toContainText('as_tickets');
  await expect(page.locator('main')).toContainText('4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a');
  await expect(page.locator('main')).toContainText('2026-06-29T10:45:00Z');
  await expect(page.locator('main')).toContainText('가격 Job');
  await expect(page.locator('main')).toContainText('Mailpit');
  await expect(page.locator('main')).toContainText('Mock Worker');
  await expect(page.locator('main')).toContainText('k6 Smoke');
  await expect(page.locator('main')).toContainText('부품/가격');
  await expect(page.locator('main')).toContainText('Agent/RAG');
  await expect(page.locator('main')).toContainText('AS 티켓');
  await expect(page.locator('main')).not.toContainText('undefined');
  expect(authMeAuthorization).toBe('Bearer jwt-admin-token');
  expect(dashboardCalls).toBe(1);
  expect(dashboardAuthorization).toBe('Bearer jwt-admin-token');
  expect(auditLogCalls).toBe(1);
  expect(auditLogAuthorization).toBe('Bearer jwt-admin-token');
});

test('shows degraded alert on admin dashboard when dashboard API reports degraded', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/dashboard', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        agentRunning: 4,
        openTickets: 7,
        priceJobsRunning: 2,
        degraded: true,
        generatedAt: '2026-06-29T11:05:00Z'
      })
    });
  });
  await page.route('**/api/admin/audit-logs/recent', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [] })
    });
  });
  await mockRecommendationModelSummary(page);

  await page.goto('/admin');

  await expect(page.locator('main')).toContainText('운영 상태 주의');
  await expect(page.locator('main')).toContainText('일부 운영 지표가 주의 상태입니다.');
  await expect(page.locator('main')).toContainText('4건');
  await expect(page.locator('main')).toContainText('7건');
  await expect(page.locator('main')).toContainText('2건');
  await expect(page.locator('main')).toContainText('주의');
  await expect(page.locator('main')).not.toContainText('undefined');
});

test('keeps admin dashboard usable when audit logs API fails', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/dashboard', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        agentRunning: 1,
        openTickets: 3,
        priceJobsRunning: 0,
        degraded: false,
        generatedAt: '2026-06-29T10:50:00Z'
      })
    });
  });
  await page.route('**/api/admin/audit-logs/recent', async (route) => {
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'INTERNAL_ERROR', message: '감사 로그 조회 실패' })
    });
  });
  await mockRecommendationModelSummary(page);

  await page.goto('/admin');

  await expect(page.locator('main')).toContainText('진행 중 Agent');
  await expect(page.locator('main')).toContainText('1건');
  await expect(page.locator('main')).toContainText('감사 로그 조회 실패');
  await expect(page.locator('main')).toContainText('최근 관리자 작업을 불러오지 못했습니다.');
  await expect(page.locator('main')).not.toContainText('undefined');
});

test('shows admin dashboard loading state while dashboard API is pending', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });

  let releaseDashboard: (() => void) | undefined;
  const dashboardReady = new Promise<void>((resolve) => {
    releaseDashboard = resolve;
  });
  await page.route('**/api/admin/dashboard', async (route) => {
    await dashboardReady;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        agentRunning: 1,
        openTickets: 3,
        priceJobsRunning: 0,
        degraded: false,
        generatedAt: '2026-06-29T10:50:00Z'
      })
    });
  });
  await mockRecommendationModelSummary(page);

  await page.goto('/admin');

  await expect(page.getByText('대시보드 로딩 중')).toBeVisible();
  await expect(page.getByText('운영 지표를 불러오고 있습니다.')).toBeVisible();

  releaseDashboard?.();
  await expect(page.locator('main')).toContainText('진행 중 Agent');
  await expect(page.locator('main')).toContainText('1건');
});

test('shows admin dashboard error state when dashboard API fails', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'admin-001', email: 'admin@example.com', role: 'ADMIN' })
    });
  });
  await page.route('**/api/admin/dashboard', async (route) => {
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'INTERNAL_ERROR', message: '대시보드 조회 실패' })
    });
  });

  await page.goto('/admin');

  await expect(page.getByText('대시보드 조회 실패')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('관리자 대시보드 API 응답을 불러오지 못했습니다.')).toBeVisible();
});
