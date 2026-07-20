import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const apiBaseUrl = process.env.STATEFUL_QA_API_BASE_URL ?? 'http://127.0.0.1:8080';
const email = process.env.STATEFUL_QA_SURFACE_USER_EMAIL ?? 'stateful-surface-web@example.com';
const password = process.env.STATEFUL_QA_USER_PASSWORD ?? 'passw0rd!';
const corpusPath = resolve(process.cwd(), '..', '..', 'tools', 'user_surface_stateful_audit_cases.json');
const demoReplayPath = resolve(process.cwd(), '..', '..', '.qa-results', 'stateful', 'demo-journey-stateful-web-replay.json');
const progressPath = resolve(process.cwd(), '..', '..', '.qa-results', 'stateful', 'user-surface-stateful-progress.json');
const categories = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER'];

type DraftItem = { partId: string; category?: string; quantity?: number };
type Draft = { items?: DraftItem[] };
type SurfaceCase = {
  id: string; group: string; routes: string[]; interactionMode: string; viewport: 'desktop' | 'mobile';
};
type SurfaceResult = {
  caseId: string; group: string; routes: string[]; mode: string; viewport: string;
  verdict: 'PASS' | 'FAIL'; failures: string[]; consoleErrors: string[]; apiErrors: string[];
  layoutEvidence: string[];
};

test.describe.configure({ mode: 'serial' });

test('사용자 화면 100개를 실제 브라우저에서 상태형 감사한다', async ({ page, request }) => {
  test.setTimeout(1_800_000);
  page.setDefaultNavigationTimeout(15_000);
  const cases = JSON.parse(readFileSync(corpusPath, 'utf8')) as SurfaceCase[];
  expect(cases).toHaveLength(100);
  const auth = await loginOrProvision(request);
  const original = await currentDraft(request, auth.accessToken);
  const setupItems = loadVerifiedSetupItems();
  const activePartId = await firstActivePartId(request, auth.accessToken);
  const results: SurfaceResult[] = [];
  let activeResult: SurfaceResult | null = null;

  page.on('console', (message) => {
    if (message.type() === 'error' && activeResult) activeResult.consoleErrors.push(message.text());
  });
  page.on('pageerror', (error) => {
    if (activeResult) activeResult.consoleErrors.push(`PAGEERROR:${error.message}`);
  });
  page.on('response', (response) => {
    if (!activeResult || response.status() < 400) return;
    const requestType = response.request().resourceType();
    if (requestType === 'xhr' || requestType === 'fetch') {
      activeResult.apiErrors.push(`${response.status()} ${new URL(response.url()).pathname}`);
    }
  });

  await authenticatePage(page, auth);
  try {
    for (let index = 0; index < cases.length; index += 1) {
      const scenario = cases[index];
      const result: SurfaceResult = {
        caseId: scenario.id, group: scenario.group, routes: scenario.routes, mode: scenario.interactionMode,
        viewport: scenario.viewport, verdict: 'PASS', failures: [], consoleErrors: [], apiErrors: [], layoutEvidence: []
      };
      activeResult = result;
      try {
        await page.setViewportSize(scenario.viewport === 'mobile' ? { width: 390, height: 844 } : { width: 1440, height: 1024 });
        if (scenario.group === 'AUTH_PROFILE_REDIRECT') {
          await auditAuthRedirect(page, auth, result);
        } else {
          await ensureAuthenticatedPage(page, auth);
          const needsDraft = ['SELF_QUOTE_TOOL', 'PART_DETAIL_PRICE', 'CHECKOUT_ASSEMBLY'].includes(scenario.group);
          const emptyState = scenario.interactionMode === 'empty-state';
          await replaceDraft(request, auth.accessToken, needsDraft && !emptyState ? setupItems : []);
          await auditSurface(page, request, auth.accessToken, scenario, result, activePartId);
        }
      } catch (error) {
        result.failures.push(`FLOW_EXCEPTION:${error instanceof Error ? error.message : String(error)}`);
      }
      result.apiErrors = unique(result.apiErrors).filter((value) => !isExpectedEmptyStateResponse(value));
      result.consoleErrors = unique(result.consoleErrors).filter((value) =>
        result.apiErrors.length > 0 || !value.includes('Failed to load resource: the server responded with a status of 404')
      );
      if (result.consoleErrors.length) result.failures.push('UNHANDLED_CONSOLE_ERROR');
      if (result.apiErrors.length) result.failures.push('API_ERROR_RESPONSE');
      if (result.apiErrors.includes('404 /api/ai/as-chat')) result.failures.push('AS_CHAT_DEFAULT_TICKET_NOT_FOUND');
      result.failures = unique(result.failures);
      result.verdict = result.failures.length ? 'FAIL' : 'PASS';
      results.push(result);
      writeProgress(results, cases.length);
      console.log(`[${results.length}/${cases.length}] ${scenario.id} -> ${result.verdict}`);
    }
  } finally {
    activeResult = null;
    await replaceDraft(request, auth.accessToken, original.items ?? []);
  }
  const paths = writeReport(results);
  expect(results, `100개 결과 row가 필요합니다. ${paths.md}`).toHaveLength(100);
  if (process.env.STATEFUL_QA_STRICT === 'true') {
    expect(results.filter((row) => row.verdict === 'FAIL'), `사용자 화면 감사 실패. ${paths.md}`).toEqual([]);
  }
});

async function auditAuthRedirect(page: Page, auth: Auth, result: SurfaceResult) {
  await page.goto('/login');
  await page.evaluate(() => localStorage.clear());
  await page.goto('/my/profile');
  await page.waitForURL(/\/login(?:\?|$)/, { timeout: 10_000 }).catch(() => result.failures.push('AUTH_REDIRECT_MISSING'));
  await authenticatePage(page, auth);
  await page.goto('/my/profile');
  await applyMode(page, '/my/profile', result.mode);
  if (result.mode === 'back-forward' && new URL(page.url()).pathname !== '/my/profile') result.failures.push('BACK_FORWARD_ROUTE_MISMATCH');
  if (!page.url().includes('/my/profile')) result.failures.push('AUTH_RETURN_PATH_MISSING');
  await assertVisibleAndContained(page, result);
}

async function auditSurface(
  page: Page,
  request: APIRequestContext,
  token: string,
  scenario: SurfaceCase,
  result: SurfaceResult,
  activePartId: string
) {
  const routes = scenario.routes.map((route) => route.replace('{activePartId}', activePartId));
  for (const route of routes) {
    await page.goto(route);
    await applyMode(page, route, scenario.interactionMode);
    if (scenario.interactionMode === 'back-forward' && new URL(page.url()).pathname !== new URL(route, 'http://qa.local').pathname) {
      result.failures.push('BACK_FORWARD_ROUTE_MISMATCH');
    }
    await assertVisibleAndContained(page, result);
  }
  if (scenario.group === 'HOME') {
    if (!await eventuallyVisible(page.locator('a[aria-label^="인기 부품"]'))) result.failures.push('HOME_RECOMMENDATIONS_MISSING');
    await openAssistant(page, result);
  } else if (scenario.group === 'SELF_QUOTE_TOOL') {
    const category = categories[(Number(scenario.id.slice(-3)) - 21) % categories.length];
    const button = page.getByTestId(`checklist-${category}`);
    if (!await eventuallyVisible(button)) result.failures.push('CATEGORY_ENTRY_MISSING');
    else {
      await button.click();
      if (scenario.interactionMode === 'double-submit') await button.click();
      if (!await eventuallyVisible(page.getByTestId(`checklist-candidates-${category}`))) result.failures.push('CATEGORY_PANEL_MISSING');
    }
    if (!await eventuallyVisible(page.getByTestId('slot-board-widget'))) result.failures.push('TOOL_PANEL_MISSING');
  } else if (scenario.group === 'PART_DETAIL_PRICE') {
    const main = page.getByRole('main');
    if (!await eventuallyVisible(main.getByText(/원/))) result.failures.push('PART_PRICE_MISSING');
    if (!await eventuallyVisible(main.getByText(/스펙|상품 정보|구매처/))) result.failures.push('PART_SPEC_MISSING');
  } else if (scenario.group === 'CHECKOUT_ASSEMBLY') {
    await page.goto('/checkout');
    const checkout = page.getByRole('main');
    await eventuallyVisible(checkout, 10_000);
    if (scenario.interactionMode === 'empty-state') {
      if (!await eventuallyVisible(checkout.getByText('조립할 부품이 없습니다'))) result.failures.push('CHECKOUT_EMPTY_STATE_MISSING');
    } else if (!await eventuallyVisible(checkout.getByRole('button', { name: '기사 제안 요청하기' }))) {
      result.failures.push('CHECKOUT_FORM_MISSING');
    }
    await page.goto('/my/assembly-requests');
    if (!await eventuallyVisible(page.getByRole('main').getByText(/내 조립 요청|조립 요청 이력/))) result.failures.push('ASSEMBLY_HISTORY_ENTRY_MISSING');
  } else if (scenario.group === 'TECHNICIAN_PORTAL') {
    const text = await page.getByRole('main').innerText().catch(() => '');
    if (!/기사 포털|기사 프로필이 없습니다|기사 신청/.test(text)) result.failures.push('TECHNICIAN_STATE_MISSING');
    if (await page.getByTestId('ai-chatbot-panel').isVisible().catch(() => false)) result.failures.push('SHOPPING_AI_VISIBLE_IN_TECHNICIAN');
  } else if (scenario.group === 'MY_QUOTES_HISTORY') {
    if (!await eventuallyVisible(page.getByRole('main').getByText(/내 견적함/))) result.failures.push('QUOTE_HISTORY_MISSING');
    if (!await eventuallyVisible(page.getByRole('main').getByText(/조립 요청|기사 제안/))) result.failures.push('ASSEMBLY_ENTRY_FROM_QUOTES_MISSING');
  } else if (scenario.group === 'SUPPORT_AS') {
    await page.goto('/support/new');
    if (!await eventuallyVisible(page.getByText(/PCAgent 다운로드|PC Agent 다운로드/))) result.failures.push('AGENT_DOWNLOAD_PATH_MISSING');
    await page.goto('/support/ai-chat');
    if (!await eventuallyVisible(page.getByRole('main').getByText('AS AI 챗봇'))) result.failures.push('SUPPORT_ENTRY_MISSING');
  } else if (scenario.group === 'GLOBAL_AI_NAVIGATION') {
    await page.goto('/');
    await openAssistant(page, result);
    let apiCalls = 0;
    const listener = (response: import('@playwright/test').Response) => {
      if (response.url().includes('/api/ai/build-chat')) apiCalls += 1;
    };
    page.on('response', listener);
    await page.getByLabel('AI 챗봇에게 PC 사양 질문').fill('GPU 보여줘');
    await page.getByRole('button', { name: '질문 보내기' }).click();
    await page.waitForURL(/\/self-quote\?category=GPU/).catch(() => result.failures.push('FAST_ROUTE_NAVIGATION_FAILED'));
    page.off('response', listener);
    if (apiCalls !== 0) result.failures.push('FAST_ROUTE_CALLED_BUILD_CHAT');
  } else if (scenario.group === 'MOBILE_ERROR_ACCESS') {
    await page.goto('/parts/not-a-real-part');
    await assertVisibleAndContained(page, result);
    if (!await eventuallyVisible(page.getByRole('main').getByText(/찾을 수|실패|다시|부품/), 10_000)) result.failures.push('ERROR_RECOVERY_MISSING');
    await page.goto('/self-quote');
    if (await hasHorizontalOverflow(page)) {
      result.failures.push('MOBILE_HORIZONTAL_OVERFLOW');
      result.layoutEvidence.push(...await horizontalOverflowEvidence(page));
    }
  }
  const persisted = await currentDraft(request, token);
  if (!Array.isArray(persisted.items)) result.failures.push('DRAFT_READ_FAILED');
}

async function applyMode(page: Page, route: string, mode: string) {
  if (mode === 'reload') {
    await page.reload();
  } else if (mode === 'back-forward') {
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await page.goto(route, { waitUntil: 'domcontentloaded' });
    await page.evaluate(() => history.back());
    await page.waitForTimeout(300);
    await page.evaluate(() => history.forward());
    await page.waitForTimeout(500);
  }
}

async function assertVisibleAndContained(page: Page, result: SurfaceResult) {
  const main = page.getByRole('main');
  if (!await eventuallyVisible(main, 10_000)) result.failures.push('MAIN_NOT_VISIBLE');
  const text = (await main.innerText().catch(() => '')).trim();
  if (!text) result.failures.push('BLANK_PAGE');
  if (await hasHorizontalOverflow(page)) {
    result.failures.push('HORIZONTAL_OVERFLOW');
    result.layoutEvidence.push(...await horizontalOverflowEvidence(page));
  }
}

async function hasHorizontalOverflow(page: Page) {
  return await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 2);
}

async function horizontalOverflowEvidence(page: Page) {
  return await page.evaluate(() => {
    const viewportWidth = document.documentElement.clientWidth;
    return [...document.querySelectorAll<HTMLElement>('body *')]
      .map((element) => ({ element, rect: element.getBoundingClientRect() }))
      .filter(({ rect }) => rect.width > 0 && (rect.right > viewportWidth + 2 || rect.left < -2))
      .sort((left, right) => (right.rect.right - viewportWidth) - (left.rect.right - viewportWidth))
      .slice(0, 5)
      .map(({ element, rect }) => {
        const identity = element.dataset.testid
          ? `[data-testid=${element.dataset.testid}]`
          : `${element.tagName.toLowerCase()}${element.id ? `#${element.id}` : ''}.${[...element.classList].slice(0, 3).join('.')}`;
        return `${location.pathname}: ${identity} left=${Math.round(rect.left)} right=${Math.round(rect.right)} viewport=${viewportWidth}`;
      });
  });
}

async function openAssistant(page: Page, result: SurfaceResult) {
  const panel = page.getByTestId('ai-chatbot-panel');
  if (!await panel.isVisible().catch(() => false)) {
    await page.evaluate(() => window.dispatchEvent(new CustomEvent('buildgraph.aiAssistant.open', { detail: { placement: 'side' } })));
  }
  if (!await eventuallyVisible(panel)) result.failures.push('GLOBAL_ASSISTANT_MISSING');
}

type Auth = { accessToken: string; refreshToken: string; user: object };

async function loginOrProvision(request: APIRequestContext): Promise<Auth> {
  let response = await request.post(`${apiBaseUrl}/api/auth/login`, { data: { email, password } });
  if (!response.ok()) {
    await request.post(`${apiBaseUrl}/api/users`, { data: {
      email, password, name: 'Stateful Surface Web', phoneNumber: '010-9888-7788', postalCode: '06236',
      addressLine1: '서울특별시 강남구 테헤란로 1', addressLine2: 'QA', termsAccepted: true, marketingAccepted: false
    } });
    response = await request.post(`${apiBaseUrl}/api/auth/login`, { data: { email, password } });
  }
  expect(response.ok(), await response.text()).toBeTruthy();
  return await response.json() as Auth;
}

async function authenticatePage(page: Page, auth: Auth) {
  await page.goto('/login');
  await page.evaluate((value) => {
    localStorage.setItem('buildgraph.token', value.accessToken);
    localStorage.setItem('buildgraph.refreshToken', value.refreshToken);
    localStorage.setItem('buildgraph.authUser', JSON.stringify(value.user));
    localStorage.setItem('buildgraph.homeLoginChoice.dismissed', 'true');
  }, auth);
}

async function ensureAuthenticatedPage(page: Page, auth: Auth) {
  const hasToken = await page.evaluate(() => Boolean(localStorage.getItem('buildgraph.token'))).catch(() => false);
  if (!hasToken) await authenticatePage(page, auth);
}

function authHeaders(token: string) { return { Authorization: `Bearer ${token}` }; }

async function currentDraft(request: APIRequestContext, token: string) {
  const response = await request.get(`${apiBaseUrl}/api/quote-drafts/current`, { headers: authHeaders(token) });
  expect(response.ok(), await response.text()).toBeTruthy();
  return await response.json() as Draft;
}

async function replaceDraft(request: APIRequestContext, token: string, items: DraftItem[]) {
  const current = await currentDraft(request, token);
  for (const item of current.items ?? []) {
    const response = await request.delete(`${apiBaseUrl}/api/quote-drafts/current/items/${item.partId}`, { headers: authHeaders(token) });
    if (!response.ok()) throw new Error(`draft delete ${response.status()}`);
  }
  for (const item of items) {
    const response = await request.put(`${apiBaseUrl}/api/quote-drafts/current/items/${item.partId}`, {
      headers: authHeaders(token), data: { quantity: item.quantity ?? 1 }
    });
    if (!response.ok()) throw new Error(`draft put ${response.status()}`);
  }
}

function loadVerifiedSetupItems(): DraftItem[] {
  try {
    const rows = JSON.parse(readFileSync(demoReplayPath, 'utf8')) as Array<{ setupItems?: DraftItem[] }>;
    return rows.find((row) => row.setupItems?.length)?.setupItems ?? [];
  } catch {
    return [];
  }
}

async function firstActivePartId(request: APIRequestContext, token: string) {
  const response = await request.get(`${apiBaseUrl}/api/parts?status=ACTIVE&page=0&size=1`, { headers: authHeaders(token) });
  expect(response.ok(), await response.text()).toBeTruthy();
  const body = await response.json() as { items?: Array<{ id: string }> };
  expect(body.items?.[0]?.id).toBeTruthy();
  return body.items![0].id;
}

function unique(values: string[]) { return [...new Set(values)]; }

async function eventuallyVisible(locator: Locator, timeout = 8_000) {
  return locator.first().waitFor({ state: 'visible', timeout }).then(() => true).catch(() => false);
}

function isExpectedEmptyStateResponse(value: string) {
  return value === '404 /api/technician/profile'
    || value === '404 /api/parts/not-a-real-part'
    || value === '404 /api/parts/not-a-real-part/price-history';
}

function writeProgress(results: SurfaceResult[], total: number) {
  mkdirSync(resolve(progressPath, '..'), { recursive: true });
  writeFileSync(progressPath, JSON.stringify({
    completed: results.length,
    total,
    lastCaseId: results.at(-1)?.caseId,
    verdicts: {
      PASS: results.filter((row) => row.verdict === 'PASS').length,
      FAIL: results.filter((row) => row.verdict === 'FAIL').length
    },
    updatedAt: new Date().toISOString(),
    results
  }, null, 2), 'utf8');
}

function writeReport(results: SurfaceResult[]) {
  const date = new Date().toISOString().slice(0, 10).replaceAll('-', '');
  const directory = resolve(process.cwd(), '..', '..', 'docs', 'reports');
  mkdirSync(directory, { recursive: true });
  const jsonPath = resolve(directory, `user-surface-stateful-audit-${date}-phase3.json`);
  const mdPath = resolve(directory, `user-surface-stateful-audit-${date}-phase3.md`);
  const groupCounts = new Map<string, { pass: number; fail: number }>();
  for (const row of results) {
    const value = groupCounts.get(row.group) ?? { pass: 0, fail: 0 };
    if (row.verdict === 'PASS') value.pass += 1; else value.fail += 1;
    groupCounts.set(row.group, value);
  }
  writeFileSync(jsonPath, JSON.stringify({ generatedAt: new Date().toISOString(), results }, null, 2), 'utf8');
  const lines = [
    '# 사용자 화면 전체 상태 전이 3차 감사', '',
    `- 실행 결과: PASS ${results.filter((row) => row.verdict === 'PASS').length} / FAIL ${results.filter((row) => row.verdict === 'FAIL').length}`, '',
    '## 그룹별 결과', '', '| 그룹 | PASS | FAIL |', '|---|---:|---:|',
    ...[...groupCounts].map(([group, value]) => `| ${group} | ${value.pass} | ${value.fail} |`),
    '', '## 실패 사례', '', '| case | 그룹 | 모드 | 오류 | console/API 증거 |', '|---|---|---|---|---|',
    ...results.filter((row) => row.verdict === 'FAIL').map((row) =>
      `| ${row.caseId} | ${row.group} | ${row.mode}/${row.viewport} | ${row.failures.join(', ')} | ${[...row.consoleErrors, ...row.apiErrors, ...row.layoutEvidence].join(' / ') || '-'} |`
    ),
    '', '## 판정 기준', '',
    '- 100개 모두 실제 Chromium에서 라우트를 열고 main 가시성, 빈 화면, console/API 오류, 가로 넘침을 확인했다.',
    '- 동일 화면의 normal/reload/back-forward/double-submit/empty-state 변형은 같은 독립 원인의 사례로 묶어 해석한다.',
    '- 원본 라우트·console·API 오류는 JSON 보고서에 남겼다.'
  ];
  writeFileSync(mdPath, `${lines.join('\n')}\n`, 'utf8');
  return { md: mdPath, json: jsonPath };
}
