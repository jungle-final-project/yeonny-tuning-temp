import { ChangeEvent, FormEvent, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { DataTable, Panel, Screen, StateMessage, StatusBadge } from '../../components/ui';
import { ApiError } from '../../lib/api';
import { AS_CHAT_DEFAULT_TICKET_ID, getAsChat, sendAsChat, streamAsChat } from './asChatApi';
import type { AsChatEvidence, AsChatResponse, AsChatToolResult } from './asChatApi';
import { createSupportTicket, getSupportTicket, uploadAgentLog } from './supportApi';
import type { AsTicketDto, CauseCandidate } from './types';

type SubmitState = 'default' | 'consent_required' | 'uploading' | 'upload_error' | 'ticket_created';

export function AsChatPage() {
  const [searchParams] = useSearchParams();
  const initialTicketId = searchParams.get('asTicketId')?.trim() || AS_CHAT_DEFAULT_TICKET_ID;
  const [ticketId, setTicketId] = useState(initialTicketId);
  const [message, setMessage] = useState('게임 20분 뒤 프레임이 급락하고 GPU 온도가 95도까지 올라가요.');
  const [latestResponse, setLatestResponse] = useState<AsChatResponse | null>(null);
  const [error, setError] = useState('');
  const [progressMessage, setProgressMessage] = useState('');
  const [progressSteps, setProgressSteps] = useState<string[]>([]);

  const chatQuery = useQuery({
    queryKey: ['as-chat', ticketId],
    queryFn: () => getAsChat(ticketId)
  });

  const sendMutation = useMutation({
    mutationFn: async () => {
      const outgoingMessage = message.trim();
      let streamStarted = false;
      setProgressMessage('AS AI 요청을 준비하고 있습니다.');
      setProgressSteps([]);
      try {
        return await streamAsChat(ticketId, outgoingMessage, (event) => {
          streamStarted = true;
          if (event.event === 'DONE') return;
          const nextMessage = String(event.data.message ?? progressLabel(event.event));
          setProgressMessage(nextMessage);
          setProgressSteps((previous) => [...previous, nextMessage].slice(-4));
        });
      } catch (cause) {
        if (!streamStarted) {
          setProgressMessage('실시간 진행 상태 연결 실패로 기본 요청으로 전환합니다.');
          return sendAsChat(ticketId, outgoingMessage);
        }
        throw cause;
      }
    },
    onSuccess: (response) => {
      setLatestResponse(response);
      setMessage('');
      setError('');
      setProgressMessage('');
      setProgressSteps([]);
    },
    onError: (cause) => {
      setProgressMessage('');
      if (cause instanceof ApiError && cause.status === 428) {
        setError('서버에 OPENAI_API_KEY가 필요합니다. API 컨테이너 환경 변수 설정 후 다시 실행해 주세요.');
        return;
      }
      if (cause instanceof ApiError && cause.status === 404) {
        setError('현재 로그인 사용자에게 연결된 AS 티켓을 찾을 수 없습니다.');
        return;
      }
      if (cause instanceof Error && cause.message) {
        setError(cause.message);
        return;
      }
      setError('AS AI 챗봇 요청에 실패했습니다. 백엔드 실행 상태와 로그인 토큰을 확인해 주세요.');
    }
  });

  const chat = latestResponse?.asTicketId === ticketId ? latestResponse : chatQuery.data;
  const isBusy = sendMutation.isPending;
  const canSend = Boolean(message.trim()) && !isBusy;

  function submitTicket(event: FormEvent) {
    event.preventDefault();
    setLatestResponse(null);
    setError('');
    void chatQuery.refetch();
  }

  function submitMessage(event: FormEvent) {
    event.preventDefault();
    if (!canSend) return;
    setError('');
    sendMutation.mutate();
  }

  return (
    <Screen>
      <div className="grid grid-cols-[minmax(0,1fr)_430px] gap-5">
        <Panel title="AS AI 챗봇" subtitle="AS 접수 후 티켓 증상, RAG 근거, Tool 결과를 사용해 1차 상담 답변을 생성합니다.">
          <form onSubmit={submitTicket} className="mb-4 flex gap-3">
            <input
              className="h-11 flex-1 rounded border border-slate-300 px-3 text-sm"
              value={ticketId}
              onChange={(event) => setTicketId(event.target.value)}
              aria-label="AS ticket id"
            />
            <button className="rounded border border-slate-300 px-4 py-2 text-sm font-bold">티켓 불러오기</button>
          </form>

          {chatQuery.isLoading ? <StateMessage type="info" title="챗봇 세션 조회 중" body="AS 티켓과 기존 대화 이력을 불러오고 있습니다." /> : null}
          {chatQuery.isError ? <StateMessage type="warn" title="챗봇 세션 조회 실패" body="GET /api/ai/as-chat 응답을 불러오지 못했습니다." /> : null}
          {error ? <div className="mb-4"><StateMessage type="warn" title="AS AI 확인 필요" body={error} /></div> : null}

          <div className="h-[560px] overflow-y-auto rounded border border-slate-200 bg-slate-50 p-4">
            {chat?.messages.length ? (
              <div className="space-y-3">
                {chat.messages.map((item) => (
                  <div key={item.id} className={`flex ${item.role === 'USER' ? 'justify-end' : 'justify-start'}`}>
                    <div className={`max-w-[72%] rounded px-4 py-3 text-sm leading-6 shadow-sm ${item.role === 'USER' ? 'bg-brand-blue text-white' : 'border border-slate-200 bg-white text-slate-800'}`}>
                      <div className="mb-1 text-[11px] font-bold opacity-75">{item.role === 'USER' ? '사용자' : 'AI 상담'}</div>
                      <p className="whitespace-pre-wrap">{item.content}</p>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="flex h-full items-center justify-center text-sm text-slate-500">
                아직 저장된 대화가 없습니다. 아래 입력창으로 AS 증상을 이어서 설명해 주세요.
              </div>
            )}
            {isBusy ? (
              <div className="mt-3 rounded border border-blue-100 bg-blue-50 p-3 text-sm text-brand-blue">
                <div className="font-bold">{progressMessage || 'AI가 RAG 근거와 Tool 결과를 확인하고 있습니다...'}</div>
                {progressSteps.length ? (
                  <div className="mt-2 flex flex-wrap gap-2 text-xs text-slate-600">
                    {progressSteps.map((step, index) => (
                      <span key={`${step}-${index}`} className="rounded-full bg-white px-2 py-1">{step}</span>
                    ))}
                  </div>
                ) : null}
              </div>
            ) : null}
          </div>

          <form onSubmit={submitMessage} className="mt-4 flex gap-3">
            <textarea
              className="h-24 flex-1 rounded border border-slate-300 p-3 text-sm"
              placeholder="예: 게임 20분 뒤 프레임이 급락하고 GPU 온도가 95도까지 올라가요."
              value={message}
              onChange={(event) => setMessage(event.target.value)}
            />
            <button disabled={!canSend} className="w-32 rounded bg-brand-blue text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-400">
              {isBusy ? '전송 중' : '전송'}
            </button>
          </form>
        </Panel>

        <div className="space-y-5">
          <Panel title="티켓 / 모델">
            <div className="space-y-3 text-sm">
              <InfoRow label="AS 티켓" value={chat?.asTicketId ?? ticketId} />
              <InfoRow label="모델" value={chat?.model ?? '-'} />
              <InfoRow label="Agent 세션" value={chat?.agentSessionId ?? '-'} />
              <InfoRow label="상태" value={chat?.ticket.status ?? '-'} />
              <div>
                <div className="mb-1 text-xs font-bold text-slate-500">증상 요약</div>
                <p className="rounded border border-slate-200 bg-white p-3 leading-6 text-slate-700">{chat?.ticket.symptom ?? '-'}</p>
              </div>
            </div>
          </Panel>

          <Panel title="LLM 구조화 결과">
            {chat?.assistantMessage ? (
              <div className="space-y-4">
                <div>
                  <div className="mb-1 text-xs font-bold text-slate-500">원인 후보</div>
                  <DataTable columns={['원인', '신뢰도', '근거']} rows={causeRowsForChat(chat)} />
                </div>
                <div>
                  <div className="mb-1 text-xs font-bold text-slate-500">다음 조치</div>
                  <DataTable columns={['조치', '우선순위', '안내']} rows={actionRowsForChat(chat)} />
                </div>
                <StateMessage
                  type={chat.escalation?.required ? 'warn' : 'info'}
                  title={chat.escalation?.required ? '상담원 연결 필요' : 'AI 1차 조치 가능'}
                  body={chat.escalation?.reason ?? 'LLM escalation 결과가 아직 없습니다.'}
                />
              </div>
            ) : (
              <StateMessage type="info" title="답변 대기" body="메시지를 보내면 LLM 구조화 결과가 여기에 표시됩니다." />
            )}
          </Panel>

          <Panel title="Tool 결과">
            <DataTable columns={['Tool', '판정', '요약']} rows={toolRows(chat?.toolResults ?? [])} />
          </Panel>

          <Panel title="RAG 근거">
            <DataTable columns={['근거', '점수', '요약']} rows={evidenceRows(chat?.evidence ?? [])} />
          </Panel>
        </div>
      </div>
    </Screen>
  );
}

function progressLabel(eventName: string) {
  if (eventName === 'STARTED') return 'AS 티켓과 사용자 세션을 확인하고 있습니다.';
  if (eventName === 'RAG_READY') return '관련 AS 근거를 찾았습니다.';
  if (eventName === 'TOOLS_READY') return 'Tool 검증 결과를 정리했습니다.';
  if (eventName === 'LLM_RUNNING') return 'AI 답변을 생성하고 있습니다.';
  return 'AS AI 처리를 진행하고 있습니다.';
}

export function SupportNewPage() {
  const navigate = useNavigate();
  const [symptomTitle, setSymptomTitle] = useState('');
  const [symptomDetail, setSymptomDetail] = useState('');
  const [consentAccepted, setConsentAccepted] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [logPreview, setLogPreview] = useState('');
  const [submitState, setSubmitState] = useState<SubmitState>('default');
  const [error, setError] = useState('');

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0] ?? null;
    setError('');
    setSelectedFile(null);
    setLogPreview('');

    if (!file) return;

    const lowerName = file.name.toLowerCase();
    if (!lowerName.endsWith('.jsonl') && !lowerName.endsWith('.ndjson')) {
      setSubmitState('upload_error');
      setError('JSONL 또는 NDJSON 로그 파일만 업로드할 수 있습니다.');
      return;
    }

    setSelectedFile(file);
    file.text()
      .then((text) => {
        const lines = text.split(/\r?\n/).filter(Boolean).slice(0, 8);
        setLogPreview(lines.join('\n') || '선택한 파일에 표시할 로그 라인이 없습니다.');
      })
      .catch(() => setLogPreview('로그 미리보기를 읽지 못했습니다. 파일은 그대로 제출할 수 있습니다.'));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');

    const title = symptomTitle.trim();
    const detail = symptomDetail.trim();
    if (!title || !detail) {
      setSubmitState('upload_error');
      setError('증상 제목과 상세 내용을 모두 입력해 주세요.');
      return;
    }
    if (!selectedFile) {
      setSubmitState('upload_error');
      setError('최근 30분 PC Agent JSONL 로그 파일을 선택해 주세요.');
      return;
    }
    if (!consentAccepted) {
      setSubmitState('consent_required');
      setError('로그 업로드와 30일 보관에 동의해야 AS를 접수할 수 있습니다.');
      return;
    }

    try {
      setSubmitState('uploading');
      const uploadedLog = await uploadAgentLog(30, consentAccepted, selectedFile);
      const symptom = `${title}\n\n${detail}`;
      const ticket = await createSupportTicket(symptom, uploadedLog.id);
      setSubmitState('ticket_created');
      navigate(`/support/${ticket.id}`);
    } catch {
      setSubmitState('upload_error');
      setError('로그 업로드 또는 AS 티켓 생성에 실패했습니다. 백엔드 실행 상태를 확인해 주세요.');
    }
  }

  const isUploading = submitState === 'uploading';

  return (
    <Screen>
      <form onSubmit={submit} className="grid grid-cols-[minmax(0,1fr)_360px] gap-5">
        <Panel title="AS 접수 / 로그 업로드" subtitle="최근 30분 JSONL 로그 업로드 동의 후 티켓을 생성합니다.">
          <div className="space-y-4">
            <div>
              <label className="mb-1 block text-xs font-bold text-slate-600">증상 제목</label>
              <input
                className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                placeholder="예: 게임 중 프레임 드랍"
                value={symptomTitle}
                onChange={(event) => setSymptomTitle(event.target.value)}
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-slate-600">증상 상세</label>
              <textarea
                className="h-36 w-full rounded border border-slate-300 p-4 text-sm"
                placeholder="언제부터 발생했는지, 어떤 작업 중 재현되는지 입력해 주세요."
                value={symptomDetail}
                onChange={(event) => setSymptomDetail(event.target.value)}
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-slate-600">최근 30분 로그 파일</label>
              <input
                className="block w-full rounded border border-slate-300 p-3 text-sm file:mr-4 file:rounded file:border-0 file:bg-brand-blue file:px-4 file:py-2 file:text-sm file:font-bold file:text-white"
                type="file"
                accept=".jsonl,.ndjson,application/x-ndjson,application/json,text/plain"
                onChange={handleFileChange}
              />
              {selectedFile ? <p className="mt-2 text-xs text-slate-500">{selectedFile.name} · {selectedFile.size.toLocaleString()} bytes</p> : null}
            </div>
            <div className="min-h-32 rounded bg-slate-900 p-4 font-mono text-xs leading-6 text-slate-200">
              {logPreview ? <pre className="whitespace-pre-wrap">{logPreview}</pre> : 'PC Agent JSONL 로그 미리보기가 여기에 표시됩니다.'}
            </div>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={consentAccepted} onChange={(event) => setConsentAccepted(event.target.checked)} />
              최근 30분 로그 업로드와 30일 보관 후 삭제 정책에 동의합니다.
            </label>
            {error ? <StateMessage type="warn" title="AS 접수 확인 필요" body={error} /> : null}
            {submitState === 'ticket_created' ? <StateMessage type="success" title="AS 티켓 생성 완료" body="생성된 티켓 상세 화면으로 이동합니다." /> : null}
            <button disabled={isUploading} className="rounded bg-brand-blue px-5 py-3 text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-400">
              {isUploading ? '업로드 중...' : 'AS 접수하기'}
            </button>
          </div>
        </Panel>
        <Panel title="접수 상태">
          {submitState === 'default' ? <StateMessage type="info" title="로그 제출 준비" body="증상과 최근 30분 JSONL 로그를 함께 제출하면 AS 티켓이 생성됩니다." /> : null}
          {submitState === 'consent_required' ? <StateMessage type="warn" title="동의 필요" body="PC Agent 로그에는 사용 환경 정보가 포함될 수 있어 업로드 동의가 필요합니다." /> : null}
          {submitState === 'uploading' ? <StateMessage type="info" title="업로드 중" body="로그 업로드 후 AS 티켓 생성 API를 순서대로 호출하고 있습니다." /> : null}
          {submitState === 'upload_error' ? <StateMessage type="warn" title="접수 실패" body={error || '입력값과 백엔드 실행 상태를 확인해 주세요.'} /> : null}
          {submitState === 'ticket_created' ? <StateMessage type="success" title="접수 완료" body="사용자 티켓 상세 화면에서 상태를 확인할 수 있습니다." /> : null}
          <div className="mt-5 rounded border border-blue-100 bg-blue-50 px-3 py-2 text-center text-xs font-bold text-brand-blue">POST /api/agent-logs/upload</div>
          <div className="mt-2 rounded border border-blue-100 bg-blue-50 px-3 py-2 text-center text-xs font-bold text-brand-blue">POST /api/as-tickets</div>
        </Panel>
      </form>
    </Screen>
  );
}

export function SupportTicketPage() {
  const { ticketId = '00000000-0000-4000-8000-000000006001' } = useParams();
  const { data: ticket, isError, isLoading } = useQuery({
    queryKey: ['support-ticket', ticketId],
    queryFn: () => getSupportTicket(ticketId)
  });

  if (isLoading) {
    return (
      <Screen>
        <StateMessage type="info" title="AS 티켓 조회 중" body="티켓 상태와 로그 요약 정보를 불러오고 있습니다." />
      </Screen>
    );
  }

  if (isError || !ticket) {
    return (
      <Screen>
        <StateMessage type="warn" title="AS 티켓 조회 실패" body="GET /api/as-tickets/{id} 응답을 불러오지 못했습니다." />
      </Screen>
    );
  }

  return (
    <Screen>
      <div className="grid grid-cols-[minmax(0,1fr)_420px] gap-5">
        <Panel title={`AS 티켓 #${shortTicketId(ticket.id)}`} subtitle="사용자와 담당자가 함께 보는 상세 화면">
          <div className="mb-4 flex flex-wrap gap-2">
            <StatusBadge status={ticket.status} />
            <span className="rounded-full border border-emerald-200 bg-emerald-50 px-2 py-1 text-[11px] font-bold text-emerald-700">Agent 원인 후보 생성 {ticket.causeCandidates.length ? '완료' : '대기'}</span>
          </div>
          <DataTable columns={['시간', '주체', '내용']} rows={ticketTimeline(ticket)} />
          <div className="mt-4">
            <label className="mb-1 block text-xs font-bold text-slate-600">답변 입력</label>
            <div className="flex gap-3">
              <input disabled className="h-11 flex-1 rounded border border-slate-300 px-3 text-sm text-slate-400" placeholder="이번 PR에서는 답변 등록을 구현하지 않습니다." />
              <button disabled className="rounded bg-slate-300 px-5 py-3 text-sm font-bold text-white">답변 등록</button>
            </div>
          </div>
        </Panel>
        <Panel title="로그 요약 / 추천 조치" subtitle="관리자와 동일한 근거 일부 노출">
          <DataTable columns={['원인 후보', '근거', '신뢰도']} rows={causeRows(ticket.causeCandidates)} />
          <p className="mt-5 text-sm leading-6 text-slate-700">
            다음 조치: 로그 요약과 원인 후보를 확인한 뒤 담당자가 추가 확인을 요청할 수 있습니다.
          </p>
          <div className="mt-6 flex gap-3">
            <button disabled className="rounded border border-slate-300 px-4 py-3 text-sm font-bold text-slate-400">로그 다시 업로드</button>
            <button disabled className="rounded bg-slate-300 px-4 py-3 text-sm font-bold text-white">티켓 종료 요청</button>
          </div>
          <div className="mt-8 rounded border border-blue-100 bg-blue-50 px-3 py-2 text-center text-xs font-bold text-brand-blue">GET /api/as-tickets/{'{id}'}</div>
          <Link to="/support/new" className="mt-5 block rounded border border-slate-300 px-4 py-3 text-center text-sm font-bold">새 AS 접수</Link>
        </Panel>
      </div>
    </Screen>
  );
}

function ticketTimeline(ticket: AsTicketDto) {
  return [
    {
      시간: formatTime(ticket.createdAt),
      주체: '사용자',
      내용: ticket.symptom
    },
    {
      시간: formatTime(ticket.createdAt),
      주체: 'Agent',
      내용: ticket.causeCandidates.length ? '로그 기반 원인 후보 생성 완료' : '원인 후보 생성 대기'
    },
    {
      시간: '-',
      주체: '상담원',
      내용: ticket.assignedAdminId ? '담당자 배정 완료' : '담당자 배정 대기'
    }
  ];
}

function causeRows(candidates: CauseCandidate[]) {
  if (!candidates.length) {
    return [{ '원인 후보': '분석 대기', 근거: '티켓 접수 완료', 신뢰도: <StatusBadge status="LOW" /> }];
  }
  return candidates.map((candidate) => ({
    '원인 후보': candidate.label ?? candidate.code ?? '원인 후보',
    근거: candidate.evidenceIds?.length ? candidate.evidenceIds.join(', ') : '로그 요약 기반',
    신뢰도: <StatusBadge status={candidate.confidence ?? 'MEDIUM'} />
  }));
}

function shortTicketId(id: string) {
  const parts = id.split('-');
  const lastPart = parts[parts.length - 1];
  return lastPart ? lastPart.replace(/^0+/, '') || lastPart : id;
}

function formatTime(value?: string) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start justify-between gap-3 border-b border-slate-100 pb-2">
      <span className="text-xs font-bold text-slate-500">{label}</span>
      <span className="max-w-[260px] break-all text-right font-semibold text-slate-800">{value}</span>
    </div>
  );
}

function causeRowsForChat(chat: AsChatResponse) {
  const candidates = chat.causeCandidates ?? [];
  if (!candidates.length) {
    return [{ 원인: '원인 후보 없음', 신뢰도: <StatusBadge status="LOW" />, 근거: 'LLM 응답에 원인 후보가 없습니다.' }];
  }
  return candidates.map((candidate) => ({
    원인: candidate.label ?? '원인 후보',
    신뢰도: <StatusBadge status={candidate.confidence ?? 'MEDIUM'} />,
    근거: candidate.reason ?? '-'
  }));
}

function actionRowsForChat(chat: AsChatResponse) {
  const actions = chat.nextActions ?? [];
  if (!actions.length) {
    return [{ 조치: '추가 조치 없음', 우선순위: <StatusBadge status="LOW" />, 안내: 'LLM 응답에 다음 조치가 없습니다.' }];
  }
  return actions.map((action) => ({
    조치: action.label ?? '다음 조치',
    우선순위: <StatusBadge status={action.priority ?? 'MEDIUM'} />,
    안내: action.instruction ?? '-'
  }));
}

function toolRows(toolResults: AsChatToolResult[]) {
  if (!toolResults.length) {
    return [{ Tool: '대기', 판정: <StatusBadge status="LOW" />, 요약: '메시지 전송 후 Tool 결과가 표시됩니다.' }];
  }
  return toolResults.map((tool) => ({
    Tool: tool.toolName,
    판정: <StatusBadge status={tool.status} />,
    요약: tool.summary
  }));
}

function evidenceRows(evidence: AsChatEvidence[]) {
  if (!evidence.length) {
    return [{ 근거: '대기', 점수: '-', 요약: '메시지 전송 후 RAG 근거가 표시됩니다.' }];
  }
  return evidence.map((item) => ({
    근거: item.sourceId,
    점수: item.score == null ? '-' : String(item.score),
    요약: item.summary
  }));
}
