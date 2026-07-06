import { expect, test, type Page } from '@playwright/test';

test('admin manages support chat rooms from the admin page', async ({ page }) => {
  let postedMessage: unknown = null;
  await mockOpenSupportWebSocket(page);
  await mockAdmin(page);
  await mockAdminSupportChats(page, (payload) => {
    postedMessage = payload;
  });

  await page.goto('/admin/support-chat-sessions');

  await expect(page.getByRole('heading', { name: '상담방 관리' })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'user-a@example.com', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'user-b@example.com', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'GPU 온도 상승' })).toBeVisible();

  await page.getByRole('button', { name: 'user-b@example.com 상담방 선택' }).click();
  await expect(page.getByRole('cell', { name: '전원이 갑자기 꺼집니다.' })).toBeVisible();

  await page.getByPlaceholder('관리자 답변을 입력하세요').fill('파워 로그를 추가로 확인하겠습니다.');
  await page.getByRole('button', { name: '답변 전송' }).click();

  await expect.poll(() => postedMessage).toEqual({ content: '파워 로그를 추가로 확인하겠습니다.' });
  await expect.poll(() => page.evaluate(() => {
    const sends = (window as unknown as { __supportChatSocketSends?: string[] }).__supportChatSocketSends ?? [];
    return sends.every((payload) => {
      try {
        return JSON.parse(payload).type === 'AUTH';
      } catch {
        return false;
      }
    });
  })).toBe(true);
  await expect(page.getByText('파워 로그를 추가로 확인하겠습니다.')).toBeVisible();
});

test('admin support chat keeps input and shows an error when REST send fails', async ({ page }) => {
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});
  await page.route('**/api/admin/support/chat-sessions/00000000-0000-4000-8000-000000009001/messages', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'CONFLICT_STATE',
          message: '종료된 AS 티켓 상담방에는 메시지를 보낼 수 없습니다.'
        })
      });
      return;
    }
    await route.fallback();
  });

  await page.goto('/admin/support-chat-sessions');
  await page.getByPlaceholder('관리자 답변을 입력하세요').fill('닫힌 티켓 답변');
  await page.getByRole('button', { name: '답변 전송' }).click();

  await expect(page.getByPlaceholder('관리자 답변을 입력하세요')).toHaveValue('닫힌 티켓 답변');
  await expect(page.getByText('종료된 AS 티켓 상담방에는 메시지를 보낼 수 없습니다.')).toBeVisible();
});

test('admin support chat limits answer input to 2000 characters', async ({ page }) => {
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});

  await page.goto('/admin/support-chat-sessions');

  await expect(page.getByPlaceholder('관리자 답변을 입력하세요')).toHaveAttribute('maxLength', '2000');
});

test('admin support chat updates the room list from websocket push', async ({ page }) => {
  await mockOpenSupportWebSocket(page);
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});

  await page.goto('/admin/support-chat-sessions');
  await expect(page.getByRole('cell', { name: '게임 실행 후 온도가 95도까지 올라갑니다.' })).toBeVisible();
  await expect(page.getByText('재연결 중')).toBeVisible();

  await page.evaluate(() => {
    const sockets = (window as unknown as { __supportChatSockets?: EventTarget[] }).__supportChatSockets ?? [];
    const socket = sockets[sockets.length - 1];
    socket?.dispatchEvent(new MessageEvent('message', {
      data: JSON.stringify({
        type: 'CHAT_UPDATED',
        detail: {
          contact: {
            id: '00000000-0000-4000-8000-000000009001',
            asTicketId: '00000000-0000-4000-8000-000000006001',
            status: 'ACTIVE',
            ticketStatus: 'OPEN',
            title: 'AS 상담방',
            symptom: 'GPU 온도 상승',
            lastMessagePreview: '방금 추가로 로그를 올렸습니다.',
            lastMessageAt: '2026-07-06T10:09:00Z',
            userUnreadCount: 0,
            adminUnreadCount: 5,
            canSendMessage: true,
            user: {
              id: '00000000-0000-4000-8000-000000001004',
              email: 'user-a@example.com',
              name: 'User A'
            }
          },
          messages: [],
          pollingIntervalMs: 5000
        }
      })
    }));
  });

  await expect(page.getByText('실시간 연결')).toBeVisible();
  await expect(page.getByRole('cell', { name: '방금 추가로 로그를 올렸습니다.' })).toBeVisible();
  await expect(page.getByRole('row', { name: /user-a@example.com/ })).toContainText('5');
});

test('admin support chat uses websocket tickets without leaking access tokens in the websocket url', async ({ page }) => {
  await mockOpenSupportWebSocket(page);
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});

  await page.goto('/admin/support-chat-sessions');

  await expect.poll(() => page.evaluate(() => {
    const urls = (window as unknown as { __supportChatSocketUrls?: string[] }).__supportChatSocketUrls ?? [];
    return urls[urls.length - 1] ?? '';
  })).not.toContain('token=');
  await expect.poll(() => page.evaluate(() => {
    const sends = (window as unknown as { __supportChatSocketSends?: string[] }).__supportChatSocketSends ?? [];
    return sends[0] ?? '';
  })).toBe(JSON.stringify({ type: 'AUTH', ticket: 'admin-ws-ticket-1' }));
});

test('admin auto-selected support chat detail is loaded without mark-read side effect', async ({ page }) => {
  const detailUrls: string[] = [];
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {}, (url) => detailUrls.push(url));

  await page.goto('/admin/support-chat-sessions');

  await expect.poll(() => detailUrls.find((url) => url.includes('00000000-0000-4000-8000-000000009001')) ?? '').toContain('markRead=false');
});

test('admin support chat shows a new message marker when reading older messages', async ({ page }) => {
  await mockOpenSupportWebSocket(page);
  await mockAdmin(page);
  await mockAdminLongSupportChat(page);

  await page.goto('/admin/support-chat-sessions');

  const scroller = page.getByTestId('admin-support-chat-messages');
  await expect.poll(async () => scroller.evaluate((element) => element.scrollTop > 0)).toBe(true);
  await scroller.evaluate((element) => {
    element.scrollTop = 0;
  });
  await pushAdminChatMessage(page, '관리자가 읽는 중 도착한 새 메시지');

  await expect(page.getByText('실시간 연결')).toBeVisible();
  await expect(page.getByText('새 메시지', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '새 메시지로 이동' })).toBeVisible();
  await expect.poll(async () => scroller.evaluate((element) => element.scrollTop < 8)).toBe(true);
});

test('admin guard reacts when auth is cleared', async ({ page }) => {
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});

  await page.goto('/admin/support-chat-sessions');
  await expect(page.getByRole('heading', { name: '상담방 관리' })).toBeVisible();

  await page.evaluate(async () => {
    const apiModulePath = '/src/lib/api.ts';
    const { clearToken } = await import(/* @vite-ignore */ apiModulePath);
    clearToken();
  });

  await expect(page.getByRole('heading', { name: '관리자 권한이 필요합니다' })).toBeVisible();
});

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

async function mockAdminSupportChats(
  page: Page,
  setPostedMessage: (payload: unknown) => void,
  onDetailRequest?: (url: string) => void
) {
  const roomA = {
    id: '00000000-0000-4000-8000-000000009001',
    asTicketId: '00000000-0000-4000-8000-000000006001',
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
    }
  };
  const roomB = {
    id: '00000000-0000-4000-8000-000000009002',
    asTicketId: '00000000-0000-4000-8000-000000006002',
    status: 'ACTIVE',
    ticketStatus: 'OPEN',
    title: 'AS 상담방',
    symptom: '전원이 갑자기 꺼집니다.',
    lastMessagePreview: '방금 다시 꺼졌습니다.',
    lastMessageAt: '2026-07-06T10:04:00Z',
    userUnreadCount: 0,
    adminUnreadCount: 1,
    canSendMessage: true,
    user: {
      id: '00000000-0000-4000-8000-000000001005',
      email: 'user-b@example.com',
      name: 'User B'
    }
  };
  const detailA = {
    contact: roomA,
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
      }
    ],
    pollingIntervalMs: 5000
  };
  const detailB = {
    contact: roomB,
    messages: [
      {
        id: '00000000-0000-4000-8000-000000009201',
        role: 'SYSTEM',
        content: '상담방이 생성되었습니다. 문의 내용을 남기면 담당자가 확인합니다.',
        createdAt: '2026-07-06T10:00:00Z'
      },
      {
        id: '00000000-0000-4000-8000-000000009202',
        role: 'USER',
        content: '방금 다시 꺼졌습니다.',
        senderName: 'User B',
        createdAt: '2026-07-06T10:04:00Z'
      }
    ],
    pollingIntervalMs: 5000
  };

  await page.route('**/api/admin/support/chat-sessions', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [roomA, roomB], pollingIntervalMs: 5000 })
      });
      return;
    }
    await route.fallback();
  });
  await page.route('**/api/admin/support/chat-sessions/00000000-0000-4000-8000-000000009001**', async (route) => {
    onDetailRequest?.(route.request().url());
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detailA) });
  });
  await page.route('**/api/admin/support/chat-sessions/00000000-0000-4000-8000-000000009002**', async (route) => {
    onDetailRequest?.(route.request().url());
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detailB) });
      return;
    }
    await route.fallback();
  });
  await page.route('**/api/admin/support/chat-sessions/00000000-0000-4000-8000-000000009002/messages', async (route) => {
    if (route.request().method() === 'POST') {
      const payload = route.request().postDataJSON();
      setPostedMessage(payload);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ...detailB,
          messages: [
            ...detailB.messages,
            {
              id: '00000000-0000-4000-8000-000000009203',
              role: 'ADMIN',
              content: String((payload as { content?: string }).content ?? ''),
              senderName: 'BuildGraph Admin',
              createdAt: '2026-07-06T10:05:00Z'
            }
          ]
        })
      });
      return;
    }
    await route.fallback();
  });
  await page.route('**/api/admin/support/chat-sessions/00000000-0000-4000-8000-000000009001/ws-ticket', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ticket: 'admin-ws-ticket-1',
          expiresAt: '2026-07-06T10:01:00Z',
          expiresInSeconds: 60
        })
      });
      return;
    }
    await route.fallback();
  });
  await page.route('**/api/admin/support/chat-sessions/00000000-0000-4000-8000-000000009002/ws-ticket', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ticket: 'admin-ws-ticket-2',
          expiresAt: '2026-07-06T10:01:00Z',
          expiresInSeconds: 60
        })
      });
      return;
    }
    await route.fallback();
  });
}

async function mockAdminLongSupportChat(page: Page) {
  const room = {
    id: '00000000-0000-4000-8000-000000009001',
    asTicketId: '00000000-0000-4000-8000-000000006001',
    status: 'ACTIVE',
    ticketStatus: 'OPEN',
    title: 'AS 상담방',
    symptom: 'GPU 온도 상승',
    lastMessagePreview: '긴 대화 확인 중입니다.',
    lastMessageAt: '2026-07-06T10:35:00Z',
    userUnreadCount: 0,
    adminUnreadCount: 2,
    canSendMessage: true,
    user: {
      id: '00000000-0000-4000-8000-000000001004',
      email: 'user-a@example.com',
      name: 'User A'
    }
  };
  const messages = Array.from({ length: 36 }, (_, index) => ({
    id: `00000000-0000-4000-8000-00000002${String(index).padStart(4, '0')}`,
    role: index === 0 ? 'SYSTEM' : index % 2 === 0 ? 'ADMIN' : 'USER',
    content: index === 35 ? '관리자 화면의 가장 최신 메시지입니다.' : `관리자 상담 메시지 ${index + 1}`,
    senderName: index % 2 === 0 ? 'BuildGraph Admin' : 'User A',
    createdAt: `2026-07-06T10:${String(index).padStart(2, '0')}:00Z`
  }));
  const detail = { contact: room, messages, pollingIntervalMs: 5000 };

  await page.route('**/api/admin/support/chat-sessions', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [room], pollingIntervalMs: 5000 })
      });
      return;
    }
    await route.fallback();
  });
  await page.route('**/api/admin/support/chat-sessions/00000000-0000-4000-8000-000000009001**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detail) });
      return;
    }
    await route.fallback();
  });
  await page.route('**/api/admin/support/chat-sessions/00000000-0000-4000-8000-000000009001/ws-ticket', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ticket: 'admin-ws-ticket-1',
          expiresAt: '2026-07-06T10:01:00Z',
          expiresInSeconds: 60
        })
      });
      return;
    }
    await route.fallback();
  });
}

async function pushAdminChatMessage(page: Page, content: string) {
  await page.evaluate((messageContent) => {
    const sockets = (window as unknown as { __supportChatSockets?: EventTarget[] }).__supportChatSockets ?? [];
    const socket = sockets[sockets.length - 1];
    socket?.dispatchEvent(new MessageEvent('message', {
      data: JSON.stringify({
        type: 'CHAT_UPDATED',
        detail: {
          contact: {
            id: '00000000-0000-4000-8000-000000009001',
            asTicketId: '00000000-0000-4000-8000-000000006001',
            status: 'ACTIVE',
            ticketStatus: 'OPEN',
            title: 'AS 상담방',
            symptom: 'GPU 온도 상승',
            lastMessagePreview: messageContent,
            lastMessageAt: '2026-07-06T10:40:00Z',
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
            ...Array.from({ length: 36 }, (_, index) => ({
              id: `00000000-0000-4000-8000-00000002${String(index).padStart(4, '0')}`,
              role: index === 0 ? 'SYSTEM' : index % 2 === 0 ? 'ADMIN' : 'USER',
              content: index === 35 ? '관리자 화면의 가장 최신 메시지입니다.' : `관리자 상담 메시지 ${index + 1}`,
              senderName: index % 2 === 0 ? 'BuildGraph Admin' : 'User A',
              createdAt: `2026-07-06T10:${String(index).padStart(2, '0')}:00Z`
            })),
            {
              id: '00000000-0000-4000-8000-000000029999',
              role: 'USER',
              content: messageContent,
              senderName: 'User A',
              createdAt: '2026-07-06T10:40:00Z'
            }
          ],
          pollingIntervalMs: 5000
        }
      })
    }));
  }, content);
}
