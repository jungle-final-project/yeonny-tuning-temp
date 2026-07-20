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
      { sampleId: 'sample-1', text: 'gpu temperature reached 95c' }
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

test('captures Agent AS demo UI evidence and verifies admin decision reflection', async ({ page }) => {
  const consoleErrors: string[] = [];
  const apiCalls: string[] = [];
  const tickets = new Map<string, MockTicket>([
    [beforeDecisionTicket.id, beforeDecisionTicket],
    [afterDecisionTicket.id, afterDecisionTicket],
    [visitRecommendedTicket.id, visitRecommendedTicket]
  ]);
  let decisionPatchPayload: Record<string, unknown> | undefined;
  let remoteRequestPayload: Record<string, unknown> | undefined;
  let feedbackPayload: Record<string, unknown> | undefined;

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
  await page.goto('/admin/as-tickets/qa-ticket-before');
  await expect(page.getByRole('main')).toContainText('지원 결정 저장');
  await expect(page.getByRole('main')).toContainText('추천 서비스');
  await expect(page.getByRole('main')).toContainText('우선 진단만 받기');
  await expect(page.getByRole('main')).toContainText('GPU 온도 상승 신호');
  await expect(page.getByRole('main')).toContainText('GPU_THERMAL_SPIKE');
  await expect(page.getByRole('main')).toContainText('CHECK_GPU_DRIVER');
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

  expect(apiCalls).toEqual(expect.arrayContaining([
    'GET /api/as-tickets/qa-ticket-before',
    'GET /api/admin/as-tickets/qa-ticket-before',
    'PATCH /api/admin/as-tickets/qa-ticket-before'
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
