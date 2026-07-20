import { expect, test } from '@playwright/test';

const ticketId = '00000000-0000-4000-8000-000000006001';

test('guards AS AI chat when token is missing', async ({ page }) => {
  await page.goto('/support/ai-chat');

  await expect(page).toHaveURL(/\/login\?redirect=%2Fsupport%2Fai-chat/);
  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible();
});

test('renders AS AI chat response with Tool and RAG details', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/support/chat-sessions/current', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        contact: {
          id: 'support-session-1',
          asTicketId: ticketId,
          status: 'OPEN',
          title: '게임 중 프레임 급락'
        },
        messages: [],
        supportNewPath: '/support/new'
      })
    });
  });

  await page.route('**/api/ai/as-chat?**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        sessionId: null,
        asTicketId: ticketId,
        ticket: {
          id: ticketId,
          status: 'IN_PROGRESS',
          symptom: 'Game frame drops after 20 minutes.',
          logSummary: 'GPU temperature spikes.'
        },
        model: 'gpt-5.5',
        messages: [],
        evidence: [],
        toolResults: []
      })
    });
  });

  await page.route('**/api/ai/as-chat', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.fallback();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        sessionId: '3c560ea5-174f-4d79-b815-0a7d4d8dfc6f',
        asTicketId: ticketId,
        ticket: {
          id: ticketId,
          status: 'IN_PROGRESS',
          symptom: 'Game frame drops after 20 minutes.',
          logSummary: 'GPU temperature spikes.'
        },
        model: 'gpt-5.5',
        agentSessionId: 'edc93fe3-b556-4c57-bb48-41873b7d49e5',
        messages: [
          { id: 'm1', role: 'USER', content: 'GPU 온도가 높아요.' },
          { id: 'm2', role: 'ASSISTANT', content: 'GPU 과열 가능성을 먼저 확인하세요.', agentSessionId: 'edc93fe3-b556-4c57-bb48-41873b7d49e5' }
        ],
        assistantMessage: 'GPU 과열 가능성을 먼저 확인하세요.',
        causeCandidates: [{ label: 'GPU 과열 가능성', confidence: 'MEDIUM', reason: '온도 상승과 프레임 급락이 함께 나타납니다.' }],
        nextActions: [{ label: '팬과 먼지 확인', priority: 'HIGH', instruction: '흡기/배기 팬과 먼지를 확인하세요.' }],
        escalation: { required: false, reason: '추가 로그 확인 전 원격지원 필수는 아닙니다.' },
        ticketDraft: { symptomSummary: '게임 중 프레임 급락', recommendedLogRequest: 'GPU 온도 로그' },
        evidence: [{ id: 'e1', sourceId: 'support-guide-gpu-thermal', summary: 'GPU thermal evidence', score: 0.92 }],
        toolResults: [{ id: 't1', toolName: 'performance', status: 'WARN', confidence: 'MEDIUM', summary: 'Thermal throttling candidate' }]
      })
    });
  });

  await page.route('**/api/ai/as-chat/stream', async (route) => {
    const response = {
      sessionId: '3c560ea5-174f-4d79-b815-0a7d4d8dfc6f',
      asTicketId: ticketId,
      ticket: {
        id: ticketId,
        status: 'IN_PROGRESS',
        symptom: 'Game frame drops after 20 minutes.',
        logSummary: 'GPU temperature spikes.'
      },
      model: 'gpt-5.5',
      agentSessionId: 'edc93fe3-b556-4c57-bb48-41873b7d49e5',
      messages: [
        { id: 'm1', role: 'USER', content: 'GPU 온도가 높아요.' },
        { id: 'm2', role: 'ASSISTANT', content: 'GPU 과열 가능성을 먼저 확인하세요.', agentSessionId: 'edc93fe3-b556-4c57-bb48-41873b7d49e5' }
      ],
      assistantMessage: 'GPU 과열 가능성을 먼저 확인하세요.',
      causeCandidates: [{ label: 'GPU 과열 가능성', confidence: 'MEDIUM', reason: '온도 상승과 프레임 급락이 함께 나타납니다.' }],
      nextActions: [{ label: '팬과 먼지 확인', priority: 'HIGH', instruction: '흡기/배기 팬과 먼지를 확인하세요.' }],
      escalation: { required: false, reason: '추가 로그 확인 전 원격지원 필수는 아닙니다.' },
      ticketDraft: { symptomSummary: '게임 중 프레임 급락', recommendedLogRequest: 'GPU 온도 로그' },
      evidence: [{ id: 'e1', sourceId: 'support-guide-gpu-thermal', summary: 'GPU thermal evidence', score: 0.92 }],
      toolResults: [{ id: 't1', toolName: 'performance', status: 'WARN', confidence: 'MEDIUM', summary: 'Thermal throttling candidate' }]
    };
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: [
        'event: STARTED',
        'data: {"message":"AS 티켓과 사용자 세션을 확인하고 있습니다."}',
        '',
        'event: RAG_READY',
        'data: {"message":"관련 AS 근거를 찾았습니다."}',
        '',
        'event: DONE',
        `data: ${JSON.stringify(response)}`,
        '',
        ''
      ].join('\n')
    });
  });

  await page.goto('/support/ai-chat');

  await expect(page.getByRole('heading', { name: 'AS AI 챗봇' })).toBeVisible();
  await expect(page.getByText(ticketId)).toBeVisible();

  await page.getByPlaceholder('예: 게임 20분 뒤 프레임이 급락하고 GPU 온도가 95도까지 올라가요.').fill('GPU 온도가 높아요.');
  await page.getByRole('button', { name: '전송' }).click();

  await expect(page.getByText('GPU 과열 가능성을 먼저 확인하세요.')).toBeVisible();
  await expect(page.getByText('Thermal throttling candidate')).toBeVisible();
  await expect(page.getByText('support-guide-gpu-thermal')).toBeVisible();
});

test('does not request AS AI without a user-owned ticket and guides to support intake', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  let asChatRequestCount = 0;
  await page.route('**/api/support/chat-sessions/current', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ contact: null, messages: [], supportNewPath: '/support/new' })
    });
  });
  await page.route('**/api/ai/as-chat?**', async (route) => {
    asChatRequestCount += 1;
    await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
  });

  await page.goto('/support/ai-chat');

  await expect(page.getByText('연결된 AS 접수가 없습니다')).toBeVisible();
  await expect(page.getByRole('link', { name: 'AS 접수 시작하기' })).toHaveAttribute('href', '/support/new');
  await expect.poll(() => asChatRequestCount).toBe(0);
  await expect(page.getByRole('button', { name: '전송' })).toBeDisabled();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
});

test('loads AS AI chat ticket id from query string', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  const queryTicketId = '00000000-0000-4000-8000-000000006001';
  await page.route('**/api/ai/as-chat?**', async (route) => {
    expect(route.request().url()).toContain(`asTicketId=${queryTicketId}`);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        sessionId: null,
        asTicketId: queryTicketId,
        ticket: {
          id: queryTicketId,
          status: 'OPEN',
          symptom: 'GPU 온도 상승',
          logSummary: null
        },
        model: 'gpt-5.5',
        messages: [],
        evidence: [],
        toolResults: []
      })
    });
  });

  await page.goto(`/support/ai-chat?asTicketId=${queryTicketId}`);

  await expect(page.getByLabel('AS 티켓 번호')).toHaveValue(queryTicketId);
  await expect(page.getByText('GPU 온도 상승')).toBeVisible();
});
