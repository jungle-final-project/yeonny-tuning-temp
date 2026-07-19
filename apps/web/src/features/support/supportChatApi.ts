import { API_BASE_URL, api, getToken } from '../../lib/api';
import type { SupportChatSessionDto, SupportChatSessionListDto } from './types';

export function getCurrentSupportChat(asTicketId?: string | null, summary = false) {
  const params = new URLSearchParams();
  if (asTicketId) params.set('asTicketId', asTicketId);
  if (summary) params.set('summary', 'true');
  const query = params.toString();
  return api<SupportChatSessionDto>(`/api/support/chat-sessions/current${query ? `?${query}` : ''}`);
}

export function getSupportChatSession(sessionId: string) {
  return api<SupportChatSessionDto>(`/api/support/chat-sessions/${sessionId}`);
}

export function postSupportChatMessage(sessionId: string, content: string) {
  return api<SupportChatSessionDto>(`/api/support/chat-sessions/${sessionId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content })
  });
}

export function putSupportChatVisitReservation(sessionId: string, payload: { scheduledAt: string; addressSnapshot?: string }) {
  return api<SupportChatSessionDto>(`/api/support/chat-sessions/${sessionId}/visit-reservation`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  });
}

export function postSupportChatWebSocketTicket(sessionId: string) {
  return api<SupportChatWebSocketTicketDto>(`/api/support/chat-sessions/${sessionId}/ws-ticket`, {
    method: 'POST'
  });
}

export function getAdminSupportChatSession(sessionId: string, markRead = true) {
  const query = markRead ? '' : '?markRead=false';
  return api<SupportChatSessionDto>(`/api/admin/support/chat-sessions/${sessionId}${query}`);
}

export function getAdminSupportChatSessions() {
  return api<SupportChatSessionListDto>('/api/admin/support/chat-sessions');
}

export function postAdminSupportChatMessage(sessionId: string, content: string) {
  return api<SupportChatSessionDto>(`/api/admin/support/chat-sessions/${sessionId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content })
  });
}

export function deleteAdminSupportChatSession(sessionId: string) {
  return api<SupportChatSessionDto>(`/api/admin/support/chat-sessions/${sessionId}`, {
    method: 'DELETE'
  });
}

export function putAdminSupportChatVisitReservation(sessionId: string, payload: { scheduledAt: string; technicianNote?: string }) {
  return api<SupportChatSessionDto>(`/api/admin/support/chat-sessions/${sessionId}/visit-reservation`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  });
}

export function deleteAdminSupportChatVisitReservation(sessionId: string) {
  return api<SupportChatSessionDto>(`/api/admin/support/chat-sessions/${sessionId}/visit-reservation`, {
    method: 'DELETE'
  });
}

export function postAdminSupportChatWebSocketTicket(sessionId: string) {
  return api<SupportChatWebSocketTicketDto>(`/api/admin/support/chat-sessions/${sessionId}/ws-ticket`, {
    method: 'POST'
  });
}

export function postAdminSupportChatQueueWebSocketTicket() {
  return api<SupportChatWebSocketTicketDto>('/api/admin/support/chat-sessions/ws-ticket', {
    method: 'POST'
  });
}

export type SupportChatSocket = {
  close: () => void;
};

type SupportChatWebSocketTicketDto = {
  ticket?: string;
  expiresAt?: string;
  expiresInSeconds?: number;
};

type SupportChatSocketError = {
  type?: string;
  code?: string;
  message?: string;
  retryable?: boolean;
};

type SupportChatQueueFrame = {
  type?: string;
  contact?: SupportChatSessionListDto['items'][number];
  id?: string;
  pollingIntervalMs?: number;
} & SupportChatSocketError;

export async function openSupportChatSocket(options: {
  mode: 'user' | 'admin';
  sessionId: string;
  onDetail: (detail: SupportChatSessionDto) => void;
  onOpen?: () => void;
  onClose?: () => void;
  onError?: () => void;
  onSocketError?: (error: SupportChatSocketError) => void;
}): Promise<SupportChatSocket | null> {
  if (!getToken() || typeof WebSocket === 'undefined') {
    return null;
  }
  const ticketResponse = options.mode === 'admin'
    ? await postAdminSupportChatWebSocketTicket(options.sessionId)
    : await postSupportChatWebSocketTicket(options.sessionId);
  if (!ticketResponse.ticket) {
    return null;
  }
  const socket = new WebSocket(supportChatSocketUrl(options.mode, options.sessionId));
  let connected = false;
  socket.addEventListener('open', () => {
    socket.send(JSON.stringify({ type: 'AUTH', ticket: ticketResponse.ticket }));
  });
  socket.addEventListener('close', () => options.onClose?.());
  socket.addEventListener('error', () => options.onError?.());
  socket.addEventListener('message', (event) => {
    try {
      const payload = JSON.parse(String(event.data)) as { type?: string; detail?: SupportChatSessionDto } & SupportChatSocketError;
      if (payload.type === 'CHAT_UPDATED' && payload.detail) {
        options.onDetail(payload.detail);
        if (!connected) {
          connected = true;
          options.onOpen?.();
        }
        return;
      }
      if (payload.type === 'ERROR') {
        options.onSocketError?.(payload);
      }
    } catch {
      // Polling remains the fallback when a socket payload is malformed.
    }
  });
  return {
    close() {
      socket.close();
    }
  };
}

function supportChatSocketUrl(mode: 'user' | 'admin', sessionId: string) {
  const base = API_BASE_URL || window.location.origin;
  const url = new URL('/ws/support-chat', base);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  url.searchParams.set('mode', mode);
  url.searchParams.set('sessionId', sessionId);
  return url.toString();
}

export async function openAdminSupportChatQueueSocket(options: {
  onUpdated: (contact: SupportChatSessionListDto['items'][number]) => void;
  onRemoved: (id: string) => void;
  onOpen?: () => void;
  onClose?: () => void;
  onError?: () => void;
  onSocketError?: (error: SupportChatSocketError) => void;
}): Promise<SupportChatSocket | null> {
  if (!getToken() || typeof WebSocket === 'undefined') {
    return null;
  }
  const ticketResponse = await postAdminSupportChatQueueWebSocketTicket();
  if (!ticketResponse.ticket) {
    return null;
  }
  const socket = new WebSocket(adminSupportChatQueueSocketUrl());
  let connected = false;
  socket.addEventListener('open', () => {
    socket.send(JSON.stringify({ type: 'AUTH', ticket: ticketResponse.ticket }));
  });
  socket.addEventListener('close', () => options.onClose?.());
  socket.addEventListener('error', () => options.onError?.());
  socket.addEventListener('message', (event) => {
    try {
      const payload = JSON.parse(String(event.data)) as SupportChatQueueFrame;
      if (payload.type === 'SUPPORT_CHAT_QUEUE_READY') {
        if (!connected) {
          connected = true;
          options.onOpen?.();
        }
        return;
      }
      if (payload.type === 'SUPPORT_CHAT_QUEUE_UPDATED' && payload.contact) {
        options.onUpdated(payload.contact);
        return;
      }
      if (payload.type === 'SUPPORT_CHAT_QUEUE_REMOVED' && payload.id) {
        options.onRemoved(payload.id);
        return;
      }
      if (payload.type === 'ERROR') {
        options.onSocketError?.(payload);
      }
    } catch {
      // Polling remains the fallback when a socket payload is malformed.
    }
  });
  return {
    close() {
      socket.close();
    }
  };
}

function adminSupportChatQueueSocketUrl() {
  const base = API_BASE_URL || window.location.origin;
  const url = new URL('/ws/admin/support-chat-queue', base);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.toString();
}
