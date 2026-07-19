import { expect, test, type Page, type Route } from '@playwright/test';

const FIRST_DIAGNOSIS_ID = '00000000-0000-4000-8000-000000000401';
const SECOND_DIAGNOSIS_ID = '00000000-0000-4000-8000-000000000402';
const THIRD_DIAGNOSIS_ID = '00000000-0000-4000-8000-000000000403';

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });
  await page.route('**/api/auth/me', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ id: 'user-001', email: 'user@example.com', role: 'USER' })
  }));
  await page.route('**/api/support/chat-sessions/current**', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ contact: null, messages: [], supportNewPath: '/support/new' })
  }));
  await page.route('**/api/technician/profile', (route) => route.fulfill({
    status: 404,
    contentType: 'application/json',
    body: JSON.stringify({ code: 'NOT_FOUND', message: 'not a technician' })
  }));
});

test('polls progress without overlap, deduplicates logs, and shows the completed server result', async ({ page }) => {
  let getCount = 0;
  let concurrent = 0;
  let maxConcurrent = 0;

  await routeDiagnosisRequest(page, () => FIRST_DIAGNOSIS_ID);
  await page.route(diagnosisGetPattern(), async (route) => {
    const callNumber = ++getCount;
    concurrent += 1;
    maxConcurrent = Math.max(maxConcurrent, concurrent);
    // 두 번째 응답은 polling 주기보다 늦게 반환해도 다음 요청이 겹치지 않는지 확인한다.
    await delay(callNumber === 2 ? 2_200 : 120);
    const response = callNumber === 1
      ? diagnosisResponse(FIRST_DIAGNOSIS_ID, {
          currentProgress: 25,
          currentTask: 'hardware-overview',
          events: [event('event-1', 'COLLECTING', 25, '하드웨어 정보를 수집하고 있습니다.'), event('event-1', 'COLLECTING', 25, '하드웨어 정보를 수집하고 있습니다.')]
        })
      : callNumber === 2
        ? diagnosisResponse(FIRST_DIAGNOSIS_ID, {
            currentProgress: 70,
            currentTask: 'graphics-device-state',
            events: [
              event('event-1', 'COLLECTING', 25, '하드웨어 정보를 수집하고 있습니다.'),
              event('event-2', 'DIAGNOSING', 70, '그래픽 장치 상태를 확인하고 있습니다.')
            ]
          })
        : callNumber === 3
          ? diagnosisResponse(FIRST_DIAGNOSIS_ID, {
              currentProgress: 100,
              currentTask: 'evidence-finalize',
              completed: true,
              events: [
                event('event-1', 'COLLECTING', 25, '하드웨어 정보를 수집하고 있습니다.'),
                event('event-2', 'DIAGNOSING', 70, '그래픽 장치 상태를 확인하고 있습니다.'),
                event('event-3', 'COMPLETED', 100, '진단 결과를 저장하고 있습니다.')
              ]
            })
          : completedResponse(FIRST_DIAGNOSIS_ID);
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(response) });
    concurrent -= 1;
  });

  await startDiagnosis(page);

  await expect(page.getByRole('progressbar', { name: 'PC Agent 진단 진행률' })).toHaveAttribute('aria-valuenow', '25');
  await expect(page.getByTestId('pc-agent-diagnosis-progress')).toContainText('hardware-overview');
  await expect(page.locator('[data-event-id="event-1"]')).toHaveCount(1);

  await expect(page.getByRole('progressbar', { name: 'PC Agent 진단 진행률' })).toHaveAttribute('aria-valuenow', '70');
  await expect(page.getByTestId('pc-agent-diagnosis-logs').locator('li')).toHaveText([
    '하드웨어 정보를 수집하고 있습니다.',
    '그래픽 장치 상태를 확인하고 있습니다.'
  ]);

  await expect(page.getByTestId('pc-agent-diagnosis-result')).toContainText('그래픽 장치·드라이버 구성 이상');
  await expect(page.getByTestId('pc-agent-diagnosis-result')).toContainText('PNP_PROBLEM_CODE');
  await expect(page.getByTestId('pc-agent-diagnosis-result')).toContainText('43');
  await expect(page.getByTestId('pc-agent-diagnosis-result')).toContainText('원격 지원이 권장됩니다.');
  await expect(page.getByTestId('pc-agent-diagnosis-progress')).toHaveCount(0);
  const terminalCount = getCount;
  await delay(2_300);
  expect(getCount).toBe(terminalCount);
  expect(maxConcurrent).toBe(1);
});

test('keeps the last progress through a transient error, retries, and stops on failure', async ({ page }) => {
  let getCount = 0;
  await routeDiagnosisRequest(page, () => FIRST_DIAGNOSIS_ID);
  await page.route(diagnosisGetPattern(), async (route) => {
    getCount += 1;
    if (getCount === 2) {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'TEMPORARY_ERROR', message: 'temporary failure' })
      });
      return;
    }
    const response = getCount === 1
      ? diagnosisResponse(FIRST_DIAGNOSIS_ID, {
          currentProgress: 35,
          currentTask: 'graphics-device-state',
          events: [event('event-running', 'DIAGNOSING', 35, '그래픽 장치 상태를 확인하고 있습니다.')]
        })
      : diagnosisResponse(FIRST_DIAGNOSIS_ID, {
          currentProgress: 35,
          currentTask: 'graphics-device-state',
          completed: true,
          events: [
            event('event-running', 'DIAGNOSING', 35, '그래픽 장치 상태를 확인하고 있습니다.'),
            event('event-failed', 'FAILED', 35, '그래픽 장치 상태 확인에 실패했습니다.')
          ]
        });
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(response) });
  });

  await startDiagnosis(page);
  await expect(page.getByRole('progressbar', { name: 'PC Agent 진단 진행률' })).toHaveAttribute('aria-valuenow', '35');
  await expect(page.getByText('진단 진행 상태를 일시적으로 불러오지 못했습니다. 다음 조회에서 다시 시도합니다.')).toBeVisible();
  await expect(page.getByRole('progressbar', { name: 'PC Agent 진단 진행률' })).toHaveAttribute('aria-valuenow', '35');
  await expect(page.getByText('PC Agent 진단 실패')).toBeVisible();
  await expect(page.getByTestId('pc-agent-diagnosis-progress')).toContainText('그래픽 장치 상태 확인에 실패했습니다.');
  const terminalCount = getCount;
  await delay(2_300);
  expect(getCount).toBe(terminalCount);
});

test('stops on cancellation and clears old data when a new diagnosis is unauthorized or missing', async ({ page }) => {
  let postCount = 0;
  const getCounts = new Map<string, number>();
  await routeDiagnosisRequest(page, () => {
    postCount += 1;
    return postCount === 1 ? FIRST_DIAGNOSIS_ID : postCount === 2 ? SECOND_DIAGNOSIS_ID : THIRD_DIAGNOSIS_ID;
  });
  await page.route(diagnosisGetPattern(), async (route) => {
    const diagnosisId = diagnosisIdFromRoute(route);
    getCounts.set(diagnosisId, (getCounts.get(diagnosisId) ?? 0) + 1);
    if (diagnosisId === SECOND_DIAGNOSIS_ID) {
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'FORBIDDEN', message: 'forbidden' })
      });
      return;
    }
    if (diagnosisId === THIRD_DIAGNOSIS_ID) {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'DIAGNOSIS_NOT_FOUND', message: 'not found' })
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(diagnosisResponse(FIRST_DIAGNOSIS_ID, {
        currentProgress: 15,
        completed: true,
        events: [event('event-cancelled', 'CANCELLED', 15, '사용자 요청으로 진단이 취소되었습니다.')]
      }))
    });
  });

  await startDiagnosis(page);
  await expect(page.getByText('PC Agent 진단 취소')).toBeVisible();
  const cancelledCount = getCounts.get(FIRST_DIAGNOSIS_ID);
  await delay(2_300);
  expect(getCounts.get(FIRST_DIAGNOSIS_ID)).toBe(cancelledCount);

  await page.getByRole('button', { name: 'PC Agent로 진단' }).click();
  await expect(page.getByText('진단 진행 상태를 조회할 권한이 없습니다. 로그인 상태를 확인해 주세요.')).toBeVisible();
  await expect(page.getByText('사용자 요청으로 진단이 취소되었습니다.')).toHaveCount(0);
  const forbiddenCount = getCounts.get(SECOND_DIAGNOSIS_ID);
  await delay(2_300);
  expect(getCounts.get(SECOND_DIAGNOSIS_ID)).toBe(forbiddenCount);

  await page.getByRole('button', { name: 'PC Agent로 진단' }).click();
  await expect(page.getByText('진단 요청을 찾을 수 없습니다.')).toBeVisible();
  await expect(page.getByText(SECOND_DIAGNOSIS_ID)).toHaveCount(0);
  expect(getCounts.get(THIRD_DIAGNOSIS_ID)).toBe(1);
});

test('cleans the polling timer when the page unmounts', async ({ page }) => {
  let getCount = 0;
  await routeDiagnosisRequest(page, () => FIRST_DIAGNOSIS_ID);
  await page.route(diagnosisGetPattern(), async (route) => {
    getCount += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(diagnosisResponse(FIRST_DIAGNOSIS_ID, {
        currentProgress: 45,
        currentTask: 'graphics-device-state',
        events: [event('event-running', 'DIAGNOSING', 45, '그래픽 장치 상태를 확인하고 있습니다.')]
      }))
    });
  });

  await startDiagnosis(page);
  await expect(page.getByRole('progressbar', { name: 'PC Agent 진단 진행률' })).toHaveAttribute('aria-valuenow', '45');
  const countBeforeUnmount = getCount;
  await page.goto('/login');
  await delay(2_300);
  expect(getCount).toBe(countBeforeUnmount);
});

test('restores the same diagnosis from the URL after refresh', async ({ page }) => {
  let getCount = 0;
  await page.route(diagnosisGetPattern(), async (route) => {
    getCount += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(completedResponse(FIRST_DIAGNOSIS_ID))
    });
  });

  await page.goto(`/support/new?diagnosisId=${FIRST_DIAGNOSIS_ID}`);
  await expect(page.getByTestId('pc-agent-diagnosis-result')).toContainText('그래픽 장치·드라이버 구성 이상');
  const countBeforeReload = getCount;
  await page.reload();
  await expect(page.getByTestId('pc-agent-diagnosis-result')).toContainText('그래픽 장치·드라이버 구성 이상');
  expect(getCount).toBeGreaterThan(countBeforeReload);
});

async function startDiagnosis(page: Page) {
  await page.goto('/support/new');
  await page.getByLabel('증상 상세').fill('화면이 끊기고 그래픽 장치 오류가 발생합니다.');
  await page.getByRole('button', { name: 'PC Agent로 진단' }).click();
}

async function routeDiagnosisRequest(page: Page, diagnosisId: () => string) {
  await page.route('**/api/users/me/agent-diagnosis-requests', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      diagnosisId: diagnosisId(),
      deviceId: '00000000-0000-4000-8000-000000000111',
      requestedAt: '2026-07-18T00:00:00Z',
      expiresAt: '2026-07-18T00:05:00Z',
      mode: 'LIVE',
      status: 'ACCEPTED'
    })
  }));
}

function diagnosisGetPattern() {
  return /\/api\/users\/me\/agent-diagnosis-requests\/[^/]+$/;
}

function diagnosisIdFromRoute(route: Route) {
  return new URL(route.request().url()).pathname.split('/').pop() ?? '';
}

function diagnosisResponse(diagnosisId: string, override: Record<string, unknown> = {}) {
  return {
    diagnosisId,
    status: 'ACCEPTED',
    connectionStatus: 'CONNECTED',
    agentConnected: true,
    accepted: true,
    currentProgress: 0,
    currentTask: null,
    events: [],
    completed: false,
    result: null,
    resolutionType: null,
    dataMode: null,
    scenarioId: null,
    createdAt: '2026-07-18T00:00:00Z',
    updatedAt: '2026-07-18T00:00:00Z',
    ...override
  };
}

function completedResponse(diagnosisId: string) {
  return diagnosisResponse(diagnosisId, {
    currentProgress: 100,
    currentTask: 'evidence-finalize',
    completed: true,
    events: [
      event('event-1', 'COLLECTING', 25, '하드웨어 정보를 수집하고 있습니다.'),
      event('event-2', 'DIAGNOSING', 70, '그래픽 장치 상태를 확인하고 있습니다.'),
      event('event-3', 'COMPLETED', 100, '진단을 완료했습니다.')
    ],
    resolutionType: 'SOFTWARE_RECOVERY',
    dataMode: 'DEMO',
    scenarioId: 'GRAPHICS_CODE43_REMOTE_SUPPORT',
    result: {
      resultId: 'result-1',
      diagnosisType: 'DEVICE_DRIVER_CONFIGURATION_ISSUE',
      severity: 'WARNING',
      title: '그래픽 장치·드라이버 구성 이상',
      summary: 'Arc A350M 장치 상태를 확인한 결과 원격 점검이 필요합니다.',
      resolutionType: 'SOFTWARE_RECOVERY',
      canAutoRecover: false,
      evidence: [{ metricType: 'PNP_PROBLEM_CODE', device: 'Intel Arc A350M', value: 43 }],
      findings: [{ code: 'GRAPHICS_DEVICE_CODE_43' }],
      actions: ['원격으로 장치 상태와 드라이버 구성을 점검합니다.'],
      dataMode: 'DEMO',
      scenarioId: 'GRAPHICS_CODE43_REMOTE_SUPPORT',
      rawPayload: { remoteAsRecommended: true },
      createdAt: '2026-07-18T00:00:05Z',
      updatedAt: '2026-07-18T00:00:05Z'
    }
  });
}

function event(eventId: string, status: string, progressPercent: number, message: string) {
  return {
    eventId,
    taskId: status === 'COLLECTING' ? 'hardware-overview' : 'graphics-device-state',
    eventType: 'PROGRESS_UPDATED',
    status,
    progressPercent,
    message,
    occurredAt: `2026-07-18T00:00:0${Math.min(progressPercent, 9)}Z`,
    createdAt: `2026-07-18T00:00:0${Math.min(progressPercent, 9)}Z`
  };
}

function delay(milliseconds: number) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
