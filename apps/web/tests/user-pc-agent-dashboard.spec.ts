import { expect, test, type Page } from '@playwright/test';

const DIAGNOSIS_ID = '00000000-0000-4000-8000-000000000501';
const TICKET_ID = '00000000-0000-4000-8000-000000000701';

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });
  await page.route('**/api/auth/me', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      id: 'user-001',
      email: 'user@example.com',
      name: 'Demo User',
      role: 'USER',
      authProviders: ['LOCAL']
    })
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

test('shows the account-bound download and diagnosis CTAs when no diagnosis exists', async ({ page }) => {
  let activationRequests = 0;
  let executableVersion = '1.0.0';
  let executableBody = 'test-agent-executable';
  await routeLatest(page, null);
  await captureDownloadedPackages(page);
  await page.route('**/api/users/me/agent-activation-token', (route) => {
    activationRequests += 1;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: `activation-${activationRequests}`,
        activationToken: `owned-one-time-token-${activationRequests}`,
        tokenType: 'ACTIVATION'
      })
    });
  });
  await page.route('**/downloads/pc-agent/latest.json', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ version: executableVersion, downloadUrl: '/downloads/pc-agent/PCAgent.exe' })
  }));
  await page.route('**/downloads/pc-agent/PCAgent.exe*', (route) => route.fulfill({
    status: 200,
    contentType: 'application/octet-stream',
    body: executableBody
  }));

  await page.goto('/my/profile');

  const card = page.getByTestId('pc-agent-dashboard-card');
  await expect(card).toContainText('아직 이 계정에서 실행한 PC Agent 진단이 없습니다.');
  await expect(card.getByRole('link', { name: '진단 시작 안내' })).toHaveAttribute('href', '/support/new');
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    card.getByRole('button', { name: 'PC Agent 다운로드' }).click()
  ]);
  expect(download.suggestedFilename()).toBe('PCAgent.zip');
  expect(activationRequests).toBe(1);
  const firstPackage = await capturedPackage(page, 0);
  expect(readStoredZipEntry(firstPackage, 'PCAgent.exe'))
    .toEqual(new TextEncoder().encode(executableBody));
  expect(JSON.parse(new TextDecoder().decode(readStoredZipEntry(firstPackage, 'pcagent-activation.json'))))
    .toMatchObject({ activationToken: 'owned-one-time-token-1' });
  expect(new TextDecoder().decode(readStoredZipEntry(firstPackage, 'README.txt')))
    .toContain('Keep PCAgent.exe and pcagent-activation.json in the same folder.');
  await expect(card).toContainText('계정에 귀속된 PCAgent.zip');

  executableVersion = '1.0.1';
  executableBody = 'test-agent-executable-v2';
  const [secondDownload] = await Promise.all([
    page.waitForEvent('download'),
    card.getByRole('button', { name: 'PC Agent 다운로드' }).click()
  ]);
  expect(secondDownload.suggestedFilename()).toBe('PCAgent.zip');
  const secondPackage = await capturedPackage(page, 1);
  expect(readStoredZipEntry(secondPackage, 'PCAgent.exe'))
    .toEqual(new TextEncoder().encode(executableBody));
  expect(JSON.parse(new TextDecoder().decode(readStoredZipEntry(secondPackage, 'pcagent-activation.json'))))
    .toMatchObject({ activationToken: 'owned-one-time-token-2' });
  expect(activationRequests).toBe(2);
});

async function captureDownloadedPackages(page: Page) {
  await page.addInitScript(() => {
    const captured: number[][] = [];
    const state = globalThis as typeof globalThis & { __pcAgentPackages?: number[][] };
    state.__pcAgentPackages = captured;
    const originalCreateObjectUrl = URL.createObjectURL.bind(URL);
    URL.createObjectURL = (blob: Blob) => {
      void blob.arrayBuffer().then((buffer) => captured.push(Array.from(new Uint8Array(buffer))));
      return originalCreateObjectUrl(blob);
    };
  });
}

async function capturedPackage(page: Page, index: number) {
  await expect.poll(() => page.evaluate((packageIndex) => {
    const state = globalThis as typeof globalThis & { __pcAgentPackages?: number[][] };
    return state.__pcAgentPackages?.[packageIndex]?.length ?? 0;
  }, index)).toBeGreaterThan(0);
  const bytes = await page.evaluate((packageIndex) => {
    const state = globalThis as typeof globalThis & { __pcAgentPackages?: number[][] };
    return state.__pcAgentPackages?.[packageIndex] ?? [];
  }, index);
  return new Uint8Array(bytes);
}

function readStoredZipEntry(archive: Uint8Array, expectedName: string) {
  const view = new DataView(archive.buffer, archive.byteOffset, archive.byteLength);
  const decoder = new TextDecoder();
  let offset = 0;
  while (offset + 30 <= archive.length && view.getUint32(offset, true) === 0x04034b50) {
    const compressionMethod = view.getUint16(offset + 8, true);
    const compressedSize = view.getUint32(offset + 18, true);
    const nameLength = view.getUint16(offset + 26, true);
    const extraLength = view.getUint16(offset + 28, true);
    const nameStart = offset + 30;
    const dataStart = nameStart + nameLength + extraLength;
    const dataEnd = dataStart + compressedSize;
    const name = decoder.decode(archive.subarray(nameStart, nameStart + nameLength));
    if (name === expectedName) {
      if (compressionMethod !== 0) {
        throw new Error(`Unexpected compression method for ${expectedName}: ${compressionMethod}`);
      }
      return archive.subarray(dataStart, dataEnd);
    }
    offset = dataEnd;
  }
  throw new Error(`ZIP entry not found: ${expectedName}`);
}

test('uses the shared diagnosis polling hook for current progress, task, and logs', async ({ page }) => {
  const initial = diagnosisSummary({
    currentProgress: 20,
    currentTask: 'hardware-overview',
    currentStatus: 'COLLECTING',
    recentMessages: [recentMessage('event-1', 'COLLECTING', 20, '하드웨어 정보를 수집하고 있습니다.')]
  });
  await routeLatest(page, initial);
  await routeDiagnosis(page, diagnosisResponse({
    currentProgress: 55,
    currentTask: 'graphics-device-state',
    events: [
      event('event-1', 'COLLECTING', 20, '하드웨어 정보를 수집하고 있습니다.'),
      event('event-2', 'DIAGNOSING', 55, '그래픽 장치 상태를 확인하고 있습니다.')
    ]
  }));

  await page.goto('/my/profile');

  const card = page.getByTestId('pc-agent-dashboard-card');
  await expect(card.getByRole('progressbar', { name: '대시보드 PC Agent 진단 진행률' })).toHaveAttribute('aria-valuenow', '55');
  await expect(card).toContainText('graphics-device-state');
  await expect(card).toContainText('그래픽 장치 상태를 확인하고 있습니다.');
  await expect(card.getByRole('link', { name: '진단 진행 화면' }))
    .toHaveAttribute('href', `/support/new?diagnosisId=${DIAGNOSIS_ID}`);
});

test('shows the completed server result and linked AS ticket without inventing a visit decision', async ({ page }) => {
  const completed = completedSummary({
    asTicket: {
      id: TICKET_ID,
      status: 'OPEN',
      reviewStatus: 'REQUIRED',
      supportDecision: null,
      createdAt: '2026-07-18T00:06:00Z'
    }
  });
  await routeLatest(page, completed);
  await routeDiagnosis(page, completedDiagnosis());

  await page.goto('/my/profile');

  const card = page.getByTestId('pc-agent-dashboard-card');
  await expect(card.getByTestId('pc-agent-dashboard-result')).toContainText('그래픽 장치·드라이버 구성 이상');
  await expect(card.getByTestId('pc-agent-dashboard-result')).toContainText('problemCode: 43');
  await expect(card.getByTestId('pc-agent-dashboard-result')).toContainText('드라이버 재설치 또는 이전 버전 롤백');
  await expect(card.getByTestId('pc-agent-dashboard-result')).toContainText('원격지원 권장');
  await expect(card.getByTestId('pc-agent-dashboard-ticket')).toContainText('원격지원 요청 접수');
  await expect(card.getByTestId('pc-agent-dashboard-ticket')).toContainText('접수됨');
  await expect(card.getByTestId('pc-agent-dashboard-ticket')).toContainText('검토 필요');
  await expect(card.getByTestId('pc-agent-dashboard-ticket')).toContainText('관리자 검토 대기');
  await expect(card).not.toContainText('방문 점검 확정');
  await expect(card.getByRole('button', { name: '원격지원 요청' })).toHaveCount(0);
  await expect(card.getByRole('link', { name: '지원 요청 보기' })).toHaveAttribute('href', `/support/${TICKET_ID}`);
});

test('refreshes latest on window focus and removes the listener after unmount', async ({ page }) => {
  let latestRequests = 0;
  let ticketAvailable = false;
  await page.route('**/api/users/me/agent-diagnosis-requests/latest', (route) => {
    latestRequests += 1;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        diagnosis: completedSummary(ticketAvailable
          ? {
              asTicket: {
                id: TICKET_ID,
                status: 'OPEN',
                reviewStatus: 'REQUIRED',
                supportDecision: null,
                createdAt: '2026-07-18T00:06:00Z'
              }
            }
          : {})
      })
    });
  });
  await routeDiagnosis(page, completedDiagnosis());

  await page.goto('/my/profile');
  const card = page.getByTestId('pc-agent-dashboard-card');
  await expect(card.getByTestId('pc-agent-dashboard-result')).toContainText('그래픽 장치·드라이버 구성 이상');
  expect(latestRequests).toBeGreaterThan(0);
  await expect(card.getByTestId('pc-agent-dashboard-ticket')).toHaveCount(0);

  const initialRequestCount = latestRequests;
  ticketAvailable = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(card.getByTestId('pc-agent-dashboard-ticket')).toContainText('원격지원 요청 접수');
  expect(latestRequests).toBeGreaterThan(initialRequestCount);

  await page.goto('/login');
  const requestCountAfterUnmount = latestRequests;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.waitForTimeout(200);
  expect(latestRequests).toBe(requestCountAfterUnmount);
});

for (const terminal of [
  { status: 'FAILED', title: 'PC Agent 진단 실패' },
  { status: 'CANCELLED', title: 'PC Agent 진단 취소' },
  { status: 'TIMED_OUT', title: 'PC Agent 진단 시간 초과' }
]) {
  test(`shows the server ${terminal.status} state and a clean retry CTA`, async ({ page }) => {
    const summary = diagnosisSummary({
      currentStatus: terminal.status,
      completed: true,
      currentProgress: 40,
      recentMessages: [recentMessage(`event-${terminal.status}`, terminal.status, 40, `서버 ${terminal.status} 상태입니다.`)]
    });
    const response = diagnosisResponse({
      completed: true,
      currentProgress: 40,
      events: [event(`event-${terminal.status}`, terminal.status, 40, `서버 ${terminal.status} 상태입니다.`)]
    });
    await routeLatest(page, summary);
    await routeDiagnosis(page, response);

    await page.goto('/my/profile');

    const card = page.getByTestId('pc-agent-dashboard-card');
    await expect(card).toContainText(terminal.title);
    await expect(card).toContainText(`서버 ${terminal.status} 상태입니다.`);
    await expect(card.getByRole('link', { name: '새 진단 시작' })).toHaveAttribute('href', '/support/new');
    await expect(card.getByTestId('pc-agent-dashboard-result')).toHaveCount(0);
  });
}

test('does not render diagnosis data when the authenticated latest endpoint rejects access', async ({ page }) => {
  await page.route('**/api/users/me/agent-diagnosis-requests/latest', (route) => route.fulfill({
    status: 403,
    contentType: 'application/json',
    body: JSON.stringify({ code: 'FORBIDDEN', message: '접근할 수 없는 진단입니다.' })
  }));

  await page.goto('/my/profile');

  const card = page.getByTestId('pc-agent-dashboard-card');
  await expect(card).toContainText('PC Agent 상태 조회 실패');
  await expect(card).toContainText('접근할 수 없는 진단입니다.');
  await expect(card).not.toContainText('다른 사용자 진단 비밀');
});

async function routeLatest(page: Page, diagnosis: Record<string, unknown> | null) {
  await page.route('**/api/users/me/agent-diagnosis-requests/latest', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ diagnosis })
  }));
}

async function routeDiagnosis(page: Page, diagnosis: Record<string, unknown>) {
  await page.route(new RegExp(`/api/users/me/agent-diagnosis-requests/${DIAGNOSIS_ID}$`), (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(diagnosis)
  }));
}

function diagnosisResponse(override: Record<string, unknown> = {}) {
  return {
    diagnosisId: DIAGNOSIS_ID,
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
    requestedAt: '2026-07-18T00:00:00Z',
    completedAt: null,
    asTicket: null,
    createdAt: '2026-07-18T00:00:00Z',
    updatedAt: '2026-07-18T00:00:00Z',
    ...override
  };
}

function diagnosisSummary(override: Record<string, unknown> = {}) {
  return {
    diagnosisId: DIAGNOSIS_ID,
    status: 'ACCEPTED',
    connectionStatus: 'CONNECTED',
    agentConnected: true,
    accepted: true,
    currentStatus: null,
    currentProgress: 0,
    currentTask: null,
    recentMessages: [],
    completed: false,
    resultAvailable: false,
    resultSeverity: null,
    resolutionType: null,
    dataMode: null,
    scenarioId: null,
    requestedAt: '2026-07-18T00:00:00Z',
    completedAt: null,
    asTicket: null,
    createdAt: '2026-07-18T00:00:00Z',
    updatedAt: '2026-07-18T00:00:00Z',
    ...override
  };
}

function completedSummary(override: Record<string, unknown> = {}) {
  return diagnosisSummary({
    currentStatus: 'COMPLETED',
    currentProgress: 100,
    currentTask: 'evidence-finalize',
    recentMessages: [recentMessage('event-complete', 'COMPLETED', 100, '진단을 완료했습니다.')],
    completed: true,
    resultAvailable: true,
    resultSeverity: 'WARNING',
    resolutionType: 'SOFTWARE_RECOVERY',
    dataMode: 'DEMO',
    scenarioId: 'GRAPHICS_CODE43_REMOTE_SUPPORT',
    completedAt: '2026-07-18T00:05:00Z',
    ...override
  });
}

function completedDiagnosis(override: Record<string, unknown> = {}) {
  return diagnosisResponse({
    currentProgress: 100,
    currentTask: 'evidence-finalize',
    completed: true,
    completedAt: '2026-07-18T00:05:00Z',
    events: [event('event-complete', 'COMPLETED', 100, '진단을 완료했습니다.')],
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
      evidence: [{ component: 'gpu', metricType: 'display_device_status', problemCode: 43 }],
      findings: [{ code: 'GRAPHICS_DEVICE_CODE_43' }],
      actions: ['드라이버 재설치 또는 이전 버전 롤백'],
      dataMode: 'DEMO',
      scenarioId: 'GRAPHICS_CODE43_REMOTE_SUPPORT',
      rawPayload: { remoteAsRecommended: true },
      createdAt: '2026-07-18T00:05:00Z',
      updatedAt: '2026-07-18T00:05:00Z'
    },
    ...override
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
    occurredAt: '2026-07-18T00:00:01Z',
    createdAt: '2026-07-18T00:00:01Z'
  };
}

function recentMessage(eventId: string, status: string, progressPercent: number, message: string) {
  return {
    eventId,
    status,
    progressPercent,
    message,
    occurredAt: '2026-07-18T00:00:01Z'
  };
}
