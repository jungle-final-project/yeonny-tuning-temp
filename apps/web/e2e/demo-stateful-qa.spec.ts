import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const apiBaseUrl = process.env.STATEFUL_QA_API_BASE_URL ?? 'http://127.0.0.1:8080';
const userEmail = process.env.STATEFUL_QA_DEMO_WEB_USER_EMAIL ?? 'stateful-demo-web@example.com';
const password = process.env.STATEFUL_QA_USER_PASSWORD ?? 'passw0rd!';
const adminEmail = process.env.STATEFUL_QA_ADMIN_EMAIL ?? 'admin@example.com';
const profile = 'BUILD_CHAT_54_MINI_FAST';
const replayPath = resolve(process.cwd(), '..', '..', '.qa-results', 'stateful', 'demo-journey-stateful-web-replay.json');

type DraftItem = { partId: string; category?: string; quantity?: number };
type Draft = { items?: DraftItem[]; totalPrice?: number };
type Scenario = {
  id: string; group: string; journeyVariant?: string;
  setupItems: DraftItem[]; steps: Array<{ kind: string; message?: string }>;
};
type CaseResult = { caseId: string; group: string; verdict: 'PASS' | 'FAIL' | 'BLOCKED'; failures: string[]; evidence: string[] };

test.describe.configure({ mode: 'serial' });

test('4분 데모 대표 20개를 실제 웹 상태 전이로 재현한다', async ({ page, request }) => {
  test.setTimeout(1_800_000);
  expect(existsSync(replayPath), `먼저 phase-2 API runner를 실행해야 합니다: ${replayPath}`).toBeTruthy();
  const scenarios = JSON.parse(readFileSync(replayPath, 'utf8')) as Scenario[];
  expect(scenarios).toHaveLength(20);
  const user = await loginOrProvision(request, userEmail, true);
  const admin = await loginOrProvision(request, adminEmail, false);
  await authenticatePage(page, user);
  await page.route('**/api/ai/build-chat', async (route) => {
    await route.continue({ headers: { ...route.request().headers(), 'X-BuildGraph-AI-Profile': profile } });
  });
  const original = await currentDraft(request, user.accessToken);
  const results: CaseResult[] = [];
  let p0 = false;
  try {
    for (const scenario of scenarios) {
      if (p0) break;
      const failures: string[] = [];
      const evidence: string[] = [];
      let cleanup: (() => Promise<void>) | undefined;
      try {
        if (scenario.group === 'DEMO_REQUIREMENT_RECOMMEND') {
          await replaceDraft(request, user.accessToken, []);
          await page.goto('/self-quote');
          const body = await submitAssistant(page, scenario.steps.find((step) => step.kind === 'BUILD_CHAT')?.message ?? '200만원으로 QHD 게임용 PC 추천해줘');
          if (!body.builds?.length) failures.push('RECOMMENDATION_NOT_RENDERED');
          if (body.builds?.some((build) => build.toolResults?.some((tool) => tool.status === 'FAIL'))) failures.push('TOOL_FAIL_RECOMMENDED');
          if (!await page.getByText(body.message ?? '', { exact: false }).last().isVisible().catch(() => false)) failures.push('RESPONSE_NOT_RENDERED');
          evidence.push(`builds=${body.builds?.length ?? 0}`);
        } else if (scenario.group === 'DEMO_GPU_DOWNGRADE_RESTORE') {
          await replaceDraft(request, user.accessToken, scenario.setupItems);
          const before = fingerprint(await currentDraft(request, user.accessToken));
          await page.goto('/self-quote');
          const body = await submitAssistant(page, scenario.steps.find((step) => step.kind === 'BUILD_CHAT')?.message ?? 'GPU를 더 저렴한 제품으로 바꿔줘');
          const previews = body.builds?.filter((build) => build.tier === 'draft-edit' || build.badges?.includes('DRAFT_EDIT_PREVIEW')) ?? [];
          if (!previews.length) failures.push('GPU_PREVIEW_MISSING');
          const simulationMessage = scenario.steps.find((step) => step.kind === 'SIMULATE')?.message;
          if (simulationMessage) {
            const simulationBody = await submitAssistant(page, simulationMessage);
            if (!simulationBody.simulation) failures.push('SIMULATION_MISSING');
          }
          if (JSON.stringify(before) !== JSON.stringify(fingerprint(await currentDraft(request, user.accessToken)))) {
            failures.push('DRAFT_MUTATED_BY_CHAT');
            p0 = true;
          }
          evidence.push(`previews=${previews.length}`);
        } else if (scenario.group === 'DEMO_ASSEMBLY_MATCH') {
          await replaceDraft(request, user.accessToken, scenario.setupItems);
          const created = await createAssemblyRequest(request, user.accessToken, scenario.id);
          cleanup = async () => { await cancelAssemblyRequest(request, user.accessToken, created.id); };
          await page.goto(`/checkout/offers/${created.id}`);
          await expect(page.getByRole('heading', { name: /기사 제안/ })).toBeVisible();
          const choose = page.getByRole('button', { name: '이 기사 선택' }).first();
          if (!await choose.isVisible().catch(() => false)) {
            failures.push('VISIBLE_OFFER_MISSING');
          } else {
            await choose.click();
            await page.getByRole('button', { name: '선택한 제안 승인' }).click();
            await expect(page).toHaveURL(new RegExp(`/checkout/payment/${created.id}`));
            await page.getByRole('button', { name: '가상 결제 완료' }).click();
            await expect(page).toHaveURL(new RegExp(`/checkout/complete/${created.id}`));
          }
          evidence.push(`request=${created.id}`);
        } else if (scenario.group === 'DEMO_DIAGNOSIS_CONSENT') {
          await page.goto('/');
          const body = await submitAssistant(page, scenario.steps.find((step) => step.kind === 'AS_CHAT')?.message ?? '게임 중 검은 화면');
          if (!body.supportGuidance) failures.push('SUPPORT_GUIDANCE_MISSING');
          if (!await page.getByTestId('ai-download-pc-agent').last().isVisible().catch(() => false)) failures.push('AGENT_DOWNLOAD_ENTRY_MISSING');
          evidence.push(`symptom=${body.supportGuidance?.symptomCategory ?? 'missing'}`);
        } else if (scenario.group === 'DEMO_REMOTE_SUPPORT') {
          await resetActiveSupportTicket(request, user.accessToken, admin.accessToken);
          const ticket = await createSupportTicket(request, user.accessToken, scenario.id);
          cleanup = async () => { await closeSupportTicket(request, admin.accessToken, ticket.id); };
          await page.goto(`/support/${ticket.id}`);
          await expect(page.getByRole('main')).toContainText('담당자 확인 자료');
          const remoteButton = page.getByRole('button', { name: '원격지원 요청' });
          if (!await remoteButton.isVisible().catch(() => false)) {
            failures.push('REMOTE_REQUEST_ENTRY_MISSING');
          } else {
            await remoteButton.click();
            await expect(page.getByRole('main')).toContainText('원격지원 상태: 신청됨');
          }
          const link = `https://support.example.test/session/${ticket.id}`;
          const patch = await request.patch(`${apiBaseUrl}/api/admin/as-tickets/${ticket.id}`, {
            headers: authHeaders(admin.accessToken),
            data: {
              reviewStatus: 'APPROVED', supportDecision: 'REMOTE_POSSIBLE', riskLevel: 'MEDIUM',
              diagnosticAccuracy: 'ACCURATE', remoteSupportLink: link, adminNote: '그래픽 드라이버 재설치 준비'
            }
          });
          if (!patch.ok()) failures.push(`ADMIN_REMOTE_PATCH_${patch.status()}`);
          await page.reload();
          await expect(page.getByText(link, { exact: false }).first()).toBeVisible({ timeout: 20_000 }).catch(() => {
            failures.push('REMOTE_LINK_NOT_RENDERED');
          });
          evidence.push(`ticket=${ticket.id}`);
        }
      } catch (error) {
        failures.push(`WEB_FLOW_EXCEPTION:${error instanceof Error ? error.message : String(error)}`);
      } finally {
        if (cleanup) await cleanup().catch((error) => failures.push(`CLEANUP_FAILED:${String(error)}`));
        await replaceDraft(request, user.accessToken, original.items ?? []).catch((error) => {
          failures.push(`DRAFT_RESTORE_FAILED:${String(error)}`);
          p0 = true;
        });
      }
      results.push({ caseId: scenario.id, group: scenario.group, verdict: failures.length ? 'FAIL' : 'PASS', failures, evidence });
    }
  } finally {
    await replaceDraft(request, user.accessToken, original.items ?? []);
  }
  const paths = writeReport(results);
  expect(p0, `P0 상태 변경 오류가 발생했습니다. ${paths.md}`).toBe(false);
  expect(results, `20개 결과 row가 필요합니다. ${paths.md}`).toHaveLength(20);
  if (process.env.STATEFUL_QA_STRICT === 'true') {
    expect(results.filter((row) => row.verdict !== 'PASS'), `상태형 데모 웹 감사 실패. ${paths.md}`).toEqual([]);
  }
});

type ChatBody = {
  message?: string;
  builds?: Array<{ tier?: string; badges?: string[]; toolResults?: Array<{ status?: string }> }>;
  simulation?: object | null;
  supportGuidance?: { symptomCategory?: string } | null;
};

async function submitAssistant(page: Page, message: string): Promise<ChatBody> {
  const panel = page.getByTestId('ai-chatbot-panel');
  if (!await panel.isVisible().catch(() => false)) {
    await page.evaluate(() => window.dispatchEvent(new CustomEvent('buildgraph.aiAssistant.open', { detail: { placement: 'side' } })));
  }
  await expect(panel).toBeVisible();
  const responsePromise = page.waitForResponse((response) => response.url().includes('/api/ai/build-chat') && response.request().method() === 'POST', { timeout: 180_000 });
  await page.getByLabel('AI 챗봇에게 PC 사양 질문').fill(message);
  await page.getByRole('button', { name: '질문 보내기' }).click();
  const response = await responsePromise;
  return await response.json() as ChatBody;
}

async function loginOrProvision(request: APIRequestContext, email: string, provision: boolean) {
  let response = await request.post(`${apiBaseUrl}/api/auth/login`, { data: { email, password } });
  if (!response.ok() && provision) {
    const suffix = String(Date.now()).slice(-7);
    await request.post(`${apiBaseUrl}/api/users`, { data: {
      email, password, name: 'Stateful Demo Web', phoneNumber: `010-${suffix.slice(0, 3)}-${suffix.slice(3)}`,
      postalCode: '06236', addressLine1: '서울특별시 강남구 테헤란로 1', addressLine2: 'QA',
      termsAccepted: true, marketingAccepted: false
    } });
    response = await request.post(`${apiBaseUrl}/api/auth/login`, { data: { email, password } });
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

function fingerprint(draft: Draft) {
  return [...(draft.items ?? [])].map((item) => `${item.category}:${item.partId}:${item.quantity ?? 1}`).sort();
}

async function createAssemblyRequest(request: APIRequestContext, token: string, caseId: string) {
  const preferredDate = new Date(Date.now() + 5 * 86_400_000).toISOString().slice(0, 10);
  const response = await request.post(`${apiBaseUrl}/api/assembly-requests`, {
    headers: { ...authHeaders(token), 'Idempotency-Key': `web-${caseId}-${Date.now()}` },
    data: { serviceType: 'FULL_SERVICE', region: '서울', preferredDate, deliveryMethod: 'DELIVERY', asPolicyAccepted: true }
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return await response.json() as { id: string };
}

async function cancelAssemblyRequest(request: APIRequestContext, token: string, id: string) {
  const response = await request.post(`${apiBaseUrl}/api/assembly-requests/${id}/cancel`, {
    headers: authHeaders(token), data: { reason: '상태형 웹 QA 정리' }
  });
  if (!response.ok()) throw new Error(`assembly cleanup ${response.status()}`);
}

async function createSupportTicket(request: APIRequestContext, token: string, caseId: string) {
  const content = [
    JSON.stringify({ timestamp: new Date().toISOString(), eventId: 'Display-4101', source: 'Display', message: 'nvlddmkm stopped responding and recovered', caseId }),
    JSON.stringify({ timestamp: new Date().toISOString(), eventId: 'GPU-METRIC', gpuTempC: 83, gpuUsagePercent: 99, fps: 0, caseId })
  ].join('\n') + '\n';
  const upload = await request.post(`${apiBaseUrl}/api/agent-logs/upload`, {
    headers: authHeaders(token),
    multipart: {
      rangeMinutes: '30', consentAccepted: 'true',
      file: { name: `${caseId}.jsonl`, mimeType: 'application/x-ndjson', buffer: Buffer.from(content) }
    }
  });
  expect(upload.ok(), await upload.text()).toBeTruthy();
  const log = await upload.json() as { id: string };
  const response = await request.post(`${apiBaseUrl}/api/as-tickets`, {
    headers: authHeaders(token), data: { symptom: '게임 실행 중 검은 화면과 그래픽 드라이버 중단', logUploadId: log.id }
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return await response.json() as { id: string };
}

async function closeSupportTicket(request: APIRequestContext, token: string, id: string) {
  const current = await request.get(`${apiBaseUrl}/api/admin/as-tickets/${id}`, { headers: authHeaders(token) });
  if (!current.ok()) throw new Error(`support cleanup lookup ${current.status()}`);
  let ticket = await current.json() as { status?: string };
  if (ticket.status === 'CLOSED' || ticket.status === 'CANCELLED') return;
  if (ticket.status !== 'RESOLVED') {
    const resolved = await request.patch(`${apiBaseUrl}/api/admin/as-tickets/${id}`, {
      headers: authHeaders(token), data: { status: 'RESOLVED', adminNote: '상태형 웹 QA 원격지원 완료' }
    });
    if (!resolved.ok()) throw new Error(`support resolve ${resolved.status()}`);
    ticket = await resolved.json() as { status?: string };
  }
  const closed = await request.patch(`${apiBaseUrl}/api/admin/as-tickets/${id}`, {
    headers: authHeaders(token), data: { status: 'CLOSED', adminNote: '상태형 웹 QA 종료' }
  });
  if (!closed.ok()) throw new Error(`support cleanup ${closed.status()}`);
}

async function resetActiveSupportTicket(request: APIRequestContext, userToken: string, adminToken: string) {
  const response = await request.get(`${apiBaseUrl}/api/support/chat-sessions/current`, { headers: authHeaders(userToken) });
  if (!response.ok()) throw new Error(`support precondition ${response.status()}`);
  const payload = await response.json() as { contact?: { asTicketId?: string } | null };
  if (payload.contact?.asTicketId) await closeSupportTicket(request, adminToken, payload.contact.asTicketId);
}

function writeReport(results: CaseResult[]) {
  const date = new Date().toISOString().slice(0, 10).replaceAll('-', '');
  const directory = resolve(process.cwd(), '..', '..', 'docs', 'reports');
  mkdirSync(directory, { recursive: true });
  const jsonPath = resolve(directory, `demo-journey-stateful-web-audit-${date}.json`);
  const mdPath = resolve(directory, `demo-journey-stateful-web-audit-${date}.md`);
  writeFileSync(jsonPath, JSON.stringify({ generatedAt: new Date().toISOString(), profile, results }, null, 2), 'utf8');
  const lines = [
    '# 4분 데모 상태형 웹 재현 감사', '', `- Build Chat profile: \`${profile}\``,
    `- 결과: PASS ${results.filter((row) => row.verdict === 'PASS').length} / FAIL ${results.filter((row) => row.verdict === 'FAIL').length}`, '',
    '| case | 그룹 | 결과 | 오류 | 증거 |', '|---|---|---|---|---|',
    ...results.map((row) => `| ${row.caseId} | ${row.group} | ${row.verdict} | ${row.failures.join(', ') || '-'} | ${row.evidence.join(', ') || '-'} |`)
  ];
  writeFileSync(mdPath, `${lines.join('\n')}\n`, 'utf8');
  return { md: mdPath, json: jsonPath };
}
