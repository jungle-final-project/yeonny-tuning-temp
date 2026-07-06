import { FormEvent, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { AUTH_CHANGED_EVENT, getCachedAuthUser } from '../../../lib/api';
import { getAdminSupportChatSession, getAdminSupportChatSessions, openSupportChatSocket, postAdminSupportChatMessage, type SupportChatSocket } from '../../support/supportChatApi';
import type { SupportChatContact, SupportChatMessage, SupportChatSessionDto, SupportChatSessionListDto } from '../../support/types';

const DEFAULT_POLL_MS = 5000;
const SOCKET_RECONNECT_DELAYS_MS = [1000, 2000, 5000, 10000];
type SocketStatus = 'polling' | 'connecting' | 'reconnecting' | 'connected' | 'disconnected';

export function AdminSupportChatSessionsPage() {
  const queryClient = useQueryClient();
  const socketRef = useRef<SupportChatSocket | null>(null);
  const messagesRef = useRef<HTMLDivElement | null>(null);
  const wasAtBottomRef = useRef(true);
  const forceScrollToBottomRef = useRef(false);
  const previousLastMessageIdRef = useRef<string | null>(null);
  const [authScope, setAuthScope] = useState(() => authScopeKey(getCachedAuthUser()));
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);
  const [selectedSessionMarkRead, setSelectedSessionMarkRead] = useState(false);
  const [message, setMessage] = useState('');
  const [sendError, setSendError] = useState<string | null>(null);
  const [socketStatus, setSocketStatus] = useState<SocketStatus>('polling');
  const [newMarkerMessageId, setNewMarkerMessageId] = useState<string | null>(null);

  const listQuery = useQuery({
    queryKey: ['admin-support-chat-sessions', authScope],
    queryFn: getAdminSupportChatSessions,
    refetchInterval: (query) => pollingInterval(query.state.data as SupportChatSessionListDto | undefined),
    retry: false
  });
  const rooms = listQuery.data?.items ?? [];

  useEffect(() => {
    if (!selectedSessionId && rooms[0]?.id) {
      setSelectedSessionMarkRead(false);
      setSelectedSessionId(rooms[0].id);
    }
  }, [rooms, selectedSessionId]);

  const detailQuery = useQuery({
    queryKey: ['admin-support-chat-session', authScope, selectedSessionId, selectedSessionMarkRead],
    queryFn: () => getAdminSupportChatSession(selectedSessionId as string, selectedSessionMarkRead),
    enabled: Boolean(selectedSessionId),
    refetchInterval: (query) => selectedSessionId ? pollingInterval(query.state.data as SupportChatSessionDto | undefined) : false,
    retry: false
  });

  useEffect(() => {
    const handleAuthChanged = () => {
      setAuthScope(authScopeKey(getCachedAuthUser()));
      socketRef.current?.close();
      socketRef.current = null;
      setSocketStatus('polling');
      setSelectedSessionId(null);
      setSelectedSessionMarkRead(false);
      setMessage('');
      setSendError(null);
      setNewMarkerMessageId(null);
      queryClient.removeQueries({ queryKey: ['admin-support-chat-sessions'] });
      queryClient.removeQueries({ queryKey: ['admin-support-chat-session'] });
    };
    window.addEventListener(AUTH_CHANGED_EVENT, handleAuthChanged);
    return () => window.removeEventListener(AUTH_CHANGED_EVENT, handleAuthChanged);
  }, [queryClient]);

  useEffect(() => {
    socketRef.current?.close();
    socketRef.current = null;
    setSocketStatus('polling');
    if (!selectedSessionId) {
      return undefined;
    }
    let disposed = false;
    let reconnectTimer: ReturnType<typeof window.setTimeout> | null = null;
    let reconnectAttempt = 0;
    let activeConnectionId = 0;

    const clearReconnectTimer = () => {
      if (reconnectTimer !== null) {
        window.clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
    };
    const scheduleReconnect = () => {
      if (disposed) return;
      if (reconnectTimer !== null) return;
      setSocketStatus('reconnecting');
      const delay = SOCKET_RECONNECT_DELAYS_MS[Math.min(reconnectAttempt, SOCKET_RECONNECT_DELAYS_MS.length - 1)];
      reconnectAttempt += 1;
      reconnectTimer = window.setTimeout(() => {
        reconnectTimer = null;
        connect();
      }, delay);
    };
    const connect = async () => {
      if (disposed) return;
      const connectionId = ++activeConnectionId;
      setSocketStatus(reconnectAttempt > 0 ? 'reconnecting' : 'connecting');
      try {
        const socket = await openSupportChatSocket({
          mode: 'admin',
          sessionId: selectedSessionId,
          onOpen: () => {
            if (disposed || activeConnectionId !== connectionId) return;
            reconnectAttempt = 0;
            setSocketStatus('connected');
          },
          onClose: () => {
            if (disposed || activeConnectionId !== connectionId) return;
            socketRef.current = null;
            scheduleReconnect();
          },
          onError: () => {
            if (disposed || activeConnectionId !== connectionId) return;
            setSocketStatus('disconnected');
            scheduleReconnect();
          },
          onSocketError: (error) => {
            if (error.message) {
              setSendError(error.message);
            }
          },
          onDetail: (detail) => {
            wasAtBottomRef.current = isNearBottom(messagesRef.current);
            cacheDetail(queryClient, authScope, detail);
          }
        });
        if (disposed || activeConnectionId !== connectionId) {
          socket?.close();
          return;
        }
        socketRef.current = socket;
        if (!socket) {
          setSocketStatus('polling');
        }
      } catch (error) {
        if (disposed || activeConnectionId !== connectionId) return;
        setSocketStatus('disconnected');
        scheduleReconnect();
      }
    };

    connect();
    return () => {
      disposed = true;
      clearReconnectTimer();
      socketRef.current?.close();
      socketRef.current = null;
      setSocketStatus('polling');
    };
  }, [authScope, selectedSessionId, queryClient]);

  const sendMutation = useMutation({
    mutationFn: (content: string) => postAdminSupportChatMessage(selectedSessionId as string, content),
    onSuccess: (detail) => {
      forceScrollToBottomRef.current = true;
      setMessage('');
      setSendError(null);
      cacheDetail(queryClient, authScope, detail);
      void listQuery.refetch();
    },
    onError: (error) => {
      setSendError(errorMessage(error));
    }
  });

  const selectedRoom = detailQuery.data?.contact ?? rooms.find((room) => room.id === selectedSessionId) ?? null;
  const messages = detailQuery.data?.messages ?? [];
  const canSend = Boolean(selectedSessionId && selectedRoom?.canSendMessage && message.trim() && !sendMutation.isPending);
  const roomRows = rooms.map((room) => ({
    선택: (
      <button
        type="button"
        onClick={() => {
          setSelectedSessionMarkRead(true);
          setSelectedSessionId(room.id);
        }}
        className={`rounded px-3 py-2 text-xs font-bold ${room.id === selectedSessionId ? 'bg-brand-blue text-white' : 'border border-slate-300 text-brand-navy'}`}
        aria-label={`${userLabel(room)} 상담방 선택`}
      >
        선택
      </button>
    ),
    사용자: userLabel(room),
    티켓: <Link to={`/admin/as-tickets/${room.asTicketId}`} className="font-bold text-brand-blue">{shortId(room.asTicketId)}</Link>,
    상태: <StatusBadge status={room.ticketStatus ?? room.status} />,
    안읽음: room.adminUnreadCount ?? 0,
    증상: room.symptom ?? '-',
    최근메시지: room.lastMessagePreview ?? '-',
    최근시각: formatDateTime(room.lastMessageAt ?? undefined)
  }));
  const exportRows = rooms.map((room) => ({
    id: room.id,
    asTicketId: room.asTicketId,
    user: userLabel(room),
    ticketStatus: room.ticketStatus ?? room.status,
    adminUnreadCount: room.adminUnreadCount ?? 0,
    symptom: room.symptom ?? '',
    lastMessageAt: formatDateTime(room.lastMessageAt ?? undefined)
  }));

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!canSend) return;
    setSendError(null);
    sendMutation.mutate(message.trim());
  }

  useEffect(() => {
    if (!messagesRef.current) return;
    const lastMessageId = messages.length > 0 ? messages[messages.length - 1].id : null;
    const previousLastMessageId = previousLastMessageIdRef.current;
    const hasNewMessage = Boolean(previousLastMessageId && lastMessageId && previousLastMessageId !== lastMessageId);
    const shouldScroll = forceScrollToBottomRef.current || !hasNewMessage || wasAtBottomRef.current;
    const markerMessageId = hasNewMessage && !shouldScroll
      ? firstMessageAfter(messages, previousLastMessageId) ?? lastMessageId
      : null;
    previousLastMessageIdRef.current = lastMessageId;
    forceScrollToBottomRef.current = false;

    const frame = window.requestAnimationFrame(() => {
      if (shouldScroll) {
        scrollMessagesToBottom(messagesRef.current);
        setNewMarkerMessageId(null);
        wasAtBottomRef.current = true;
      } else {
        setNewMarkerMessageId(markerMessageId);
      }
    });
    return () => window.cancelAnimationFrame(frame);
  }, [messages, selectedSessionId]);

  return (
    <AdminShell title="상담방 관리" exportRows={exportRows} exportFileName="admin-support-chat-sessions.csv">
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_460px]">
        <Panel title="상담방 목록" subtitle="AS 티켓이 생성된 사용자 상담방을 확인하고 응답할 수 있습니다.">
          {listQuery.isLoading ? <StateMessage type="info" title="상담방 로딩 중" body="사용자 상담방 목록을 불러오고 있습니다." /> : null}
          {listQuery.isError ? <StateMessage type="warn" title="상담방 조회 실패" body="관리자 상담방 목록을 불러오지 못했습니다." /> : null}
          {!listQuery.isLoading && !listQuery.isError && roomRows.length === 0 ? (
            <StateMessage type="info" title="상담방 없음" body="아직 관리할 사용자 상담방이 없습니다." />
          ) : null}
          {!listQuery.isLoading && !listQuery.isError && roomRows.length > 0 ? (
            <DataTable columns={['선택', '사용자', '티켓', '상태', '안읽음', '증상', '최근메시지', '최근시각']} rows={roomRows} />
          ) : null}
        </Panel>

        <Panel title="대화 내용" subtitle={selectedRoom ? `${userLabel(selectedRoom)} · ${shortId(selectedRoom.asTicketId)}` : '상담방을 선택하세요.'}>
          {!selectedSessionId ? (
            <StateMessage type="info" title="선택된 상담방 없음" body="왼쪽 목록에서 상담방을 선택하면 대화 내용이 표시됩니다." />
          ) : null}
          {detailQuery.isLoading ? <StateMessage type="info" title="대화 로딩 중" body="상담 메시지를 불러오고 있습니다." /> : null}
          {detailQuery.isError ? <StateMessage type="warn" title="대화 조회 실패" body="상담방 상세를 불러오지 못했습니다." /> : null}
          {detailQuery.data ? (
            <>
              <div className="mb-4 rounded border border-slate-200 bg-slate-50 p-3 text-sm">
                <div className="mb-2 font-bold text-slate-900">{selectedRoom?.symptom ?? 'AS 상담'}</div>
                <div className="flex flex-wrap gap-2 text-xs text-slate-600">
                  <span>티켓 {selectedRoom ? shortId(selectedRoom.asTicketId) : '-'}</span>
                  <span>상태 {selectedRoom?.ticketStatus ?? '-'}</span>
                  <span>안읽음 {selectedRoom?.adminUnreadCount ?? 0}</span>
                  <span>{socketStatusLabel(socketStatus)}</span>
                </div>
              </div>
              <div className="relative h-[440px] overflow-hidden rounded border border-slate-200 bg-slate-50">
                <div
                  ref={messagesRef}
                  data-testid="admin-support-chat-messages"
                  className="h-full overflow-y-auto p-4"
                  onScroll={(event) => {
                    const nearBottom = isNearBottom(event.currentTarget);
                    wasAtBottomRef.current = nearBottom;
                    if (nearBottom) {
                      setNewMarkerMessageId(null);
                    }
                  }}
                >
                  <div className="space-y-3">
                    {messages.map((item) => (
                      <div key={item.id}>
                        {newMarkerMessageId === item.id ? <NewMessageMarker /> : null}
                        <AdminChatBubble message={item} />
                      </div>
                    ))}
                  </div>
                </div>
                {newMarkerMessageId ? (
                  <button
                    type="button"
                    aria-label="새 메시지로 이동"
                    onClick={() => {
                      scrollMessagesToBottom(messagesRef.current);
                      wasAtBottomRef.current = true;
                      setNewMarkerMessageId(null);
                    }}
                    className="absolute bottom-3 left-1/2 -translate-x-1/2 rounded-full bg-brand-blue px-4 py-2 text-xs font-bold text-white shadow-lg"
                  >
                    새 메시지로 이동
                  </button>
                ) : null}
              </div>
              <form onSubmit={submit} className="mt-4">
                {selectedRoom?.canSendMessage === false ? (
                  <StateMessage type="info" title="종료된 상담방" body="종료된 AS 티켓 상담방에는 답변을 보낼 수 없습니다." />
                ) : (
                  <div className="space-y-2">
                    <div className="flex gap-2">
                      <input
                        className="h-11 min-w-0 flex-1 rounded border border-slate-300 px-3 text-sm"
                        placeholder="관리자 답변을 입력하세요"
                        value={message}
                        maxLength={2000}
                        onChange={(event) => {
                          setMessage(event.target.value);
                          setSendError(null);
                        }}
                      />
                      <button disabled={!canSend} className="rounded bg-brand-blue px-4 py-2 text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-400">
                        {sendMutation.isPending ? '전송 중' : '답변 전송'}
                      </button>
                    </div>
                    {sendError ? <p role="alert" className="text-xs font-bold text-rose-700">{sendError}</p> : null}
                  </div>
                )}
              </form>
            </>
          ) : null}
        </Panel>
      </div>
    </AdminShell>
  );
}

function AdminChatBubble({ message }: { message: SupportChatMessage }) {
  const isAdmin = message.role === 'ADMIN';
  const isSystem = message.role === 'SYSTEM';
  return (
    <div className={`flex ${isAdmin ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-[82%] rounded px-3 py-2 text-sm leading-6 shadow-sm ${
        isAdmin
          ? 'bg-brand-blue text-white'
          : isSystem
            ? 'border border-slate-200 bg-white text-slate-600'
            : 'border border-slate-200 bg-white text-slate-900'
      }`}>
        <div className="mb-1 text-[11px] font-bold opacity-75">{messageLabel(message)}</div>
        <p className="whitespace-pre-wrap break-words">{message.content}</p>
      </div>
    </div>
  );
}

function NewMessageMarker() {
  return (
    <div className="my-3 flex items-center gap-3" aria-label="새 메시지">
      <div className="h-px flex-1 bg-blue-200" />
      <span className="rounded-full bg-blue-50 px-3 py-1 text-[11px] font-black text-brand-blue">새 메시지</span>
      <div className="h-px flex-1 bg-blue-200" />
    </div>
  );
}

function cacheDetail(queryClient: ReturnType<typeof useQueryClient>, authScope: string, detail: SupportChatSessionDto) {
  if (detail.contact?.id) {
    queryClient.setQueriesData<SupportChatSessionDto | undefined>(
      { queryKey: ['admin-support-chat-session', authScope, detail.contact.id] },
      (existing) => shouldApplyDetail(detail, existing) ? detail : existing
    );
    queryClient.setQueryData<SupportChatSessionListDto | undefined>(
      ['admin-support-chat-sessions', authScope],
      (existing) => patchAdminList(existing, detail.contact as SupportChatContact)
    );
  }
}

function patchAdminList(existing: SupportChatSessionListDto | undefined, contact: SupportChatContact) {
  if (!existing) return existing;
  const items = existing.items.some((item) => item.id === contact.id)
    ? existing.items.map((item) => item.id === contact.id ? { ...item, ...contact } : item)
    : [contact, ...existing.items];
  return {
    ...existing,
    items: items.sort((left, right) => timestamp(right.lastMessageAt) - timestamp(left.lastMessageAt))
  };
}

function messageLabel(message: SupportChatMessage) {
  if (message.role === 'SYSTEM') return '시스템';
  if (message.role === 'ADMIN') return message.senderName ?? '관리자';
  return message.senderName ?? '사용자';
}

function userLabel(room: SupportChatContact) {
  return room.user?.email ?? room.user?.name ?? room.user?.id ?? '-';
}

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}

function formatDateTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

function socketStatusLabel(status: SocketStatus) {
  switch (status) {
    case 'connected':
      return '실시간 연결';
    case 'connecting':
    case 'reconnecting':
      return '재연결 중';
    case 'disconnected':
      return '연결 끊김';
    case 'polling':
    default:
      return '자동 새로고침';
  }
}

function errorMessage(error: unknown) {
  return error instanceof Error && error.message ? error.message : '메시지를 전송하지 못했습니다.';
}

function pollingInterval(detail?: { pollingIntervalMs?: number }) {
  const value = detail?.pollingIntervalMs;
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : DEFAULT_POLL_MS;
}

function authScopeKey(user: unknown) {
  if (!user || typeof user !== 'object') {
    return 'anonymous';
  }
  const record = user as { id?: unknown; role?: unknown };
  const id = typeof record.id === 'string' && record.id ? record.id : 'unknown';
  const role = typeof record.role === 'string' && record.role ? record.role : 'unknown';
  return `${role}:${id}`;
}

function shouldApplyDetail(incoming: SupportChatSessionDto, existing?: SupportChatSessionDto) {
  const incomingTime = detailTime(incoming);
  const existingTime = existing ? detailTime(existing) : null;
  return incomingTime === null || existingTime === null || incomingTime >= existingTime;
}

function detailTime(detail: SupportChatSessionDto) {
  const lastMessage = detail.messages.length > 0 ? detail.messages[detail.messages.length - 1] : null;
  const value = detail.contact?.lastMessageAt ?? lastMessage?.createdAt ?? null;
  return timestamp(value);
}

function timestamp(value?: string | null) {
  if (!value) return 0;
  const time = Date.parse(value);
  return Number.isNaN(time) ? 0 : time;
}

function isNearBottom(element: HTMLElement | null) {
  if (!element) return true;
  return element.scrollHeight - element.clientHeight - element.scrollTop < 24;
}

function scrollMessagesToBottom(element: HTMLElement | null) {
  if (element) {
    element.scrollTop = element.scrollHeight;
  }
}

function firstMessageAfter(messages: SupportChatMessage[], previousLastMessageId: string | null) {
  const previousIndex = previousLastMessageId
    ? messages.findIndex((message) => message.id === previousLastMessageId)
    : -1;
  if (previousIndex >= 0 && previousIndex < messages.length - 1) {
    return messages[previousIndex + 1].id;
  }
  return messages.length > 0 ? messages[messages.length - 1].id : null;
}
