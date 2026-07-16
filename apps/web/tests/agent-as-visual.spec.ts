import { expect, test, type Request, type Route } from '@playwright/test';

const screenshotDir = '../../artifacts/qa/agent-as';
type MockTicket = Record<string, unknown> & { id: string };

const beforeDecisionTicket = {
  id: 'qa-ticket-before',
  status: 'OPEN',
  analysisStatus: 'RULE_READY',
  reviewStatus: 'REQUIRED',
  supportDecision: 'NEEDS_MORE_INFO',
  riskLevel: 'MEDIUM',
  symptom: 'GPU temperature spike during gaming',
  logUploadId: 'log-upload-before',
  logSummaryText: 'GPU 온도 상승 신호가 있지만 방문/원격 판단에는 추가 확인이 필요합니다.',
  logSummary: {
    summaryText: 'GPU 온도 상승 신호가 있지만 방문/원격 판단에는 추가 확인이 필요합니다.',
    dataQuality: { level: 'PARTIAL' },
    incidentWindow: {
      startedAt: '2026-07-02T06:00:00Z',
      endedAt: '2026-07-02T06:30:00Z',
      symptomType: 'REMOTE_DRIVER_OS'
    },
    correlations: [
      { type: 'AS_RAG_MATCH', summary: 'GPU thermal throttling 신호가 감지됐습니다.' }
    ],
    rawSamples: [
      {
        refId: 'sample-1',
        schemaVersion: '1',
        collectedAt: '2026-07-02T06:18:20Z',
        agentId: 'agent-qa-001',
        sequence: 42,
        kind: 'SYSTEM_METRIC',
        payload: {
          gpuTemperatureC: 95,
          message: 'gpu temperature reached 95c',
          diagnosticContext: 'masked-context-'.repeat(24)
        },
        privacyFlags: { containsRawPath: false, masked: true }
      },
      {
        sampleId: 'legacy-sample-2',
        text: JSON.stringify({
          capturedAt: '2026-07-02T06:19:20Z',
          event: 'frame_drop',
          fpsAvg: 47
        })
      }
    ]
  },
  supportRouting: {
    recommendedDecision: 'NEEDS_MORE_INFO',
    confidence: 'LOW',
    reasonCodes: ['GPU_THERMAL_SPIKE'],
    remoteActions: ['CHECK_GPU_DRIVER'],
    visitReasons: [],
    blockingFactors: ['INSUFFICIENT_LOG_RANGE'],
    recommendedService: 'DIAGNOSIS_ONLY',
    recommendedServiceLabel: '우선 진단만 받기',
    requiresAdminApproval: true
  },
  assignedAdminId: null,
  causeCandidates: [
    { label: 'GPU thermal throttling', confidence: 'HIGH', evidenceIds: ['gpu-temperature-95c'] }
  ],
  upgradeCandidates: [],
  adminNote: null,
  remoteSupportLink: null,
  remoteSupportStatus: null,
  safetyAdviceLevel: 'STOP_USE_UNTIL_REVIEW',
  safetyNotices: [
    { code: 'THERMAL_DAMAGE_RISK', message: '담당자 검토 전까지 고부하 작업을 중지해 주세요.' }
  ],
  visitSupportRequired: false,
  createdAt: '2026-07-02T06:30:00Z'
};

const afterDecisionTicket = {
  ...beforeDecisionTicket,
  id: 'qa-ticket-after',
  status: 'IN_PROGRESS',
  reviewStatus: 'APPROVED',
  supportDecision: 'REMOTE_POSSIBLE',
  assignedAdminId: 'admin-public-id',
  adminNote: 'Remote support link sent.',
  remoteSupportLink: 'https://support.example.test/session/qa-ticket-after',
  remoteSupportStatus: 'LINK_SENT'
};

const visitRecommendedTicket = {
  ...beforeDecisionTicket,
  id: 'qa-ticket-visit',
  supportDecision: 'VISIT_REQUIRED',
  riskLevel: 'HIGH',
  symptom: 'Kernel-Power 반복 종료와 WHEA 오류',
  logSummaryText: 'Kernel-Power와 WHEA 오류가 반복되어 현장 점검 가능성이 높습니다.',
  supportRouting: {
    recommendedDecision: 'VISIT_REQUIRED',
    confidence: 'HIGH',
    reasonCodes: ['KERNEL_POWER_REPEAT', 'WHEA_ERROR_REPEAT'],
    remoteActions: [],
    visitReasons: ['POWER_OR_BOARD_CHECK_REQUIRED'],
    blockingFactors: [],
    recommendedService: 'VISIT_SUPPORT',
    recommendedServiceLabel: '방문지원 신청',
    requiresAdminApproval: true
  }
};

const noSampleTicket = {
  ...beforeDecisionTicket,
  id: 'qa-ticket-no-samples',
  logSummary: {
    ...beforeDecisionTicket.logSummary,
    rawSamples: []
  }
};

const diagnosisOnlyTicket = {
  ...beforeDecisionTicket,
  id: 'qa-ticket-diagnosis-evidence',
  logUploadId: null,
  logSummaryText: null,
  logSummary: null,
  diagnosisEvidence: [
    {
      taskId: 'gpu-temperature',
      component: 'gpu',
      metricType: 'temperature',
      value: 95,
      unit: 'C',
      availability: 'AVAILABLE',
      status: 'ABNORMAL',
      source: 'nvidia-smi',
      sampledAt: '2026-07-02T06:18:20Z'
    }
  ]
};

test('captures Agent AS demo UI evidence and verifies admin decision reflection', async ({ page }) => {
  const consoleErrors: string[] = [];
  const apiCalls: string[] = [];
  const tickets = new Map<string, MockTicket>([
    [beforeDecisionTicket.id, beforeDecisionTicket],
    [afterDecisionTicket.id, afterDecisionTicket],
    [visitRecommendedTicket.id, visitRecommendedTicket],
    [noSampleTicket.id, noSampleTicket],
    [diagnosisOnlyTicket.id, diagnosisOnlyTicket]
  ]);
  let decisionPatchPayload: Record<string, unknown> | undefined;
  let remoteRequestPayload: Record<string, unknown> | undefined;
  let feedbackPayload: Record<string, unknown> | undefined;
  let deletedTicketId: string | undefined;

  page.on('console', (message) => {
    if (message.type() === 'error') {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/auth/me', async (route) => {
    recordApiCall(apiCalls, route.request());
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'admin-001',
        email: 'admin@example.com',
        role: 'ADMIN'
      })
    });
  });
  await page.route('**/api/support/chat-sessions/current**', async (route) => {
    recordApiCall(apiCalls, route.request());
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ contact: null, messages: [], supportNewPath: '/support/new' })
    });
  });
  await page.route(/\/api\/as-tickets\/[^/]+$/, async (route) => {
    recordApiCall(apiCalls, route.request());
    const ticketId = lastPathSegment(route.request().url());
    await fulfillTicket(route, tickets.get(ticketId));
  });
  await page.route(/\/api\/as-tickets\/[^/]+\/remote-support-requests$/, async (route) => {
    recordApiCall(apiCalls, route.request());
    const match = new URL(route.request().url()).pathname.match(/\/api\/as-tickets\/([^/]+)\/remote-support-requests$/);
    const ticketId = match?.[1] ?? beforeDecisionTicket.id;
    remoteRequestPayload = route.request().postDataJSON() as Record<string, unknown>;
    const current = tickets.get(ticketId) ?? beforeDecisionTicket;
    const updated = {
      ...current,
      remoteSupportStatus: 'REQUESTED',
      reviewStatus: 'REQUIRED'
    };
    tickets.set(ticketId, updated);
    await fulfillTicket(route, updated);
  });
  await page.route(/\/api\/as-tickets\/[^/]+\/feedback$/, async (route) => {
    recordApiCall(apiCalls, route.request());
    const match = new URL(route.request().url()).pathname.match(/\/api\/as-tickets\/([^/]+)\/feedback$/);
    const ticketId = match?.[1] ?? beforeDecisionTicket.id;
    feedbackPayload = route.request().postDataJSON() as Record<string, unknown>;
    const current = tickets.get(ticketId) ?? beforeDecisionTicket;
    const updated = {
      ...current,
      feedbackRating: feedbackPayload.rating,
      feedbackComment: feedbackPayload.comment
    };
    tickets.set(ticketId, updated);
    await fulfillTicket(route, updated);
  });
  await page.route('**/api/admin/as-tickets', async (route) => {
    recordApiCall(apiCalls, route.request());
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: Array.from(tickets.values()), page: 0, size: 20, total: tickets.size })
    });
  });
  await page.route(/\/api\/admin\/as-tickets\/[^/]+$/, async (route) => {
    recordApiCall(apiCalls, route.request());
    const ticketId = lastPathSegment(route.request().url());
    if (route.request().method() === 'DELETE') {
      deletedTicketId = ticketId;
      tickets.delete(ticketId);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: ticketId,
          deleted: true,
          deletedAt: '2026-07-16T06:00:00Z'
        })
      });
      return;
    }
    if (route.request().method() === 'PATCH') {
      decisionPatchPayload = route.request().postDataJSON() as Record<string, unknown>;
      const current = tickets.get(ticketId) ?? beforeDecisionTicket;
      const updated = {
        ...current,
        ...decisionPatchPayload,
        id: ticketId,
        remoteSupportStatus: decisionPatchPayload.remoteSupportLink ? 'LINK_SENT' : current.remoteSupportStatus
      };
      tickets.set(ticketId, updated);
      await fulfillTicket(route, updated);
      return;
    }
    await fulfillTicket(route, tickets.get(ticketId));
  });

  await page.goto('/support/new');
  await expect(page.getByRole('main')).toContainText('AS 접수');
  await expect(page.getByRole('main')).toContainText('문제 발생 전후 로그');
  await expect(page.getByRole('main')).toContainText('선택 구간 로그 파일');
  await page.screenshot({ path: `${screenshotDir}/01-support-new.png`, fullPage: true });

  await page.goto('/support/qa-ticket-before');
  await expect(page.getByRole('main')).toContainText('규칙 진단 완료');
  await expect(page.getByRole('main')).toContainText('검토 필요');
  await expect(page.getByRole('main')).toContainText('추가 정보 필요');
  await expect(page.getByRole('main')).toContainText('GPU thermal throttling');
  await expect(page.getByRole('main')).toContainText('안전 안내');
  await expect(page.getByRole('main')).toContainText('고부하 작업을 중지');
  await page.getByRole('button', { name: '원격지원 요청' }).click();
  await expect(page.getByRole('main')).toContainText('원격지원 상태: 신청됨');
  expect(remoteRequestPayload).toMatchObject({
    reason: '원격지원으로 화면을 함께 확인하고 싶습니다.'
  });
  await page.getByRole('button', { name: '피드백 저장' }).click();
  await expect(page.getByRole('main')).toContainText('저장된 평점 5/5');
  expect(feedbackPayload).toMatchObject({
    rating: 5
  });
  await expect(page.getByRole('main')).not.toContainText('undefined');
  await page.screenshot({ path: `${screenshotDir}/02-support-ticket-before-decision.png`, fullPage: true });

  await page.evaluate(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.goto('/admin/as-tickets');
  await expect(page.getByRole('main')).toContainText('추천 서비스');
  await expect(page.getByRole('main')).toContainText('방문지원 신청');

  for (const column of ['상태', '검토', '결정', '추천 서비스']) {
    await expect(page.getByRole('columnheader', { name: column, exact: true })).toHaveCSS('white-space', 'nowrap');
  }

  const nowrapValues = [
    page.getByTitle('OPEN').first(),
    page.getByTitle('REQUIRED').first(),
    page.getByTitle('VISIT_REQUIRED').first(),
    page.getByText('방문지원 신청', { exact: true }).first()
  ];
  for (const value of nowrapValues) {
    await expect(value).toHaveCSS('white-space', 'nowrap');
    expect(await value.evaluate((element) => (
      element.scrollWidth <= element.clientWidth + 1
      && element.scrollHeight <= element.clientHeight + 1
    ))).toBe(true);
  }

  const ticketListPanel = page.getByTestId('admin-as-ticket-list-panel');
  const ticketSummaryPanel = page.getByTestId('admin-as-ticket-summary-panel');
  for (const width of [1280, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    const listBox = await ticketListPanel.boundingBox();
    const summaryBox = await ticketSummaryPanel.boundingBox();
    expect(listBox).not.toBeNull();
    expect(summaryBox).not.toBeNull();
    expect(summaryBox!.y).toBeGreaterThan(listBox!.y + listBox!.height);
  }

  await page.goto('/admin/as-tickets/qa-ticket-before');
  await expect(page.getByRole('main')).toContainText('지원 결정 저장');
  await expect(page.getByRole('main')).toContainText('추천 서비스');
  await expect(page.getByRole('main')).toContainText('우선 진단만 받기');
  await expect(page.getByRole('main')).toContainText('GPU 온도 상승 신호');
  await expect(page.getByRole('main')).toContainText('GPU_THERMAL_SPIKE');
  await expect(page.getByRole('main')).toContainText('CHECK_GPU_DRIVER');

  const ticketOverview = page.getByTestId('admin-as-ticket-overview');
  const overviewLabels = await ticketOverview.locator('tbody > tr > td:first-child').allTextContents();
  expect(overviewLabels.slice(0, 3)).toEqual(['상태', '에이전트 로그', '분석 상태']);
  await expect(ticketOverview.locator('tbody > tr:first-child > td:first-child')).toHaveCSS('white-space', 'nowrap');
  for (const removedLabel of [
    '추천 근거 코드',
    '원격 조치 후보',
    '방문 판단 근거',
    '차단 요인',
    '원인 후보',
    '업그레이드 후보',
    '원격지원',
    '방문지원'
  ]) {
    expect(overviewLabels).not.toContain(removedLabel);
  }

  const showAgentLogs = ticketOverview.getByRole('button', { name: '에이전트 데이터 보기 (2건)', exact: true });
  await expect(showAgentLogs).toHaveAttribute('aria-expanded', 'false');
  await expect(ticketOverview.getByTestId('agent-log-samples-panel')).toHaveCount(0);
  await showAgentLogs.click();
  await expect(ticketOverview.getByTestId('agent-log-samples-panel')).toBeVisible();
  await expect(ticketOverview).toContainText('개인정보가 마스킹된 핵심 업로드 로그 샘플이며 최대 20건까지 표시됩니다.');
  await expect(ticketOverview).toContainText('SYSTEM_METRIC');
  await expect(ticketOverview).toContainText('#42');
  await expect(ticketOverview).toContainText('gpu temperature reached 95c');
  await expect(ticketOverview).toContainText('frame_drop');

  for (const width of [1280, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => (
      document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1
    ))).toBe(true);
  }

  await ticketOverview.getByRole('button', { name: '에이전트 데이터 닫기', exact: true }).click();
  await expect(ticketOverview.getByTestId('agent-log-samples-panel')).toHaveCount(0);

  await page.goto('/admin/as-tickets/qa-ticket-no-samples');
  await expect(page.getByTestId('admin-as-ticket-overview')).toContainText('업로드된 로그가 있으나 표시 가능한 샘플이 없습니다.');

  await page.goto('/admin/as-tickets/qa-ticket-diagnosis-evidence');
  const diagnosisOverview = page.getByTestId('admin-as-ticket-overview');
  await diagnosisOverview.getByRole('button', { name: '에이전트 데이터 보기 (1건)', exact: true }).click();
  await expect(diagnosisOverview.getByTestId('agent-log-samples-panel')).toContainText('실시간 진단 근거');
  await expect(diagnosisOverview.getByTestId('agent-log-samples-panel')).toContainText('gpu · temperature');
  await expect(diagnosisOverview.getByTestId('agent-log-samples-panel')).toContainText('nvidia-smi');
  await expect(page.getByRole('main')).toContainText('PC Agent 실시간 진단 근거 1건 연결됨');

  await page.goto('/admin/as-tickets/qa-ticket-before');
  await page.getByLabel('검토 상태').selectOption('APPROVED');
  await page.getByLabel('지원 결정').selectOption('REMOTE_POSSIBLE');
  await page.getByLabel('위험도').selectOption('HIGH');
  await page.getByLabel('진단 적중 여부').selectOption('ACCURATE');
  await page.getByLabel('원격지원 링크').fill('https://support.example.test/session/qa-ticket-before');
  await page.getByLabel('관리자 메모').fill('Remote support link sent.');
  await page.getByRole('button', { name: '결정 저장' }).click();
  await expect(page.getByRole('main')).toContainText('결정 저장 완료');
  await expect(page.getByRole('main')).toContainText('원격 지원 가능');
  await expect(page.getByRole('main')).toContainText('Remote support link sent.');
  await expect(page.getByRole('main')).not.toContainText('undefined');
  expect(decisionPatchPayload).toMatchObject({
    reviewStatus: 'APPROVED',
    supportDecision: 'REMOTE_POSSIBLE',
    riskLevel: 'HIGH',
    diagnosticAccuracy: 'ACCURATE',
    remoteSupportLink: 'https://support.example.test/session/qa-ticket-before',
    adminNote: 'Remote support link sent.'
  });
  await page.screenshot({ path: `${screenshotDir}/03-admin-ticket-decision-fields.png`, fullPage: true });

  await page.evaluate(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });
  await page.goto('/support/qa-ticket-before');
  await expect(page.getByRole('main')).toContainText('승인됨');
  await expect(page.getByRole('main')).toContainText('원격 지원 가능');
  await expect(page.getByRole('main')).toContainText('https://support.example.test/session/qa-ticket-before');
  await page.screenshot({ path: `${screenshotDir}/04-support-ticket-after-decision.png`, fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/support/qa-ticket-before');
  await expect(page.getByRole('main')).toContainText('원격 지원 가능');
  await expect(page.getByRole('main')).toContainText('Remote support link sent.');
  await page.screenshot({ path: `${screenshotDir}/05-mobile-ticket.png`, fullPage: true });

  await page.evaluate(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
  });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/admin/as-tickets');
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'AS 티켓 qa-ticket-no-samples 삭제', exact: true }).click();
  await expect(page.getByRole('button', { name: 'AS 티켓 qa-ticket-no-samples 삭제', exact: true })).toHaveCount(0);
  expect(deletedTicketId).toBe('qa-ticket-no-samples');

  expect(apiCalls).toEqual(expect.arrayContaining([
    'GET /api/as-tickets/qa-ticket-before',
    'GET /api/admin/as-tickets/qa-ticket-before',
    'PATCH /api/admin/as-tickets/qa-ticket-before',
    'DELETE /api/admin/as-tickets/qa-ticket-no-samples'
  ]));
  expect(consoleErrors).toEqual([]);
});

function recordApiCall(apiCalls: string[], request: Request) {
  apiCalls.push(`${request.method()} ${new URL(request.url()).pathname}`);
}

function lastPathSegment(url: string) {
  const pathname = new URL(url).pathname;
  return pathname.slice(pathname.lastIndexOf('/') + 1);
}

async function fulfillTicket(route: Route, ticket: unknown) {
  if (!ticket) {
    await route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'NOT_FOUND', message: 'Ticket not found' })
    });
    return;
  }
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ticket) });
}
