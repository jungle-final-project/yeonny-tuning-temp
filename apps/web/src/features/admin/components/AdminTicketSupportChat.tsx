import { FormEvent, type ReactNode, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Send } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Panel, StateMessage } from '../../../components/ui';
import {
  getAdminSupportChatSession,
  getAdminSupportChatSessions,
  openSupportChatSocket,
  postAdminSupportChatMessage,
  type SupportChatSocket
} from '../../support/supportChatApi';
import type { SupportChatMessage, SupportChatSessionDto, SupportChatSessionListDto } from '../../support/types';
import { AdminChatBubble, connectionClass, socketStatusLabel, type SocketStatus } from '../pages/AdminSupportChatSessionsPage';

const DEFAULT_POLL_MS = 5000;
const SOCKET_RECONNECT_DELAYS_MS = [1000, 2000, 5000, 10000];

// AS 티켓 상세에 박는 상담방 채팅(메시지 + 입력만). 방문 예약·상담방 삭제 같은 관리 기능은
// /admin/support-chat-sessions가 담당하고, 여기서는 이 티켓의 대화 확인·답변 전송만 한다.
// 실시간 방식은 상담방 관리 페이지와 동일: WebSocket 수신 + 재연결 사다리 + 폴링 폴백.
export function AdminTicketSupportChat({ ticketId, remoteAction }: { ticketId: string; remoteAction?: ReactNode }) {
  const queryClient = useQueryClient();
  const socketRef = useRef<SupportChatSocket | null>(null);
  const messagesRef = useRef<HTMLDivElement | null>(null);
  const wasAtBottomRef = useRef(true);
  const forceScrollToBottomRef = useRef(false);
  const [message, setMessage] = useState('');
  const [sendError, setSendError] = useState<string | null>(null);
  const [socketStatus, setSocketStatus] = useState<SocketStatus>('polling');

  // 티켓→상담방 조회 API가 없어 목록에서 asTicketId로 찾는다. 방이 아직 없으면 목록만
  // 폴링해 생기는 즉시 붙고, 찾은 뒤에는 상세 폴링/WS가 갱신을 담당한다.
  const listQuery = useQuery({
    queryKey: ['admin-ticket-support-chat', 'sessions'],
    queryFn: getAdminSupportChatSessions,
    enabled: Boolean(ticketId),
    refetchInterval: (query) => {
      const list = query.state.data as SupportChatSessionListDto | undefined;
      return list && findSession(list, ticketId) ? false : pollingInterval(list);
    },
    retry: false
  });
  const session = findSession(listQuery.data, ticketId);
  const sessionId = session?.id ?? null;

  const detailQuery = useQuery({
    queryKey: ['admin-ticket-support-chat', 'session', sessionId],
    queryFn: () => getAdminSupportChatSession(sessionId as string, true),
    enabled: Boolean(sessionId),
    refetchInterval: (query) => sessionId ? pollingInterval(query.state.data as SupportChatSessionDto | undefined) : false,
    retry: false
  });

  const applyDetail = (detail: SupportChatSessionDto) => {
    queryClient.setQueryData<SupportChatSessionDto | undefined>(
      ['admin-ticket-support-chat', 'session', sessionId],
      (existing) => shouldApplyDetail(detail, existing) ? detail : existing
    );
  };

  useEffect(() => {
    socketRef.current?.close();
    socketRef.current = null;
    setSocketStatus('polling');
    if (!sessionId) {
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
          sessionId,
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
            queryClient.setQueryData<SupportChatSessionDto | undefined>(
              ['admin-ticket-support-chat', 'session', sessionId],
              (existing) => shouldApplyDetail(detail, existing) ? detail : existing
            );
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
  }, [sessionId, queryClient]);

  const sendMutation = useMutation({
    mutationFn: (content: string) => postAdminSupportChatMessage(sessionId as string, content),
    onSuccess: (detail) => {
      forceScrollToBottomRef.current = true;
      setMessage('');
      setSendError(null);
      applyDetail(detail);
    },
    onError: (error) => {
      setSendError(errorMessage(error));
    }
  });

  const contact = detailQuery.data?.contact ?? session ?? null;
  const messages: SupportChatMessage[] = detailQuery.data?.messages ?? [];
  const canSend = Boolean(sessionId && contact?.canSendMessage && message.trim() && !sendMutation.isPending);

  // 단순화한 스크롤: 방금 전송했거나 바닥 근처였을 때만 바닥으로 붙인다(새 메시지 마커 없음).
  useEffect(() => {
    if (!messagesRef.current) return;
    const shouldScroll = forceScrollToBottomRef.current || wasAtBottomRef.current;
    forceScrollToBottomRef.current = false;
    if (!shouldScroll) return;
    const frame = window.requestAnimationFrame(() => {
      scrollMessagesToBottom(messagesRef.current);
      wasAtBottomRef.current = true;
    });
    return () => window.cancelAnimationFrame(frame);
  }, [messages, sessionId]);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!canSend) return;
    setSendError(null);
    sendMutation.mutate(message.trim());
  }

  return (
    <div data-testid="admin-ticket-support-chat">
      <Panel
        title="상담방"
        subtitle="이 AS 티켓의 사용자 상담방입니다. 방문 예약과 상담방 삭제는 상담방 관리에서 처리합니다."
        action={(
          <div className="flex flex-wrap items-center justify-end gap-2">
            {remoteAction}
            <Link className="shrink-0 text-xs font-bold text-brand-blue hover:underline" to="/admin/support-chat-sessions">상담방 관리로 이동</Link>
          </div>
        )}
      >
        {listQuery.isLoading ? <StateMessage type="info" title="상담방 확인 중" body="이 티켓의 상담방을 찾고 있습니다." /> : null}
        {listQuery.isError ? <StateMessage type="warn" title="상담방 조회 실패" body="상담방 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요." /> : null}
        {!listQuery.isLoading && !listQuery.isError && !session ? (
          <StateMessage type="info" title="상담방 없음" body="이 티켓에 연결된 상담방이 아직 없습니다. 사용자가 상담을 시작하면 여기에 표시됩니다." />
        ) : null}
        {sessionId && detailQuery.isLoading ? <StateMessage type="info" title="대화 로딩 중" body="상담 메시지를 불러오고 있습니다." /> : null}
        {sessionId && detailQuery.isError ? <StateMessage type="warn" title="대화 조회 실패" body="상담방 상세를 불러오지 못했습니다." /> : null}
        {sessionId && detailQuery.data ? (
          <>
            <div className="mb-2 flex items-center justify-end">
              <span className={`rounded-full border px-2 py-0.5 text-[11px] font-black ${connectionClass(socketStatus)}`}>
                {socketStatusLabel(socketStatus)}
              </span>
            </div>
            <div className="h-[340px] overflow-hidden rounded-md border border-slate-200 bg-slate-50 sm:h-[380px] lg:h-[440px]">
              <div
                ref={messagesRef}
                data-testid="admin-ticket-support-chat-messages"
                className="h-full overflow-y-auto p-4"
                onScroll={(event) => {
                  wasAtBottomRef.current = isNearBottom(event.currentTarget);
                }}
              >
                {messages.length === 0 ? (
                  <StateMessage type="info" title="메시지 없음" body="아직 메시지가 없습니다. 상담방이 활성화되면 채팅이 표시됩니다." />
                ) : (
                  <div className="space-y-3">
                    {messages.map((item) => <AdminChatBubble key={item.id} message={item} />)}
                  </div>
                )}
              </div>
            </div>
            <form onSubmit={submit} className="mt-4">
              {contact?.canSendMessage === false ? (
                <StateMessage type="info" title="종료된 상담방" body="종료된 AS 티켓 상담방에는 답변을 보낼 수 없습니다." />
              ) : (
                <div className="space-y-2">
                  <div className="flex gap-2">
                    <input
                      className="h-11 min-w-0 flex-1 rounded-md border border-slate-300 px-3 text-sm focus:border-brand-blue focus:outline-none focus:ring-4 focus:ring-blue-100"
                      placeholder="관리자 답변을 입력하세요"
                      value={message}
                      maxLength={2000}
                      onChange={(event) => {
                        setMessage(event.target.value);
                        setSendError(null);
                      }}
                    />
                    <button
                      type="submit"
                      disabled={!canSend}
                      className="inline-flex h-11 min-w-28 shrink-0 items-center justify-center gap-1 rounded-md bg-brand-blue px-4 text-sm font-black text-white transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-400"
                    >
                      <Send size={14} />
                      {sendMutation.isPending ? '전송 중' : '답변 전송'}
                    </button>
                  </div>
                  <div className="flex items-center justify-between text-[11px]">
                    <span className={`font-bold ${sendError ? 'text-rose-700' : 'text-slate-500'}`} role={sendError ? 'alert' : undefined}>
                      {sendError ?? 'Enter 키로 즉시 전송합니다. 전송 후 입력창은 자동으로 비워집니다.'}
                    </span>
                    <span className="text-slate-500">문자 수 {message.length} / 2000</span>
                  </div>
                </div>
              )}
            </form>
          </>
        ) : null}
      </Panel>
    </div>
  );
}

function findSession(list: SupportChatSessionListDto | undefined, ticketId: string) {
  return list?.items.find((item) => item.asTicketId === ticketId) ?? null;
}

function errorMessage(error: unknown) {
  return error instanceof Error && error.message ? error.message : '요청을 처리하지 못했습니다.';
}

function pollingInterval(detail?: { pollingIntervalMs?: number }) {
  const value = detail?.pollingIntervalMs;
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : DEFAULT_POLL_MS;
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
