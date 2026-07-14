import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const apiBaseUrl = process.env.STATEFUL_QA_API_BASE_URL ?? 'http://127.0.0.1:8080';
const userEmail = process.env.STATEFUL_QA_WEB_USER_EMAIL ?? 'stateful-qa-web@example.com';
const password = process.env.STATEFUL_QA_USER_PASSWORD ?? 'passw0rd!';
const profile = 'BUILD_CHAT_54_MINI_FAST';
const replayPath = resolve(process.cwd(), '..', '..', '.qa-results', 'stateful', 'build-chat-stateful-web-replay.json');

type DraftItem = { partId: string; category?: string; quantity?: number };
type QuoteDraft = { items?: DraftItem[] };
type ReplayTurn = { message: string; expected: Record<string, unknown> };
type ReplayCase = { caseId: string; group: string; setupItems: DraftItem[]; turns: ReplayTurn[] };
type ChatResponse = {
  answerType?: string; message?: string; builds?: Array<{
    tier?: string; badges?: string[]; appliedPartCategories?: string[];
    items?: Array<{ category?: string; name?: string }>;
    toolResults?: Array<{ status?: string }>;
  }>;
  simulation?: { category?: string } | null;
  boardFocus?: { categories?: string[] } | null; clarification?: object | null;
  quickReplies?: string[]; warnings?: string[];
};
type TurnResult = {
  turn: number; message: string; status: number; response: ChatResponse | null;
  rendered: boolean; draftUnchanged: boolean; failures: string[];
};
type CaseResult = { caseId: string; group: string; verdict: 'PASS' | 'FAIL' | 'BLOCKED'; turns: TurnResult[] };

test.describe.configure({ mode: 'serial' });

test('상태형 고위험 20개 체인을 실제 웹에서 재현한다', async ({ page, request }) => {
  test.setTimeout(1_800_000);
  expect(existsSync(replayPath), `먼저 python tools/audit_build_chat_stateful.py를 실행해야 합니다: ${replayPath}`).toBeTruthy();
  const cases = JSON.parse(readFileSync(replayPath, 'utf8')) as ReplayCase[];
  expect(cases).toHaveLength(20);
  const auth = await loginOrProvision(request);
  await authenticatePage(page, auth);
  await page.route('**/api/ai/build-chat', async (route) => {
    await route.continue({ headers: { ...route.request().headers(), 'X-BuildGraph-AI-Profile': profile } });
  });
  const original = await currentDraft(request, auth.accessToken);
  const results: CaseResult[] = [];
  let p0 = false;
  try {
    for (const scenario of cases) {
      if (p0) break;
      await replaceDraft(request, auth.accessToken, scenario.setupItems);
      const prepared = await currentDraft(request, auth.accessToken);
      const expectedFingerprint = draftFingerprint(prepared);
      await page.goto('/self-quote');
      await clearAssistantSession(page);
      await page.reload();
      const turns: TurnResult[] = [];
      for (let index = 0; index < scenario.turns.length; index += 1) {
        await openAssistant(page);
        const turn = scenario.turns[index];
        const { status, body } = await submit(page, turn.message);
        const failures: string[] = [];
        if (status !== 200) failures.push(`HTTP_${status}`);
        if (!body?.message || !Array.isArray(body.builds) || !Array.isArray(body.warnings)) failures.push('SCHEMA_INVALID');
        const rendered = Boolean(body?.message) && await page.getByText(body!.message!, { exact: false }).last().isVisible().catch(() => false);
        if (!rendered) failures.push('RESPONSE_NOT_RENDERED');
        if (body?.builds?.some((build) => build.toolResults?.some((tool) => tool.status === 'FAIL'))) failures.push('TOOL_FAIL_RECOMMENDED');
        failures.push(...validateExpected(body, turn.expected));
        const after = await currentDraft(request, auth.accessToken);
        const draftUnchanged = JSON.stringify(draftFingerprint(after)) === JSON.stringify(expectedFingerprint);
        if (!draftUnchanged) {
          failures.push('DRAFT_MUTATED');
          p0 = true;
        }
        turns.push({ turn: index + 1, message: turn.message, status, response: body, rendered, draftUnchanged, failures });
        if (p0) break;
      }
      results.push({
        caseId: scenario.caseId, group: scenario.group,
        verdict: turns.some((row) => row.failures.length) ? 'FAIL' : 'PASS', turns
      });
    }
  } finally {
    await replaceDraft(request, auth.accessToken, original.items ?? []);
  }
  const paths = writeReport(results);
  const failures = results.flatMap((row) => row.turns.flatMap((turn) => turn.failures.map((failure) => `${row.caseId}#${turn.turn}: ${failure}`)));
  expect(p0, `P0가 발생했습니다. 보고서: ${paths.md}`).toBe(false);
  expect(results, `20개 결과 row가 모두 필요합니다. 보고서: ${paths.md}`).toHaveLength(20);
  if (process.env.STATEFUL_QA_STRICT === 'true') {
    expect(failures, `상태형 웹 감사 실패. 보고서: ${paths.md}`).toEqual([]);
  }
});

async function loginOrProvision(request: APIRequestContext) {
  let response = await request.post(`${apiBaseUrl}/api/auth/login`, { data: { email: userEmail, password } });
  if (!response.ok()) {
    const create = await request.post(`${apiBaseUrl}/api/users`, {
      data: {
        email: userEmail, password, name: 'Stateful QA Web', phoneNumber: '010-9900-9000',
        postalCode: '06236', addressLine1: '서울특별시 강남구 테헤란로 1', addressLine2: 'QA Web',
        termsAccepted: true, marketingAccepted: false
      }
    });
    expect([200, 201, 409]).toContain(create.status());
    response = await request.post(`${apiBaseUrl}/api/auth/login`, { data: { email: userEmail, password } });
  }
  expect(response.ok(), await response.text()).toBeTruthy();
  return await response.json() as { accessToken: string; refreshToken: string; user: object };
}

async function authenticatePage(page: Page, auth: Awaited<ReturnType<typeof loginOrProvision>>) {
  await page.goto('/login');
  await page.evaluate((value) => {
    localStorage.setItem('buildgraph.token', value.accessToken);
    localStorage.setItem('buildgraph.refreshToken', value.refreshToken);
    localStorage.setItem('buildgraph.authUser', JSON.stringify(value.user));
    localStorage.setItem('buildgraph.homeLoginChoice.dismissed', 'true');
  }, auth);
}

async function currentDraft(request: APIRequestContext, token: string) {
  const response = await request.get(`${apiBaseUrl}/api/quote-drafts/current`, { headers: { Authorization: `Bearer ${token}` } });
  expect(response.ok(), await response.text()).toBeTruthy();
  return await response.json() as QuoteDraft;
}

async function replaceDraft(request: APIRequestContext, token: string, items: DraftItem[]) {
  const current = await currentDraft(request, token);
  for (const item of current.items ?? []) {
    const response = await request.delete(`${apiBaseUrl}/api/quote-drafts/current/items/${item.partId}`, {
      headers: { Authorization: `Bearer ${token}` }
    });
    expect(response.ok(), await response.text()).toBeTruthy();
  }
  for (const item of items) {
    const response = await request.put(`${apiBaseUrl}/api/quote-drafts/current/items/${item.partId}`, {
      headers: { Authorization: `Bearer ${token}` }, data: { quantity: item.quantity ?? 1 }
    });
    expect(response.ok(), await response.text()).toBeTruthy();
  }
}

async function clearAssistantSession(page: Page) {
  await page.evaluate(() => {
    for (const key of Object.keys(sessionStorage)) if (key.startsWith('buildgraph.ai.')) sessionStorage.removeItem(key);
  });
}

async function openAssistant(page: Page) {
  const panel = page.getByTestId('ai-chatbot-panel');
  if (!await panel.isVisible().catch(() => false)) {
    await page.evaluate(() => window.dispatchEvent(new CustomEvent('buildgraph.aiAssistant.open', { detail: { placement: 'side' } })));
  }
  await expect(panel).toBeVisible();
}

async function submit(page: Page, prompt: string) {
  const responsePromise = page.waitForResponse((response) => (
    response.url().includes('/api/ai/build-chat') && response.request().method() === 'POST'
  ), { timeout: 180_000 });
  await page.getByLabel('AI 챗봇에게 PC 사양 질문').fill(prompt);
  await page.getByRole('button', { name: '질문 보내기' }).click();
  const response = await responsePromise;
  const text = await response.text();
  let body: ChatResponse | null = null;
  try { body = text ? JSON.parse(text) as ChatResponse : null; } catch { body = null; }
  return { status: response.status(), body };
}

function draftFingerprint(draft: QuoteDraft) {
  return [...(draft.items ?? [])]
    .map((item) => `${item.category}:${item.partId}:${item.quantity ?? 1}`)
    .sort();
}

const categoryAliases: Record<string, string[]> = {
  CPU: ['cpu', '프로세서', '라이젠', 'ryzen', '인텔', '9950', '9700', '285k'],
  MOTHERBOARD: ['메인보드', '마더보드', '보드', 'b860', 'b850', 'x870', 'z890'],
  RAM: ['ram', '램', '메모리', 'ddr'], GPU: ['gpu', '그래픽', '글카', 'rtx', '지포스'],
  STORAGE: ['ssd', 'nvme', '저장', 'm.2', 'pcie'], PSU: ['psu', '파워', '전원', '와트'],
  CASE: ['케이스', 'case', '타워'], COOLER: ['쿨러', '공랭', '수랭', 'aio', '라디에이터']
};

function validateExpected(response: ChatResponse | null, expected: Record<string, unknown>) {
  if (!response) return ['SCHEMA_INVALID'];
  const failures: string[] = [];
  const expectedCategory = typeof expected.expectedCategory === 'string' ? expected.expectedCategory : undefined;
  if (expected.candidateAudit === true && expectedCategory) {
    const structuredCategories = new Set<string>();
    if (response.simulation?.category) structuredCategories.add(response.simulation.category);
    for (const category of response.boardFocus?.categories ?? []) structuredCategories.add(category);
    for (const build of response.builds ?? []) {
      for (const category of build.appliedPartCategories ?? []) structuredCategories.add(category);
      for (const item of build.items ?? []) if (item.category) structuredCategories.add(item.category);
    }
    const text = [response.message ?? '', ...(response.quickReplies ?? []),
      ...(response.builds ?? []).flatMap((build) => (build.items ?? []).map((item) => `${item.category ?? ''} ${item.name ?? ''}`))]
      .join(' ').toLowerCase().replaceAll(' ', '');
    const aliases = categoryAliases[expectedCategory] ?? [expectedCategory.toLowerCase()];
    const textMatch = aliases.some((alias) => text.includes(alias.toLowerCase().replaceAll(' ', '')));
    if (!structuredCategories.has(expectedCategory) && !textMatch) failures.push('CATEGORY_MISMATCH');
  }
  const outcome = String(expected.outcome ?? '');
  const previews = (response.builds ?? []).filter((build) => build.tier === 'draft-edit' || build.badges?.includes('DRAFT_EDIT_PREVIEW'));
  if (outcome === 'PREVIEW' && !previews.length) failures.push('EXPECTED_PREVIEW_MISSING');
  if (outcome === 'SIMULATION' && !response.simulation) failures.push('EXPECTED_SIMULATION_MISSING');
  if (outcome === 'BOARD_FOCUS' && !response.boardFocus) failures.push('BOARD_FOCUS_MISSING');
  return failures;
}

function writeReport(results: CaseResult[]) {
  const date = new Date().toISOString().slice(0, 10).replaceAll('-', '');
  const directory = resolve(process.cwd(), '..', '..', 'docs', 'reports');
  mkdirSync(directory, { recursive: true });
  const jsonPath = resolve(directory, `build-chat-stateful-web-audit-${date}.json`);
  const mdPath = resolve(directory, `build-chat-stateful-web-audit-${date}.md`);
  writeFileSync(jsonPath, JSON.stringify({ generatedAt: new Date().toISOString(), profile, results }, null, 2), 'utf8');
  const lines = [
    '# Build Chat 상태형 웹 재현 감사', '', `- 모델 profile: \`${profile}\``,
    `- 결과: PASS ${results.filter((row) => row.verdict === 'PASS').length} / FAIL ${results.filter((row) => row.verdict === 'FAIL').length}`, '',
    '| case | 그룹 | 결과 | 턴 | 오류 |', '|---|---|---|---:|---|',
    ...results.map((row) => `| ${row.caseId} | ${row.group} | ${row.verdict} | ${row.turns.length} | ${row.turns.flatMap((turn) => turn.failures).join(', ') || '-'} |`)
  ];
  writeFileSync(mdPath, `${lines.join('\n')}\n`, 'utf8');
  return { md: mdPath, json: jsonPath };
}
