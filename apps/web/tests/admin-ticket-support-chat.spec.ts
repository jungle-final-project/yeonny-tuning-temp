import { expect, test, type Page } from '@playwright/test';

// AS 티켓 상세(/admin/as-tickets/:ticketId)에 박힌 상담방 채팅 검증.
// 전체 상담 콘솔 동작은 admin-support-chat.spec.ts가 담당하고, 여기서는
// 티켓→상담방 매칭, 지원 결정 폼 숨김, 전송, 빈/종료 상태, WS 실시간 수신만 본다.

const TICKET_ID = '00000000-0000-4000-8000-000000006001';
const ROOM_ID = '00000000-0000-4000-8000-000000009001';

const adminTicket = {
  id: TICKET_ID,
  status: 'OPEN',
  analysisStatus: 'RULE_READY',
  reviewStatus: 'REQUIRED',
  supportDecision: 'NEEDS_MORE_INFO',
  riskLevel: 'MEDIUM',
  symptom: 'GPU 온도 상승',
  logUploadId: null,
  logSummaryText: null,
  logSummary: null,
  supportRouting: {
    recommendedDecision: 'NEEDS_MORE_INFO',
    confidence: 'LOW',
    reasonCodes: [],
    remoteActions: [],
    visitReasons: [],
    blockingFactors: [],
    recommendedService: 'DIAGNOSIS_ONLY',
    recommendedServiceLabel: '우선 진단만 받기',
    requiresAdminApproval: true
  },
  assignedAdminId: null,
  causeCandidates: [],
  upgradeCandidates: [],
  adminNote: null,
  remoteSupportLink: null,
  remoteSupportStatus: null,
  visitSupportRequired: false,
  createdAt: '2026-07-06T10:00:00Z'
};

function chatRoom(overrides: Record<string, unknown> = {}) {
  return {
    id: ROOM_ID,
    asTicketId: TICKET_ID,
    status: 'ACTIVE',
    ticketStatus: 'OPEN',
    title: 'AS 상담방',
    symptom: 'GPU 온도 상승',
    lastMessagePreview: '게임 실행 후 온도가 95도까지 올라갑니다.',
    lastMessageAt: '2026-07-06T10:02:00Z',
    userUnreadCount: 0,
    adminUnreadCount: 2,
    canSendMessage: true,
    user: {
      id: '00000000-0000-4000-8000-000000001004',
      email: 'user-a@example.com',
      name: 'User A'
    },
    ...overrides
  };
}

function baseMessages() {
  return [
    {
      id: '00000000-0000-4000-8000-000000009101',
      role: 'SYSTEM',
      content: '상담방이 생성되었습니다. 문의 내용을 남기면 담당자가 확인합니다.',
      createdAt: '2026-07-06T10:00:00Z'
    },
    {
      id: '00000000-0000-4000-8000-000000009102',
      role: 'USER',
      content: '게임 실행 후 온도가 95도까지 올라갑니다.',
      senderName: 'User A',
      createdAt: '2026-07-06T10:02:00Z'
    }
  ];
}

async function mockAdmin(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: '00000000-0000-4000-8000-000000000001',
      email: 'admin@example.com',
      name: 'BuildGraph Admin',
      role: 'ADMIN'
    }));
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-4000-8000-000000000001',
        email: 'admin@example.com',
        name: 'BuildGraph Admin',
        role: 'ADMIN'
      })
    });
  });
}

async function mockTicket(page: Page) {
  await page.route(`**/api/admin/as-tickets/${TICKET_ID}`, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(adminTicket) });
  });
}

async function mockChat(
  page: Page,
  options: {
    room: Record<string, unknown>;
    messages: Array<Record<string, unknown>>;
    onPostedMessage?: (payload: unknown) => void;
    wsTicket?: string | null;
  }
) {
  let messages = options.messages;
  await page.route('**/api/admin/support/chat-sessions', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [options.room], pollingIntervalMs: 5000 })
    });
  });
  await page.route(`**/api/admin/support/chat-sessions/${ROOM_ID}**`, async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/ws-ticket')) {
      // wsTicket이 없으면 ticket 필드 없이 응답한다 — openSupportChatSocket은 조용히 폴링으로 남는다.
      const body = options.wsTicket
        ? { ticket: options.wsTicket, expiresAt: '2026-07-06T10:01:00Z', expiresInSeconds: 60 }
        : {};
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      return;
    }
    if (route.request().method() === 'POST' && url.pathname.endsWith('/messages')) {
      const payload = route.request().postDataJSON() as Record<string, unknown>;
      options.onPostedMessage?.(payload);
      messages = [
        ...messages,
        {
          id: `00000000-0000-4000-8000-00000000${9200 + messages.length}`,
          role: 'ADMIN',
          content: String(payload.content ?? ''),
          senderName: 'BuildGraph Admin',
          createdAt: '2026-07-06T10:10:00Z'
        }
      ];
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ contact: options.room, messages, pollingIntervalMs: 5000 })
    });
  });
}

// admin-support-chat.spec.ts의 FakeWebSocket 하네스와 동일 (헬퍼가 module-private라 복제).
async function mockOpenSupportWebSocket(page: Page) {
  await page.addInitScript(() => {
    const sends: string[] = [];
    const sockets: EventTarget[] = [];
    const urls: string[] = [];
    class FakeWebSocket extends EventTarget {
      static CONNECTING = 0;
      static OPEN = 1;
      static CLOSING = 2;
      static CLOSED = 3;
      readyState = FakeWebSocket.OPEN;
      url: string;

      constructor(url: string) {
        super();
        this.url = url;
        sockets.push(this);
        urls.push(url);
        setTimeout(() => this.dispatchEvent(new Event('open')), 0);
      }

      send(payload: string) {
        sends.push(payload);
      }

      close() {
        this.readyState = FakeWebSocket.CLOSED;
        this.dispatchEvent(new Event('close'));
      }
    }
    (window as unknown as { WebSocket: typeof WebSocket }).WebSocket = FakeWebSocket as unknown as typeof WebSocket;
    (window as unknown as { __supportChatSocketSends?: string[] }).__supportChatSocketSends = sends;
    (window as unknown as { __supportChatSockets?: EventTarget[] }).__supportChatSockets = sockets;
    (window as unknown as { __supportChatSocketUrls?: string[] }).__supportChatSocketUrls = urls;
  });
}

test('ticket detail embeds the support chat in place of the decision form and sends replies', async ({ page }) => {
  let postedMessage: unknown;
  await mockAdmin(page);
  await mockTicket(page);
  await mockChat(page, {
    room: chatRoom(),
    messages: baseMessages(),
    onPostedMessage: (payload) => { postedMessage = payload; }
  });

  await page.goto(`/admin/as-tickets/${TICKET_ID}`);
  await expect(page.getByRole('heading', { name: 'AS 티켓 상세' })).toBeVisible();

  // 지원 결정 저장 폼은 코드만 남고 렌더링되지 않는다.
  await expect(page.getByRole('main')).not.toContainText('지원 결정 저장');
  await expect(page.getByRole('button', { name: '결정 저장' })).toHaveCount(0);

  const chat = page.getByTestId('admin-ticket-support-chat');
  await expect(chat.getByText('게임 실행 후 온도가 95도까지 올라갑니다.')).toBeVisible();
  await expect(chat.getByRole('link', { name: '상담방 관리로 이동' })).toHaveAttribute('href', '/admin/support-chat-sessions');

  await chat.getByPlaceholder('관리자 답변을 입력하세요').fill('파워 로그를 추가로 확인하겠습니다.');
  await chat.getByRole('button', { name: '답변 전송' }).click();
  await expect.poll(() => postedMessage).toEqual({ content: '파워 로그를 추가로 확인하겠습니다.' });
  await expect(chat.getByText('파워 로그를 추가로 확인하겠습니다.')).toBeVisible();
  await expect(chat.getByPlaceholder('관리자 답변을 입력하세요')).toHaveValue('');
});

test('shows an empty state when the ticket has no chat session yet', async ({ page }) => {
  await mockAdmin(page);
  await mockTicket(page);
  await mockChat(page, {
    room: chatRoom({ asTicketId: '00000000-0000-4000-8000-000000006999' }),
    messages: baseMessages()
  });

  await page.goto(`/admin/as-tickets/${TICKET_ID}`);
  const chat = page.getByTestId('admin-ticket-support-chat');
  await expect(chat).toContainText('상담방 없음');
  await expect(chat.getByPlaceholder('관리자 답변을 입력하세요')).toHaveCount(0);
});

test('keeps history visible but blocks input for a closed chat session', async ({ page }) => {
  await mockAdmin(page);
  await mockTicket(page);
  await mockChat(page, {
    room: chatRoom({ canSendMessage: false, status: 'CLOSED', ticketStatus: 'CLOSED' }),
    messages: baseMessages()
  });

  await page.goto(`/admin/as-tickets/${TICKET_ID}`);
  const chat = page.getByTestId('admin-ticket-support-chat');
  await expect(chat).toContainText('종료된 상담방');
  await expect(chat.getByText('게임 실행 후 온도가 95도까지 올라갑니다.')).toBeVisible();
  await expect(chat.getByPlaceholder('관리자 답변을 입력하세요')).toHaveCount(0);
});

test('receives realtime chat updates through the detail websocket', async ({ page }) => {
  await mockOpenSupportWebSocket(page);
  await mockAdmin(page);
  await mockTicket(page);
  await mockChat(page, {
    room: chatRoom(),
    messages: baseMessages(),
    wsTicket: 'admin-ws-ticket-1'
  });

  await page.goto(`/admin/as-tickets/${TICKET_ID}`);
  const chat = page.getByTestId('admin-ticket-support-chat');
  await expect(chat.getByText('게임 실행 후 온도가 95도까지 올라갑니다.')).toBeVisible();
  // '실시간 연결' 표시는 소켓 open이 아니라 첫 CHAT_UPDATED 수신 시점이다(openSupportChatSocket.onOpen 규약).
  // 먼저 AUTH frame 전송을 확인한 뒤 서버 push를 흉내 내고 나서 상태와 새 메시지를 검증한다.
  await expect.poll(async () => page.evaluate(() => (
    (window as unknown as { __supportChatSocketSends?: string[] }).__supportChatSocketSends ?? []
  ))).toEqual(expect.arrayContaining([JSON.stringify({ type: 'AUTH', ticket: 'admin-ws-ticket-1' })]));

  await page.evaluate(({ roomId, ticketId }) => {
    const sockets = (window as unknown as { __supportChatSockets?: EventTarget[] }).__supportChatSockets ?? [];
    const socket = sockets[sockets.length - 1];
    socket?.dispatchEvent(new MessageEvent('message', {
      data: JSON.stringify({
        type: 'CHAT_UPDATED',
        detail: {
          contact: {
            id: roomId,
            asTicketId: ticketId,
            status: 'ACTIVE',
            ticketStatus: 'OPEN',
            title: 'AS 상담방',
            symptom: 'GPU 온도 상승',
            lastMessagePreview: '방금 또 온도가 튀었어요.',
            lastMessageAt: '2026-07-06T10:20:00Z',
            userUnreadCount: 0,
            adminUnreadCount: 3,
            canSendMessage: true,
            user: {
              id: '00000000-0000-4000-8000-000000001004',
              email: 'user-a@example.com',
              name: 'User A'
            }
          },
          messages: [
            {
              id: '00000000-0000-4000-8000-000000009101',
              role: 'SYSTEM',
              content: '상담방이 생성되었습니다. 문의 내용을 남기면 담당자가 확인합니다.',
              createdAt: '2026-07-06T10:00:00Z'
            },
            {
              id: '00000000-0000-4000-8000-000000009102',
              role: 'USER',
              content: '게임 실행 후 온도가 95도까지 올라갑니다.',
              senderName: 'User A',
              createdAt: '2026-07-06T10:02:00Z'
            },
            {
              id: '00000000-0000-4000-8000-000000009103',
              role: 'USER',
              content: '방금 또 온도가 튀었어요.',
              senderName: 'User A',
              createdAt: '2026-07-06T10:20:00Z'
            }
          ],
          pollingIntervalMs: 5000
        }
      })
    }));
  }, { roomId: ROOM_ID, ticketId: TICKET_ID });

  await expect(chat.getByText('방금 또 온도가 튀었어요.')).toBeVisible();
  await expect(chat).toContainText('실시간 연결');
});
