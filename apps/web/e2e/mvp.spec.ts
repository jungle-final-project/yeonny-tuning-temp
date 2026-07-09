import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const apiBaseUrl = process.env.MVP_API_BASE_URL ?? 'http://127.0.0.1:8080';
const userEmail = process.env.MVP_USER_EMAIL ?? 'user@example.com';
const adminEmail = process.env.MVP_ADMIN_EMAIL ?? 'admin@example.com';
const password = process.env.MVP_PASSWORD ?? 'passw0rd!';

test('MVP E2E: login, recommendation, alert, AS, admin worker views', async ({ page, request }) => {
  await preflight(request);

  await login(page, userEmail, password, 'USER');
  const userToken = await accessToken(page);

  const buildChatResponse = await request.post(`${apiBaseUrl}/api/ai/build-chat`, {
    headers: { Authorization: `Bearer ${userToken}` },
    data: { message: '200만원 QHD 게임용 PC 추천해줘. NVIDIA 선호.' }
  });
  expect(buildChatResponse.status(), 'OPENAI_API_KEY가 없으면 build chat은 428이어야 하며 MVP E2E는 실패합니다.').not.toBe(428);
  expect(buildChatResponse.ok(), await buildChatResponse.text()).toBeTruthy();

  const recommendation = await createRecommendation(page);
  await page.getByRole('link', { name: '상세 보기' }).first().click();
  await expect(page).toHaveURL(new RegExp(`/builds/${recommendation.buildId}`));
  await expect(page.getByText('추천 Build 결과')).toBeVisible();
  await page.getByRole('link', { name: '견적 저장' }).click();

  await expect(page).toHaveURL('/my/quotes');
  await expect(page.getByText('내 견적함')).toBeVisible();
  await page.getByLabel('목표가').fill(String(430_000 + uniqueNumber()));
  await page.getByRole('button', { name: '알림 등록' }).click();
  await expect(page.getByText('알림 등록 완료')).toBeVisible();

  const ticketId = await createSupportTicket(page);
  await expect(page).toHaveURL(new RegExp(`/support/${ticketId}`));
  await expect(page.getByText('AS 티켓 #')).toBeVisible();

  await page.evaluate(() => localStorage.clear());
  await login(page, adminEmail, password, 'ADMIN');

  await page.goto(`/admin/as-tickets/${ticketId}`);
  await expect(page.getByText('AS 티켓 상세')).toBeVisible();
  await expect(page.getByText(ticketId)).toBeVisible();
  await page.getByRole('button', { name: '담당자 배정' }).click();
  await page.getByRole('button', { name: '상태 저장' }).click();
  await expect(page.getByText('저장 완료')).toBeVisible();
  await page.getByLabel('상태', { exact: true }).selectOption('IN_PROGRESS');
  await page.getByLabel('관리자 메모').fill('MVP E2E 담당자 배정 및 상태 전이 확인');
  await page.getByRole('button', { name: '상태 저장' }).click();
  await expect(page.getByText('저장 완료')).toBeVisible();

  await page.goto(`/admin/agent-sessions/${recommendation.agentSessionId}`);
  await expect(page.getByText('Agent 실행 Trace')).toBeVisible();
  await expect(page.getByText(/현재 상태: (SUCCEEDED|FAILED|RUNNING|QUEUED)/)).toBeVisible();
  await expect(page.getByText('Tool 호출 이력')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'RAG Evidence' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'LLM 생성 기록' })).toBeVisible();

  await page.goto('/admin/price-jobs');
  await expect(page.getByText('가격 Job 관리자')).toBeVisible();
  const runButton = page.getByRole('button', { name: /가격 Job 실행|실행 중인 Job 있음|실행 요청 중/ });
  if (await runButton.isEnabled()) {
    const runResponse = page.waitForResponse((response) =>
      response.url().includes('/api/admin/price-jobs/run') && response.request().method() === 'POST'
    );
    await runButton.click();
    const response = await runResponse;
    expect(response.ok(), await response.text()).toBeTruthy();
    await expect(page.getByText('실행 요청 완료')).toBeVisible();
    await expect(page.getByText(/^(QUEUED|RUNNING|SUCCEEDED|FAILED)$/).first()).toBeVisible();
  } else {
    await expect(page.getByText('실행 중인 Job 있음')).toBeVisible();
  }
});

async function preflight(request: APIRequestContext) {
  if (!hasOpenAiKey()) {
    throw new Error('OPENAI_API_KEY is required for MVP E2E. Set it in the shell env or repository .env.');
  }
  const apiHealth = await request.get(`${apiBaseUrl}/actuator/health`);
  expect(apiHealth.ok(), `API health check failed: ${apiHealth.status()}`).toBeTruthy();
}

async function login(page: Page, email: string, loginPassword: string, role: 'USER' | 'ADMIN') {
  await page.goto('/login');
  await page.getByLabel('이메일').fill(email);
  await page.getByLabel('비밀번호').fill(loginPassword);
  await page.getByRole('button', { name: '로그인' }).click();
  await expect(page.getByText(`로그인됨 · ${email} · ${role}`)).toBeVisible();
}

async function accessToken(page: Page) {
  const token = await page.evaluate(() => localStorage.getItem('buildgraph.token'));
  expect(token).toBeTruthy();
  return token as string;
}

async function createRecommendation(page: Page) {
  await page.goto('/requirements/new');
  await page.locator('textarea').first().fill('200만원 안에서 QHD 게임과 개발을 같이 할 PC 추천해줘. NVIDIA 선호.');
  await page.getByLabel('예산').fill('2000000');
  await page.getByLabel('주 용도').fill('게임, 개발');
  await page.getByLabel('해상도').fill('QHD');
  await page.getByLabel('브랜드 선호').fill('NVIDIA');

  const parseResponse = page.waitForResponse((response) =>
    response.url().includes('/api/requirements/parse') && response.request().method() === 'POST'
  );
  await page.getByRole('button', { name: '요구사항 분석' }).click();
  const parsed = await parseResponse;
  expect(parsed.ok(), await parsed.text()).toBeTruthy();
  await expect(page.getByText('분석 완료')).toBeVisible();

  const recommendResponse = page.waitForResponse((response) =>
    response.url().includes('/api/builds/recommend') && response.request().method() === 'POST'
  );
  await page.getByRole('button', { name: '추천 결과 보기' }).click();
  const response = await recommendResponse;
  expect(response.ok(), await response.text()).toBeTruthy();
  const body = await response.json() as {
    agentSessionId?: string;
    recommendations?: Array<{ id?: string; agentSessionId?: string }>;
  };
  const buildId = body.recommendations?.[0]?.id;
  const agentSessionId = body.agentSessionId ?? body.recommendations?.[0]?.agentSessionId;
  expect(buildId).toBeTruthy();
  expect(agentSessionId).toBeTruthy();
  await expect(page.getByText('추천 빌드 3개')).toBeVisible();
  return { buildId: buildId as string, agentSessionId: agentSessionId as string };
}

async function createSupportTicket(page: Page) {
  await page.goto('/support/new');
  await page.getByLabel('증상 제목').fill('MVP E2E 프레임 드랍');
  await page.getByLabel('증상 상세').fill('게임 실행 20분 뒤 프레임이 급락하고 GPU 온도가 높게 유지됩니다.');
  await page.locator('input[type="file"]').setInputFiles({
    name: 'mvp-agent-log.jsonl',
    mimeType: 'application/x-ndjson',
    buffer: Buffer.from(JSON.stringify({
      capturedAt: new Date().toISOString(),
      game: 'PUBG',
      gpuTempC: 95,
      fpsAvg: 47,
      event: 'frame_drop'
    }) + '\n')
  });
  await page.getByLabel('최근 30분 로그 업로드와 30일 보관 후 삭제 정책에 동의합니다.').check();
  await page.getByRole('button', { name: 'AS 접수하기' }).click();
  await expect(page).toHaveURL(/\/support\/[0-9a-f-]+/);
  const match = page.url().match(/\/support\/([0-9a-f-]+)$/);
  expect(match?.[1]).toBeTruthy();
  return match?.[1] as string;
}

function hasOpenAiKey() {
  if (process.env.OPENAI_API_KEY?.trim()) {
    return true;
  }
  for (const envPath of [resolve(process.cwd(), '.env'), resolve(process.cwd(), '..', '..', '.env')]) {
    if (existsSync(envPath) && /^OPENAI_API_KEY=.+/m.test(readFileSync(envPath, 'utf8'))) {
      return true;
    }
  }
  return false;
}

function uniqueNumber() {
  return Number(String(Date.now()).slice(-5));
}
