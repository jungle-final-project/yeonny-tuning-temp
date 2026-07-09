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

test('admin support chat updates an existing room from the queue websocket push', async ({ page }) => {
  await mockOpenSupportWebSocket(page);
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});

  await page.goto('/admin/support-chat-sessions');
  await expect(page.getByRole('cell', { name: '게임 실행 후 온도가 95도까지 올라갑니다.' })).toBeVisible();
  const roomListPanel = page.locator('section').filter({ has: page.getByRole('heading', { name: '상담방 목록' }) });
  await expect(roomListPanel.getByText('재연결 중', { exact: true })).toBeVisible();
  await expect.poll(() => page.evaluate(() => {
    const urls = (window as unknown as { __supportChatSocketUrls?: string[] }).__supportChatSocketUrls ?? [];
    return urls.some((url) => url.includes('/ws/admin/support-chat-queue'));
  })).toBe(true);

  await pushAdminQueueReady(page);
  await pushAdminQueueUpdated(page, {
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
  });

  await expect(roomListPanel.getByText('실시간 연결', { exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: '방금 추가로 로그를 올렸습니다.' })).toBeVisible();
  await expect(page.getByRole('row', { name: /user-a@example.com/ })).toContainText('5');
});

test('admin support chat updates the room list from the queue websocket patch', async ({ page }) => {
  await mockOpenSupportWebSocket(page);
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});

  await page.goto('/admin/support-chat-sessions');
  await expect(page.getByRole('cell', { name: 'user-c@example.com', exact: true })).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => {
    const urls = (window as unknown as { __supportChatSocketUrls?: string[] }).__supportChatSocketUrls ?? [];
    return urls.some((url) => url.includes('/ws/admin/support-chat-queue'));
  })).toBe(true);

  await pushAdminQueueUpdated(page, {
    id: '00000000-0000-4000-8000-000000009003',
    asTicketId: '00000000-0000-4000-8000-000000006003',
    status: 'ACTIVE',
    ticketStatus: 'OPEN',
    title: 'AS 상담방',
    symptom: '새 상담방 증상',
    lastMessagePreview: '새 상담방에서 메시지가 도착했습니다.',
    lastMessageAt: '2026-07-06T10:10:00Z',
    userUnreadCount: 0,
    adminUnreadCount: 7,
    canSendMessage: true,
    user: {
      id: '00000000-0000-4000-8000-000000001006',
      email: 'user-c@example.com',
      name: 'User C'
    }
  });

  await expect(page.getByRole('cell', { name: 'user-c@example.com', exact: true })).toBeVisible();
  await expect(page.getByRole('row', { name: /user-c@example.com/ })).toContainText('7');
  await expect(page.getByRole('cell', { name: '새 상담방에서 메시지가 도착했습니다.' })).toBeVisible();
});

test('admin support chat removes rooms from the list when the queue websocket sends a removal patch', async ({ page }) => {
  await mockOpenSupportWebSocket(page);
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});

  await page.goto('/admin/support-chat-sessions');
  await expect(page.getByRole('cell', { name: 'user-b@example.com', exact: true })).toBeVisible();
  await expect.poll(() => page.evaluate(() => {
    const urls = (window as unknown as { __supportChatSocketUrls?: string[] }).__supportChatSocketUrls ?? [];
    return urls.some((url) => url.includes('/ws/admin/support-chat-queue'));
  })).toBe(true);

  await pushAdminQueueRemoved(page, '00000000-0000-4000-8000-000000009002');

  await expect(page.getByRole('cell', { name: 'user-b@example.com', exact: true })).toHaveCount(0);
});

test('admin support chat can delete a room and keep the archived detail read-only', async ({ page }) => {
  let deleteCalls = 0;
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});
  await page.route('**/api/admin/support/chat-sessions/00000000-0000-4000-8000-000000009001', async (route) => {
    if (route.request().method() === 'DELETE') {
      deleteCalls += 1;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(deletedAdminChatDetail())
      });
      return;
    }
    await route.fallback();
  });
  page.on('dialog', (dialog) => dialog.accept());

  await page.goto('/admin/support-chat-sessions');
  await expect(page.getByRole('button', { name: '상담방 삭제' })).toBeVisible();
  await page.getByRole('button', { name: '상담방 삭제' }).click();

  await expect.poll(() => deleteCalls).toBe(1);
  await expect(page.getByRole('cell', { name: 'user-a@example.com', exact: true })).toHaveCount(0);
  await expect(page.getByText('상담방 삭제됨')).toBeVisible();
  await expect(page.getByText('관리자가 상담방을 삭제했습니다. 새 AS 접수가 가능합니다.')).toBeVisible();
  await expect(page.getByPlaceholder('관리자 답변을 입력하세요')).toHaveCount(0);
});

test('admin support chat keeps selection and input when room delete fails', async ({ page }) => {
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});
  await page.route('**/api/admin/support/chat-sessions/00000000-0000-4000-8000-000000009001', async (route) => {
    if (route.request().method() === 'DELETE') {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'UPSTREAM_ERROR',
          message: '상담방 삭제에 실패했습니다.'
        })
      });
      return;
    }
    await route.fallback();
  });
  page.on('dialog', (dialog) => dialog.accept());

  await page.goto('/admin/support-chat-sessions');
  await page.getByPlaceholder('관리자 답변을 입력하세요').fill('삭제 전 작성 중인 답변');
  await page.getByRole('button', { name: '상담방 삭제' }).click();

  await expect(page.getByRole('cell', { name: 'user-a@example.com', exact: true })).toBeVisible();
  await expect(page.getByPlaceholder('관리자 답변을 입력하세요')).toHaveValue('삭제 전 작성 중인 답변');
  await expect(page.getByText('상담방 삭제에 실패했습니다.')).toBeVisible();
});

test('admin support chat can schedule and cancel a visit reservation', async ({ page }) => {
  let schedulePayload: unknown = null;
  let cancelCalls = 0;
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});
  await page.route('**/api/admin/support/chat-sessions/00000000-0000-4000-8000-000000009001/visit-reservation', async (route) => {
    if (route.request().method() === 'PUT') {
      schedulePayload = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(adminChatDetailWithReservation('SCHEDULED'))
      });
      return;
    }
    if (route.request().method() === 'DELETE') {
      cancelCalls += 1;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(adminChatDetailWithReservation('CANCELLED'))
      });
      return;
    }
    await route.fallback();
  });

  await page.goto('/admin/support-chat-sessions');
  await page.getByLabel('방문 예약 시각').fill('2099-07-10T14:30');
  await page.getByLabel('기사 메모').fill('방문 전 연락');
  await page.getByRole('button', { name: '예약 확정' }).click();

  await expect.poll(() => schedulePayload).toEqual({
    scheduledAt: '2099-07-10T14:30:00+09:00',
    technicianNote: '방문 전 연락'
  });
  const visitPanel = page.locator('section').filter({ hasText: '기사 메모' });
  await expect(visitPanel.getByText('예약 확정됨', { exact: true })).toBeVisible();
  await expect(visitPanel.getByText('2099-07-10 14:30', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: '예약 취소' }).click();
  await expect.poll(() => cancelCalls).toBe(1);
  await expect(visitPanel.getByText('예약 취소됨', { exact: true })).toBeVisible();
});

test('admin support chat keeps reservation input and shows an error when scheduling fails', async ({ page }) => {
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});
  await page.route('**/api/admin/support/chat-sessions/00000000-0000-4000-8000-000000009001/visit-reservation', async (route) => {
    if (route.request().method() === 'PUT') {
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'VALIDATION_ERROR',
          message: '방문 예약 시각은 미래여야 합니다.'
        })
      });
      return;
    }
    await route.fallback();
  });

  await page.goto('/admin/support-chat-sessions');
  await page.getByLabel('방문 예약 시각').fill('2099-07-10T14:30');
  await page.getByLabel('기사 메모').fill('방문 전 연락');
  await page.getByRole('button', { name: '예약 확정' }).click();

  await expect(page.getByLabel('방문 예약 시각')).toHaveValue('2099-07-10T14:30');
  await expect(page.getByLabel('기사 메모')).toHaveValue('방문 전 연락');
  await expect(page.getByText('방문 예약 시각은 미래여야 합니다.')).toBeVisible();
});

test('admin support chat updates visit reservation in the room list from queue patch', async ({ page }) => {
  await mockOpenSupportWebSocket(page);
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});

  await page.goto('/admin/support-chat-sessions');
  await expect.poll(() => page.evaluate(() => {
    const urls = (window as unknown as { __supportChatSocketUrls?: string[] }).__supportChatSocketUrls ?? [];
    return urls.some((url) => url.includes('/ws/admin/support-chat-queue'));
  })).toBe(true);
  await pushAdminQueueUpdated(page, {
    id: '00000000-0000-4000-8000-000000009001',
    asTicketId: '00000000-0000-4000-8000-000000006001',
    status: 'ACTIVE',
    ticketStatus: 'OPEN',
    title: 'AS 상담방',
    symptom: 'GPU 온도 상승',
    lastMessagePreview: '방문 지원 예약이 확정되었습니다: 2099-07-10 14:30',
    lastMessageAt: '2026-07-06T10:10:00Z',
    userUnreadCount: 0,
    adminUnreadCount: 2,
    canSendMessage: true,
    visitReservation: {
      id: '00000000-0000-4000-8000-000000008001',
      status: 'SCHEDULED',
      scheduledAt: '2099-07-10T14:30:00+09:00'
    },
    user: {
      id: '00000000-0000-4000-8000-000000001004',
      email: 'user-a@example.com',
      name: 'User A'
    }
  });

  await expect(page.getByRole('row', { name: /user-a@example.com/ })).toContainText('예약 확정');
  await expect(page.getByRole('row', { name: /user-a@example.com/ })).toContainText('2099-07-10 14:30');
});

test('admin support chat uses websocket tickets without leaking access tokens in the websocket url', async ({ page }) => {
  await mockOpenSupportWebSocket(page);
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});

  await page.goto('/admin/support-chat-sessions');

  await expect.poll(() => page.evaluate(() => {
    const urls = (window as unknown as { __supportChatSocketUrls?: string[] }).__supportChatSocketUrls ?? [];
    return urls.find((url) => url.includes('/ws/support-chat')) ?? '';
  })).not.toContain('token=');
  await expect.poll(() => page.evaluate(() => {
    const sends = (window as unknown as { __supportChatSocketSends?: string[] }).__supportChatSocketSends ?? [];
    return sends.includes(JSON.stringify({ type: 'AUTH', ticket: 'admin-ws-ticket-1' }));
  })).toBe(true);
});

test('admin support chat uses a dedicated queue websocket without leaking access tokens', async ({ page }) => {
  await mockOpenSupportWebSocket(page);
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {});

  await page.goto('/admin/support-chat-sessions');

  await expect.poll(() => page.evaluate(() => {
    const urls = (window as unknown as { __supportChatSocketUrls?: string[] }).__supportChatSocketUrls ?? [];
    return urls.find((url) => url.includes('/ws/admin/support-chat-queue')) ?? '';
  })).not.toContain('token=');
  await expect.poll(() => page.evaluate(() => {
    const sends = (window as unknown as { __supportChatSocketSends?: string[] }).__supportChatSocketSends ?? [];
    return sends.includes(JSON.stringify({ type: 'AUTH', ticket: 'admin-queue-ws-ticket-1' }));
  })).toBe(true);
});

test('admin support chat reconnects the queue websocket with a fresh ticket', async ({ page }) => {
  let queueTicketCalls = 0;
  await mockOpenSupportWebSocket(page);
  await mockAdmin(page);
  await mockAdminSupportChats(page, () => {}, undefined, () => {
    queueTicketCalls += 1;
    return `admin-queue-ws-ticket-${queueTicketCalls}`;
  });

  await page.goto('/admin/support-chat-sessions');

  await expect.poll(() => queueTicketCalls > 0).toBe(true);
  const beforeReconnect = queueTicketCalls;
  const expectedTicket = `admin-queue-ws-ticket-${beforeReconnect + 1}`;
  await page.evaluate(() => {
    const sockets = (window as unknown as { __supportChatSockets?: EventTarget[]; __supportChatSocketUrls?: string[] }).__supportChatSockets ?? [];
    const urls = (window as unknown as { __supportChatSocketUrls?: string[] }).__supportChatSocketUrls ?? [];
    for (let i = 0; i < urls.length; i += 1) {
      if (urls[i].includes('/ws/admin/support-chat-queue')) {
        (sockets[i] as unknown as { close?: () => void })?.close?.();
      }
    }
  });

  await expect.poll(() => queueTicketCalls).toBeGreaterThan(beforeReconnect);
  await expect.poll(() => page.evaluate(() => {
    const sends = (window as unknown as { __supportChatSocketSends?: string[] }).__supportChatSocketSends ?? [];
    return sends;
  })).toContain(JSON.stringify({ type: 'AUTH', ticket: expectedTicket }));
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
  onDetailRequest?: (url: string) => void,
  issueQueueTicket: () => string = () => 'admin-queue-ws-ticket-1'
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
  await page.route('**/api/admin/support/chat-sessions/ws-ticket', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ticket: issueQueueTicket(),
          expiresAt: '2026-07-06T10:01:00Z',
          expiresInSeconds: 60
        })
      });
      return;
    }
    await route.fallback();
  });
  await page.route('**/api/admin/support/chat-sessions/00000000-0000-4000-8000-000000009001**', async (route) => {
    onDetailRequest?.(route.request().url());
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detailA) });
      return;
    }
    await route.fallback();
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
  await page.route('**/api/admin/support/chat-sessions/ws-ticket', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ticket: 'admin-queue-ws-ticket-1',
          expiresAt: '2026-07-06T10:01:00Z',
          expiresInSeconds: 60
        })
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

async function pushAdminQueueUpdated(page: Page, contact: Record<string, unknown>) {
  await page.evaluate((nextContact) => {
    const sockets = (window as unknown as { __supportChatSockets?: EventTarget[] }).__supportChatSockets ?? [];
    const urls = (window as unknown as { __supportChatSocketUrls?: string[] }).__supportChatSocketUrls ?? [];
    let index = -1;
    for (let i = urls.length - 1; i >= 0; i -= 1) {
      if (urls[i].includes('/ws/admin/support-chat-queue')) {
        index = i;
        break;
      }
    }
    sockets[index]?.dispatchEvent(new MessageEvent('message', {
      data: JSON.stringify({
        type: 'SUPPORT_CHAT_QUEUE_UPDATED',
        contact: nextContact
      })
    }));
  }, contact);
}

async function pushAdminQueueReady(page: Page) {
  await page.evaluate(() => {
    const sockets = (window as unknown as { __supportChatSockets?: EventTarget[] }).__supportChatSockets ?? [];
    const urls = (window as unknown as { __supportChatSocketUrls?: string[] }).__supportChatSocketUrls ?? [];
    let index = -1;
    for (let i = urls.length - 1; i >= 0; i -= 1) {
      if (urls[i].includes('/ws/admin/support-chat-queue')) {
        index = i;
        break;
      }
    }
    sockets[index]?.dispatchEvent(new MessageEvent('message', {
      data: JSON.stringify({
        type: 'SUPPORT_CHAT_QUEUE_READY'
      })
    }));
  });
}

async function pushAdminQueueRemoved(page: Page, id: string) {
  await page.evaluate((removedId) => {
    const sockets = (window as unknown as { __supportChatSockets?: EventTarget[] }).__supportChatSockets ?? [];
    const urls = (window as unknown as { __supportChatSocketUrls?: string[] }).__supportChatSocketUrls ?? [];
    let index = -1;
    for (let i = urls.length - 1; i >= 0; i -= 1) {
      if (urls[i].includes('/ws/admin/support-chat-queue')) {
        index = i;
        break;
      }
    }
    sockets[index]?.dispatchEvent(new MessageEvent('message', {
      data: JSON.stringify({
        type: 'SUPPORT_CHAT_QUEUE_REMOVED',
        id: removedId
      })
    }));
  }, id);
}

function adminChatDetailWithReservation(status: string) {
  return {
    contact: {
      id: '00000000-0000-4000-8000-000000009001',
      asTicketId: '00000000-0000-4000-8000-000000006001',
      status: 'ACTIVE',
      ticketStatus: 'OPEN',
      title: 'AS 상담방',
      symptom: 'GPU 온도 상승',
      lastMessagePreview: status === 'CANCELLED' ? '방문 지원 예약이 취소되었습니다.' : '방문 지원 예약이 확정되었습니다: 2099-07-10 14:30',
      lastMessageAt: '2026-07-06T10:10:00Z',
      userUnreadCount: 1,
      adminUnreadCount: 0,
      canSendMessage: true,
      visitReservation: {
        id: '00000000-0000-4000-8000-000000008001',
        status,
        scheduledAt: '2099-07-10T14:30:00+09:00',
        technicianNote: status === 'CANCELLED' ? null : '방문 전 연락'
      },
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
      }
    ],
    pollingIntervalMs: 5000
  };
}

function deletedAdminChatDetail() {
  return {
    contact: {
      id: '00000000-0000-4000-8000-000000009001',
      asTicketId: '00000000-0000-4000-8000-000000006001',
      status: 'ARCHIVED',
      ticketStatus: 'CANCELLED',
      title: 'AS 상담방',
      symptom: 'GPU 온도 상승',
      lastMessagePreview: '관리자가 상담방을 삭제했습니다. 새 AS 접수가 가능합니다.',
      lastMessageAt: '2026-07-06T10:11:00Z',
      userUnreadCount: 1,
      adminUnreadCount: 2,
      canSendMessage: false,
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
        id: '00000000-0000-4000-8000-000000009199',
        role: 'SYSTEM',
        content: '관리자가 상담방을 삭제했습니다. 새 AS 접수가 가능합니다.',
        createdAt: '2026-07-06T10:11:00Z'
      }
    ],
    pollingIntervalMs: 5000
  };
}
