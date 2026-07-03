import { expect, test, type APIRequestContext, type Page, type Request, type Response } from '@playwright/test';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const apiBaseUrl = process.env.AI_LATENCY_API_BASE_URL ?? 'http://127.0.0.1:8080';
const userEmail = process.env.AI_LATENCY_USER_EMAIL ?? 'user@example.com';
const password = process.env.AI_LATENCY_PASSWORD ?? 'passw0rd!';
const reportDate = new Date().toISOString().slice(0, 10).replaceAll('-', '');

type ScenarioGroup =
  | 'FAST_LOCAL_ROUTE'
  | 'FAST_SERVER_ROUTE'
  | 'DRAFT_ACTION'
  | 'DETERMINISTIC_RECOMMEND'
  | 'LLM_FULL_COMPLEX';

type PartCategory = 'CPU' | 'MOTHERBOARD' | 'RAM' | 'GPU' | 'STORAGE' | 'PSU' | 'CASE' | 'COOLER';

type Scenario = {
  id: string;
  group: ScenarioGroup;
  prompt: string;
  startPath?: string;
  prepareDraft?: boolean;
  expectNoBuildChat?: boolean;
  expectBuildChat?: boolean;
  expectBuilds?: boolean;
  expectPartRecommendation?: boolean;
  expectDraftApplied?: boolean;
  expectRoute?: RouteExpectation;
  forbidPartDetailRoute?: boolean;
};

type RouteExpectation =
  | { kind: 'path'; path: string }
  | { kind: 'partDetail' }
  | { kind: 'filter'; category: PartCategory; queryIncludes: string };

type PartRow = {
  id: string;
  category: PartCategory;
  name: string;
  manufacturer?: string;
  price?: number;
};

type QuoteDraft = {
  items?: Array<{ partId: string; category?: PartCategory; quantity?: number }>;
};

type BuildChatBody = {
  answerType?: string;
  message?: string;
  builds?: Array<{
    items?: Array<{ partId?: string; category?: PartCategory; quantity?: number }>;
    toolResults?: Array<{ status?: string }>;
    warnings?: string[];
  }>;
  partRecommendation?: unknown;
  actions?: Array<{
    type?: string;
    payload?: { route?: string; category?: string; partId?: string; buildId?: string };
  }>;
  warnings?: string[];
};

type ApiEvent = {
  method: string;
  path: string;
  startedMs: number;
  endedMs?: number;
  status?: number;
};

type ScenarioResult = {
  id: string;
  group: ScenarioGroup;
  prompt: string;
  ok: boolean;
  totalMs: number;
  submitStart: string;
  assistantRenderedMs?: number;
  actionStartedMs?: number;
  actionSettledMs?: number;
  navigationSettledMs?: number;
  draftSettledMs?: number;
  buildChatMs?: number;
  answerType?: string;
  actionTypes: string[];
  route?: string;
  buildCount: number;
  warningCount: number;
  apiCalls: ApiEvent[];
  errors: string[];
};

const groupThresholds: Record<ScenarioGroup, number> = {
  FAST_LOCAL_ROUTE: 1_000,
  FAST_SERVER_ROUTE: 1_500,
  DRAFT_ACTION: 2_000,
  DETERMINISTIC_RECOMMEND: 3_000,
  LLM_FULL_COMPLEX: 5_000
};

let cachedSeedBuildItems: Array<{ partId: string; category?: PartCategory; quantity?: number }> | null = null;

test.describe.configure({ mode: 'serial' });

test('AI chatbot live web latency and fast-path safety scenarios', async ({ page, request }) => {
  test.setTimeout(1_200_000);
  await preflight(request);
  const login = await loginByApi(request);
  await authenticatePage(page, login);

  const parts = await loadRepresentativeParts(request, login.accessToken);
  const scenarios = buildScenarios(parts);
  expect(scenarios).toHaveLength(60);

  const results: ScenarioResult[] = [];
  for (const scenario of scenarios) {
    results.push(await runScenario(page, request, login.accessToken, scenario, parts));
  }

  const reportPaths = writeLatencyReport(results);
  const failures = evaluateResults(results);
  expect(failures, `AI latency report: ${reportPaths.markdown}\n${failures.join('\n')}`).toEqual([]);
});

async function preflight(request: APIRequestContext) {
  if (!hasOpenAiKey()) {
    throw new Error('OPENAI_API_KEY is required for AI latency live E2E. Set it before running docker compose and this script.');
  }
  const health = await request.get(`${apiBaseUrl}/api/health`);
  expect(health.ok(), `API health check failed: ${health.status()} ${await health.text()}`).toBeTruthy();
}

async function loginByApi(request: APIRequestContext) {
  const response = await request.post(`${apiBaseUrl}/api/auth/login`, {
    data: { email: userEmail, password }
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  const body = await response.json() as {
    accessToken: string;
    refreshToken: string;
    user: { id: string; email: string; role: string };
  };
  expect(body.accessToken).toBeTruthy();
  return body;
}

async function authenticatePage(page: Page, login: Awaited<ReturnType<typeof loginByApi>>) {
  await page.goto('/login');
  await page.evaluate((auth) => {
    localStorage.setItem('buildgraph.token', auth.accessToken);
    localStorage.setItem('buildgraph.refreshToken', auth.refreshToken);
    localStorage.setItem('buildgraph.authUser', JSON.stringify(auth.user));
    sessionStorage.clear();
  }, login);
  await page.goto('/');
  await expect(page.getByText(`로그인됨 · ${userEmail} · USER`)).toBeVisible();
}

async function runScenario(
  page: Page,
  request: APIRequestContext,
  accessToken: string,
  scenario: Scenario,
  representativeParts: Record<PartCategory, PartRow>
): Promise<ScenarioResult> {
  const errors: string[] = [];
  const startPath = scenario.startPath ?? '/';
  if (scenario.prepareDraft) {
    await seedQuoteDraft(request, accessToken, representativeParts);
  }
  await clearAiSession(page);
  await page.goto(startPath);
  await clearAiSession(page);
  await openChat(page);

  const apiCalls: ApiEvent[] = [];
  const requestStarted = new Map<Request, ApiEvent>();
  const onRequest = (apiRequest: Request) => {
    const url = new URL(apiRequest.url());
    if (!url.pathname.startsWith('/api/')) return;
    const event = {
      method: apiRequest.method(),
      path: `${url.pathname}${url.search}`,
      startedMs: Date.now()
    };
    apiCalls.push(event);
    requestStarted.set(apiRequest, event);
  };
  const onResponse = (apiResponse: Response) => {
    const event = requestStarted.get(apiResponse.request());
    if (!event) return;
    event.endedMs = Date.now();
    event.status = apiResponse.status();
  };
  page.on('request', onRequest);
  page.on('response', onResponse);

  const beforeAiCount = await page.getByText('AI DB 답변').count();
  const buildChatResponsePromise = scenario.expectNoBuildChat
    ? Promise.resolve(null)
    : page.waitForResponse((response) =>
      response.url().includes('/api/ai/build-chat') && response.request().method() === 'POST',
      { timeout: 20_000 }
    ).catch(() => null);

  const submitStartMs = Date.now();
  const submitStart = new Date(submitStartMs).toISOString();
  await page.getByLabel('AI 챗봇에게 PC 사양 질문').fill(scenario.prompt);
  await page.getByRole('button', { name: '질문 보내기' }).click();

  let assistantRenderedMs: number | undefined;
  try {
    await expect.poll(async () => page.getByText('AI DB 답변').count(), { timeout: 500 }).toBeGreaterThan(beforeAiCount);
    assistantRenderedMs = Date.now() - submitStartMs;
  } catch {
    // The completion signal for this live suite is the user-visible outcome below:
    // route settled, draft applied, or recommendation rendered. Some route/action
    // flows navigate before the chat label count changes, so this timing is best-effort.
  }

  const buildChatResponse = await buildChatResponsePromise;
  let buildChatBody: BuildChatBody | null = null;
  let buildChatMs: number | undefined;
  if (buildChatResponse) {
    const event = apiCalls.find((call) => call.path.startsWith('/api/ai/build-chat') && call.endedMs);
    buildChatMs = event?.endedMs == null ? undefined : event.endedMs - event.startedMs;
    const buildChatText = await buildChatResponse.text();
    if (!buildChatResponse.ok()) {
      errors.push(`build-chat HTTP ${buildChatResponse.status()}: ${buildChatText}`);
    }
    try {
      buildChatBody = JSON.parse(buildChatText) as BuildChatBody;
    } catch (error) {
      errors.push(`buildChat JSON parse failed: ${messageOf(error)}`);
    }
  }

  if (scenario.expectNoBuildChat && apiCalls.some((call) => call.path.startsWith('/api/ai/build-chat'))) {
    errors.push('expected no build-chat API call, but /api/ai/build-chat was called');
  }
  if (scenario.expectBuildChat && !buildChatResponse) {
    errors.push('expected build-chat API call, but no response was observed');
  }

  const actionStartedAt = firstActionStartedAt(apiCalls);
  let actionSettledMs: number | undefined = actionStartedAt == null ? undefined : actionStartedAt - submitStartMs;
  let navigationSettledMs: number | undefined;
  let draftSettledMs: number | undefined;
  const scenarioTimeout = groupThresholds[scenario.group];

  try {
    if (scenario.expectRoute) {
      await waitForExpectedRoute(page, scenario.expectRoute, scenarioTimeout);
      navigationSettledMs = Date.now() - submitStartMs;
    }
    if (scenario.expectDraftApplied) {
      await expect(page.getByText('견적 장바구니에 적용됨').last()).toBeVisible({ timeout: scenarioTimeout });
      draftSettledMs = Date.now() - submitStartMs;
      actionSettledMs = draftSettledMs;
    }
    if (scenario.expectBuilds) {
      await expect(page.getByRole('button', { name: '이 조합으로 셀프 견적 보기' }).first()).toBeVisible({ timeout: scenarioTimeout });
    }
    if (scenario.expectPartRecommendation) {
      await expect(page.getByText('추천 후보').first()).toBeVisible({ timeout: scenarioTimeout });
    }
  } catch (error) {
    errors.push(`expectation failed: ${messageOf(error)}`);
  }

  await page.waitForTimeout(250);
  if (scenario.forbidPartDetailRoute && new URL(page.url()).pathname.startsWith('/parts/')) {
    errors.push('ambiguous product prompt must not navigate to /parts/{id}');
  }

  const bodyErrors = validateBuildChatBody(scenario, buildChatBody);
  errors.push(...bodyErrors);

  page.off('request', onRequest);
  page.off('response', onResponse);

  return {
    id: scenario.id,
    group: scenario.group,
    prompt: scenario.prompt,
    ok: errors.length === 0,
    totalMs: Date.now() - submitStartMs,
    submitStart,
    assistantRenderedMs,
    actionStartedMs: actionStartedAt == null ? undefined : actionStartedAt - submitStartMs,
    actionSettledMs,
    navigationSettledMs,
    draftSettledMs,
    buildChatMs,
    answerType: buildChatBody?.answerType,
    actionTypes: buildChatBody?.actions?.map((action) => action.type ?? 'UNKNOWN') ?? [],
    route: buildChatBody?.actions?.find((action) => action.type === 'OPEN_ROUTE')?.payload?.route,
    buildCount: buildChatBody?.builds?.length ?? 0,
    warningCount: (buildChatBody?.warnings?.length ?? 0)
      + (buildChatBody?.builds?.reduce((sum, build) => sum + (build.warnings?.length ?? 0), 0) ?? 0),
    apiCalls: apiCalls.map((call) => ({
      ...call,
      startedMs: call.startedMs - submitStartMs,
      endedMs: call.endedMs == null ? undefined : call.endedMs - submitStartMs
    })),
    errors
  };
}

function buildScenarios(parts: Record<PartCategory, PartRow>): Scenario[] {
  const exactParts = [parts.CPU, parts.GPU, parts.CASE, parts.PSU].filter(Boolean);
  return [
    ...[
      ['local-001', 'GPU 보여줘', '/self-quote?category=GPU'],
      ['local-002', 'CPU 부품 화면 열어줘', '/self-quote?category=CPU'],
      ['local-003', '램 부품 목록 보여줘', '/self-quote?category=RAM'],
      ['local-004', '셀프 견적 열어줘', '/self-quote'],
      ['local-005', '내 견적함 열어줘', '/my/quotes'],
      ['local-006', 'AI 견적 입력 화면으로 가자', '/requirements/new'],
      ['local-007', 'AS 접수하러 가자', '/support/new'],
      ['local-008', '구매하기 화면 열어줘', '/checkout']
    ].map(([id, prompt, path]) => ({
      id,
      group: 'FAST_LOCAL_ROUTE' as const,
      prompt,
      expectNoBuildChat: true,
      expectRoute: { kind: 'path' as const, path }
    })),
    ...exactParts.map((part, index) => ({
      id: `server-exact-${String(index + 1).padStart(3, '0')}`,
      group: 'FAST_SERVER_ROUTE' as const,
      prompt: `${part.name} 상세페이지로 이동해`,
      expectBuildChat: true,
      expectRoute: { kind: 'partDetail' as const }
    })),
    ...[
      ['server-filter-001', '5090 보여줘', 'GPU', '5090'],
      ['server-filter-002', '9950X3D 보여줘', 'CPU', '9950'],
      ['server-filter-003', 'MSI 보드 보여줘', 'MOTHERBOARD', 'MSI'],
      ['server-filter-004', '리안리 케이스 보여줘', 'CASE', '리안리'],
      ['server-filter-005', 'DDR5 램 보여줘', 'RAM', 'DDR5'],
      ['server-filter-006', 'NVME SSD 보여줘', 'STORAGE', 'NVME'],
      ['server-filter-007', '1000W 파워 보여줘', 'PSU', '1000'],
      ['server-filter-008', '수랭 쿨러 보여줘', 'COOLER', '수랭']
    ].map(([id, prompt, category, query]) => ({
      id,
      group: 'FAST_SERVER_ROUTE' as const,
      prompt,
      expectBuildChat: true,
      forbidPartDetailRoute: true,
      expectRoute: { kind: 'filter' as const, category: category as PartCategory, queryIncludes: query }
    })),
    ...[
      'GPU 빼줘',
      'RAM 64GB로 바꿔줘',
      '파워 1000W 이상으로 바꿔줘',
      '그래픽카드 더 싼데 성능 너무 떨어지지 않게 추천해줘',
      'CPU 더 좋은 걸로 바꿔줘',
      '메인보드 MSI 걸로 맞춰줘',
      'SSD 더 빠른 걸로 바꿔줘',
      '케이스 리안리 216 모델꺼로 맞춰줘',
      '쿨러 더 잘 식히는 걸로 바꿔줘',
      'RAM 수량 2개로 변경해줘',
      '그래픽카드 더 좋은 걸로 바꿔줘',
      '파워 1200W 이상으로 바꿔줘'
    ].map((prompt, index) => ({
      id: `draft-${String(index + 1).padStart(3, '0')}`,
      group: 'DRAFT_ACTION' as const,
      prompt,
      startPath: '/self-quote',
      prepareDraft: true,
      expectBuildChat: true,
      expectDraftApplied: true
    })),
    ...[
      '800만원으로 최고급 PC 추천해줘',
      '800만원짜리 컴퓨터 추천해줘',
      '300만원대 게임용 PC 추천해줘',
      '300만원으로 게임용 PC 추천해줘',
      '200만원 QHD 게임용 PC 추천해줘',
      '100만원 사무 학습용 PC 추천해줘',
      'QHD 배그 144Hz PC 추천해줘',
      '4K 영상편집 PC 추천해줘',
      '개발이랑 게임 같이 할 PC 추천해줘',
      '화이트 감성 PC 추천해줘',
      '저장공간 넉넉한 PC 추천해줘',
      '가성비형 조합 추천해줘',
      '끝판왕 PC 추천해줘'
    ].map((prompt, index) => ({
      id: `det-build-${String(index + 1).padStart(3, '0')}`,
      group: 'DETERMINISTIC_RECOMMEND' as const,
      prompt,
      expectBuildChat: true,
      expectBuilds: true
    })),
    ...[
      '고성능 GPU 추천해줘',
      '가성비 CPU 추천해줘',
      '저소음 쿨러 추천해줘'
    ].map((prompt, index) => ({
      id: `det-part-${String(index + 1).padStart(3, '0')}`,
      group: 'DETERMINISTIC_RECOMMEND' as const,
      prompt,
      expectBuildChat: true,
      expectPartRecommendation: true
    })),
    ...[
      '300만원 이하 RTX 5090 PC 추천해줘',
      '예산은 300인데 5090은 꼭 들어가야 해',
      '컴팩트 케이스 대신 통풍 좋은 케이스로 고성능 PC 추천해줘',
      'QHD 배그 144Hz인데 소음도 낮은 PC 추천해줘',
      '저소음 개발용인데 CUDA 실험도 해야 하는 PC 추천해줘',
      '영상편집 + Docker + IDE 병행용으로 400만원 안쪽',
      '전력 여유 넉넉한 5080급 게임용 PC 추천해줘',
      '수랭 쿨러 누수 보증 확실한 고성능 PC 추천해줘',
      '맥스엘리트 파워 이슈 걱정되니 안정적인 파워 위주 PC 추천해줘',
      '리안리 케이스 중심으로 고성능 게임용 PC 추천해줘',
      'AI 학습용 800만원 이하인데 소음 낮은 PC 추천해줘',
      '200만원으로 최고사양 느낌 나는 PC 추천해줘'
    ].map((prompt, index) => ({
      id: `llm-complex-${String(index + 1).padStart(3, '0')}`,
      group: 'LLM_FULL_COMPLEX' as const,
      prompt,
      expectBuildChat: true,
      expectBuilds: true
    }))
  ];
}

async function openChat(page: Page) {
  const panel = page.getByTestId('ai-chatbot-panel');
  if (await panel.isVisible().catch(() => false)) return;
  await page.getByTestId('ai-chatbot-launcher').click();
  await expect(panel).toBeVisible();
}

async function clearAiSession(page: Page) {
  await page.evaluate(() => {
    for (const key of Object.keys(sessionStorage)) {
      if (key.startsWith('buildgraph.ai.')) {
        sessionStorage.removeItem(key);
      }
    }
  });
}

async function loadRepresentativeParts(request: APIRequestContext, accessToken: string) {
  const result: Partial<Record<PartCategory, PartRow>> = {};
  const categories: PartCategory[] = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER'];
  for (const category of categories) {
    const part = await firstPart(request, accessToken, category);
    result[category] = part;
  }
  const cpu9950 = await firstPart(request, accessToken, 'CPU', '9950X3D').catch(() => null);
  const gpu5090 = await firstPart(request, accessToken, 'GPU', '5090').catch(() => null);
  const caseLianLi = await firstPart(request, accessToken, 'CASE', '리안리').catch(() => null);
  result.CPU = cpu9950 ?? result.CPU;
  result.GPU = gpu5090 ?? result.GPU;
  result.CASE = caseLianLi ?? result.CASE;
  return result as Record<PartCategory, PartRow>;
}

async function firstPart(request: APIRequestContext, accessToken: string, category: PartCategory, q?: string) {
  const params = new URLSearchParams({ category, page: '0', size: '20' });
  if (q) params.set('q', q);
  const response = await request.get(`${apiBaseUrl}/api/parts?${params}`, {
    headers: { Authorization: `Bearer ${accessToken}` }
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  const body = await response.json() as { items?: PartRow[] };
  const part = body.items?.[0];
  expect(part, `No ACTIVE part found for ${category}${q ? ` q=${q}` : ''}`).toBeTruthy();
  return part as PartRow;
}

async function seedQuoteDraft(
  request: APIRequestContext,
  accessToken: string,
  representativeParts: Record<PartCategory, PartRow>
) {
  const headers = { Authorization: `Bearer ${accessToken}` };
  const current = await request.get(`${apiBaseUrl}/api/quote-drafts/current`, { headers });
  expect(current.ok(), await current.text()).toBeTruthy();
  const draft = await current.json() as QuoteDraft;
  for (const item of draft.items ?? []) {
    if (item.partId) {
      await request.delete(`${apiBaseUrl}/api/quote-drafts/current/items/${item.partId}`, { headers });
    }
  }
  const seedItems = await seedBuildItems(request, accessToken);
  if (seedItems.length) {
    for (const item of seedItems) {
      const response = await request.put(`${apiBaseUrl}/api/quote-drafts/current/items/${item.partId}`, {
        headers,
        data: { quantity: item.quantity ?? (item.category === 'RAM' ? 2 : 1) }
      });
      expect(response.ok(), `${item.category ?? 'PART'} draft seed failed: ${response.status()} ${await response.text()}`).toBeTruthy();
    }
    return;
  }
  for (const category of ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER'] as PartCategory[]) {
    const part = representativeParts[category];
    const quantity = category === 'RAM' ? 2 : 1;
    const response = await request.put(`${apiBaseUrl}/api/quote-drafts/current/items/${part.id}`, {
      headers,
      data: { quantity }
    });
    expect(response.ok(), `${category} draft seed failed: ${response.status()} ${await response.text()}`).toBeTruthy();
  }
}

async function seedBuildItems(request: APIRequestContext, accessToken: string) {
  if (cachedSeedBuildItems) {
    return cachedSeedBuildItems;
  }
  const response = await request.post(`${apiBaseUrl}/api/ai/build-chat`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { message: '800만원으로 최고급 PC 추천해줘' }
  });
  if (!response.ok()) {
    cachedSeedBuildItems = [];
    return cachedSeedBuildItems;
  }
  const body = await response.json() as BuildChatBody;
  const items = body.builds?.[0]?.items
    ?.map((item) => ({
      partId: item.partId,
      category: item.category,
      quantity: item.quantity
    }))
    .filter((item): item is { partId: string; category?: PartCategory; quantity?: number } => Boolean(item.partId)) ?? [];
  cachedSeedBuildItems = items;
  return cachedSeedBuildItems;
}

async function waitForExpectedRoute(page: Page, expected: RouteExpectation, timeout: number) {
  if (expected.kind === 'path') {
    await expect(page).toHaveURL((url) => url.pathname + url.search === expected.path, { timeout });
    return;
  }
  if (expected.kind === 'partDetail') {
    await expect(page).toHaveURL((url) => /^\/parts\/[0-9a-f-]{8,}$/i.test(url.pathname), { timeout });
    return;
  }
  await expect(page).toHaveURL((url) => (
    url.pathname === '/self-quote'
    && url.searchParams.get('category') === expected.category
    && decodeURIComponent(url.searchParams.get('q') ?? '').toLowerCase().includes(expected.queryIncludes.toLowerCase())
  ), { timeout });
}

function validateBuildChatBody(scenario: Scenario, body: BuildChatBody | null) {
  const errors: string[] = [];
  if (!body) return errors;
  if (scenario.expectBuilds && !body.builds?.length) {
    errors.push('expected build recommendations, but response.builds is empty');
  }
  if (scenario.expectPartRecommendation && !body.partRecommendation) {
    errors.push('expected partRecommendation, but response.partRecommendation is missing');
  }
  for (const [index, build] of body.builds?.entries() ?? []) {
    if (build.toolResults?.some((tool) => tool.status === 'FAIL')) {
      errors.push(`build[${index}] contains Tool FAIL result`);
    }
  }
  const route = body.actions?.find((action) => action.type === 'OPEN_ROUTE')?.payload?.route;
  if (scenario.forbidPartDetailRoute && route?.startsWith('/parts/')) {
    errors.push(`ambiguous route returned product detail route: ${route}`);
  }
  return errors;
}

function firstActionStartedAt(apiCalls: ApiEvent[]) {
  return apiCalls.find((call) => (
    call.path.startsWith('/api/ai/build-chat')
    || call.path.startsWith('/api/quote-drafts/current')
    || call.path.startsWith('/api/parts')
  ))?.startedMs;
}

function evaluateResults(results: ScenarioResult[]) {
  const failures: string[] = [];
  for (const result of results) {
    if (!result.ok) {
      failures.push(`${result.id} failed: ${result.errors.join('; ')}`);
    }
    if (result.totalMs > 5_000) {
      failures.push(`${result.id} exceeded global 5s gate: ${result.totalMs}ms`);
    }
  }
  for (const group of Object.keys(groupThresholds) as ScenarioGroup[]) {
    const groupRows = results.filter((result) => result.group === group);
    const p95 = percentile(groupRows.map((row) => row.totalMs), 0.95);
    if (p95 > groupThresholds[group]) {
      failures.push(`${group} p95 exceeded ${groupThresholds[group]}ms: ${p95}ms`);
    }
  }
  return failures;
}

function writeLatencyReport(results: ScenarioResult[]) {
  const reportDir = resolve(process.cwd(), '..', '..', 'docs', 'reports');
  mkdirSync(reportDir, { recursive: true });
  const jsonPath = resolve(reportDir, `web-ai-latency-${reportDate}.json`);
  const markdownPath = resolve(reportDir, `web-ai-latency-${reportDate}.md`);
  writeFileSync(jsonPath, JSON.stringify({ generatedAt: new Date().toISOString(), results }, null, 2), 'utf8');
  writeFileSync(markdownPath, markdownReport(results), 'utf8');
  return { json: jsonPath, markdown: markdownPath };
}

function markdownReport(results: ScenarioResult[]) {
  const lines = [
    '# 웹 체감 AI Latency 리포트',
    '',
    '- 측정 기준: Docker live web + API에서 실제 챗봇 입력을 전송한 사용자 체감 시간',
    '- 정책: 자동 route/action은 현상 유지, public API 응답 shape 변경 없음',
    '- 전역 기준: 단일 케이스 5초 초과 0건',
    '',
    '## 그룹 요약',
    '',
    '| group | cases | successRate | avgMs | avgSec | p95Ms | p95Sec | maxMs | maxSec | thresholdMs | thresholdSec | failed |',
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |'
  ];
  for (const group of Object.keys(groupThresholds) as ScenarioGroup[]) {
    const rows = results.filter((result) => result.group === group);
    const avgMs = avg(rows.map((row) => row.totalMs));
    const p95Ms = percentile(rows.map((row) => row.totalMs), 0.95);
    const maxMs = Math.max(...rows.map((row) => row.totalMs));
    lines.push(`| ${group} | ${rows.length} | ${percent(rows.filter((row) => row.ok).length, rows.length)} | ${avgMs} | ${sec(avgMs)} | ${p95Ms} | ${sec(p95Ms)} | ${maxMs} | ${sec(maxMs)} | ${groupThresholds[group]} | ${sec(groupThresholds[group])} | ${rows.filter((row) => !row.ok).length} |`);
  }
  lines.push(
    '',
    '## 상세 결과',
    '',
    '| id | group | ok | totalMs | totalSec | assistantMs | buildChatMs | actionMs | navigationMs | draftMs | answerType | actions | route | errors |',
    '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- | --- |'
  );
  for (const row of results) {
    lines.push(`| ${row.id} | ${row.group} | ${row.ok ? 'PASS' : 'FAIL'} | ${row.totalMs} | ${sec(row.totalMs)} | ${value(row.assistantRenderedMs)} | ${value(row.buildChatMs)} | ${value(row.actionStartedMs)} | ${value(row.navigationSettledMs)} | ${value(row.draftSettledMs)} | ${row.answerType ?? '-'} | ${row.actionTypes.join(', ') || '-'} | ${escapeCell(row.route ?? '-')} | ${escapeCell(row.errors.join('; ') || '-')} |`);
  }
  lines.push(
    '',
    '## 해석',
    '',
    '- 이 리포트는 API 직접 호출 benchmark가 아니라 브라우저 입력, 자동 action, route 이동, draft 갱신까지 포함한 웹 체감 기준이다.',
    '- FAST_LOCAL_ROUTE는 프론트 shortcut 품질을, FAST_SERVER_ROUTE는 서버 route resolver 품질을 본다.',
    '- DRAFT_ACTION은 자동 장바구니 action이 실제 quote draft API와 화면 상태까지 마무리되는 시간을 본다.',
    '- LLM_FULL_COMPLEX는 fast path가 처리하지 않는 복합 의도 요청이 5초 안에 끝나는지 확인한다.'
  );
  return `${lines.join('\n')}\n`;
}

function value(input: number | undefined) {
  return input == null ? '-' : String(input);
}

function sec(ms: number) {
  return (ms / 1000).toFixed(2);
}

function avg(values: number[]) {
  if (!values.length) return 0;
  return Math.round(values.reduce((sum, value) => sum + value, 0) / values.length);
}

function percentile(values: number[], p: number) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * p) - 1)];
}

function percent(numerator: number, denominator: number) {
  if (!denominator) return '0.0%';
  return `${((numerator / denominator) * 100).toFixed(1)}%`;
}

function escapeCell(valueToEscape: string) {
  return valueToEscape.replaceAll('|', '\\|').replaceAll('\n', ' ');
}

function messageOf(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function hasOpenAiKey() {
  if (process.env.OPENAI_API_KEY?.trim()) {
    return true;
  }
  for (const envPath of [resolve(process.cwd(), '..', '..', '.env'), resolve(process.cwd(), '.env')]) {
    if (existsSync(envPath) && /^OPENAI_API_KEY=.+/m.test(readFileSync(envPath, 'utf8'))) {
      return true;
    }
  }
  return false;
}
