import { ChangeEvent, FormEvent, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { DataTable, Panel, Screen, StateMessage, StatusBadge } from '../../components/ui';
import { ApiError } from '../../lib/api';
import { AS_CHAT_DEFAULT_TICKET_ID, getAsChat, sendAsChat, streamAsChat } from './asChatApi';
import type { AsChatEvidence, AsChatResponse, AsChatToolResult } from './asChatApi';
import { createSupportTicket, getSupportTicket, uploadAgentLog } from './supportApi';
import type { AsTicketDto, CauseCandidate } from './types';

type SubmitState = 'default' | 'validation_error' | 'consent_required' | 'uploading' | 'upload_error' | 'ticket_error' | 'ticket_created';

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

  function downloadSampleJsonl() {
    const sampleLines = [
      { timestamp: '2026-06-29T10:00:00Z', cpuUsagePercent: 42, memoryUsagePercent: 61, gpuTemperatureC: 72 },
      { timestamp: '2026-06-29T10:00:05Z', cpuUsagePercent: 48, memoryUsagePercent: 63, gpuTemperatureC: 78 },
      { timestamp: '2026-06-29T10:00:10Z', cpuUsagePercent: 55, memoryUsagePercent: 64, gpuTemperatureC: 84, note: 'example only' }
    ];
    const body = `${sampleLines.map((line) => JSON.stringify(line)).join('\n')}\n`;
    const blob = new Blob([body], { type: 'application/x-ndjson;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'buildgraph-agent-sample.jsonl';
    anchor.click();
    URL.revokeObjectURL(url);
  }

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0] ?? null;
    setError('');
    setSelectedFile(null);
    setLogPreview('');
    setSubmitState('default');

    if (!file) return;

    const lowerName = file.name.toLowerCase();
    if (!lowerName.endsWith('.jsonl') && !lowerName.endsWith('.ndjson')) {
      setSubmitState('validation_error');
      setError('로그 파일 확장자는 .jsonl 또는 .ndjson만 사용할 수 있습니다.');
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
    if (!title) {
      setSubmitState('validation_error');
      setError('증상 제목을 입력해 주세요.');
      return;
    }
    if (!detail) {
      setSubmitState('validation_error');
      setError('증상 상세 내용을 입력해 주세요.');
      return;
    }
    if (!selectedFile) {
      setSubmitState('validation_error');
      setError('최근 30분 PC Agent 로그 파일을 선택해 주세요. .jsonl 또는 .ndjson 파일을 사용할 수 있습니다.');
      return;
    }
    if (!consentAccepted) {
      setSubmitState('consent_required');
      setError('로그 업로드와 30일 보관에 동의해야 AS를 접수할 수 있습니다.');
      return;
    }

    try {
      setSubmitState('uploading');
      await uploadAndCreateTicket(title, detail, selectedFile);
    } catch (cause) {
      if (cause instanceof TicketCreateError) {
        setSubmitState('ticket_error');
        setError('AS 티켓 생성에 실패했습니다. 로그인 상태를 확인한 뒤 다시 시도해 주세요.');
        return;
      }
      setSubmitState('upload_error');
      setError(uploadFailureMessage(cause));
    }
  }

  async function uploadAndCreateTicket(title: string, detail: string, file: File) {
    const uploadedLog = await uploadAgentLog(30, consentAccepted, file);
    const symptom = `${title}\n\n${detail}`;
    try {
      const ticket = await createSupportTicket(symptom, uploadedLog.id);
      setSubmitState('ticket_created');
      navigate(`/support/${ticket.id}`);
    } catch {
      throw new TicketCreateError();
    }
  }

  const isUploading = submitState === 'uploading';

  return (
    <Screen>
      <form onSubmit={submit} className="grid grid-cols-[minmax(0,1fr)_360px] gap-5">
        <Panel title="AS 접수" subtitle="증상과 PC Agent 로그를 함께 보내면 담당자가 더 정확히 확인할 수 있습니다.">
          <div className="space-y-4">
            <div>
              <label htmlFor="support-symptom-title" className="mb-1 block text-xs font-bold text-slate-600">증상 제목</label>
              <input
                id="support-symptom-title"
                className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                placeholder="예: 게임 중 프레임 드랍"
                value={symptomTitle}
                onChange={(event) => setSymptomTitle(event.target.value)}
              />
            </div>
            <div>
              <label htmlFor="support-symptom-detail" className="mb-1 block text-xs font-bold text-slate-600">증상 상세</label>
              <textarea
                id="support-symptom-detail"
                className="h-36 w-full rounded border border-slate-300 p-4 text-sm"
                placeholder="언제부터 발생했는지, 어떤 작업 중 재현되는지 입력해 주세요."
                value={symptomDetail}
                onChange={(event) => setSymptomDetail(event.target.value)}
              />
            </div>
            <div>
              <label htmlFor="support-log-file" className="mb-1 block text-xs font-bold text-slate-600">최근 30분 로그 파일</label>
              <button
                type="button"
                className="mb-2 rounded border border-slate-300 px-3 py-2 text-xs font-bold"
                onClick={downloadSampleJsonl}
              >
                샘플 JSONL 다운로드
              </button>
              <p className="mb-2 text-xs leading-5 text-slate-500">
                JSON Lines 형식 예시입니다. 서버 검증 규칙을 확정하는 파일은 아닙니다.
              </p>
              <input
                id="support-log-file"
                className="block w-full rounded border border-slate-300 p-3 text-sm file:mr-4 file:rounded file:border-0 file:bg-brand-blue file:px-4 file:py-2 file:text-sm file:font-bold file:text-white"
                type="file"
                accept=".jsonl,.ndjson,application/x-ndjson,application/json,text/plain"
                onChange={handleFileChange}
              />
              {selectedFile ? <p className="mt-2 text-xs text-slate-500">{selectedFile.name} · {selectedFile.size.toLocaleString()} bytes</p> : null}
            </div>
            <div className="min-h-32 rounded bg-slate-900 p-4 font-mono text-xs leading-6 text-slate-200">
              {logPreview ? <pre className="whitespace-pre-wrap">{logPreview}</pre> : '선택한 로그 파일의 일부가 여기에 표시됩니다.'}
            </div>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={consentAccepted} onChange={(event) => setConsentAccepted(event.target.checked)} />
              최근 30분 로그 업로드와 30일 보관 후 삭제 정책에 동의합니다.
            </label>
            {error ? <StateMessage type="warn" title="AS 접수 확인 필요" body={error} /> : null}
            {submitState === 'ticket_created' ? <StateMessage type="success" title="AS 티켓 생성 완료" body="생성된 티켓 상세 화면으로 이동합니다." /> : null}
            <button disabled={isUploading} className="rounded bg-brand-blue px-5 py-3 text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-400">
              {isUploading ? '로그 업로드 및 티켓 생성 중...' : 'AS 접수하기'}
            </button>
          </div>
        </Panel>
        <Panel title="접수 상태">
          {submitState === 'default' ? <StateMessage type="info" title="접수 준비" body="증상과 최근 30분 로그 파일을 함께 제출하면 AS 접수가 시작됩니다." /> : null}
          {submitState === 'validation_error' ? <StateMessage type="warn" title="입력 확인 필요" body={error || '증상과 로그 파일 입력값을 확인해 주세요.'} /> : null}
          {submitState === 'consent_required' ? <StateMessage type="warn" title="동의 필요" body="PC Agent 로그에는 사용 환경 정보가 포함될 수 있어 업로드 동의가 필요합니다." /> : null}
          {submitState === 'uploading' ? <StateMessage type="info" title="접수 중" body="로그를 업로드한 뒤 AS 티켓을 생성하고 있습니다." /> : null}
          {submitState === 'upload_error' ? <StateMessage type="warn" title="로그 업로드 실패" body={error || '로그 파일과 백엔드 실행 상태를 확인해 주세요.'} /> : null}
          {submitState === 'ticket_error' ? <StateMessage type="warn" title="티켓 생성 실패" body={error || 'AS 티켓을 생성하지 못했습니다. 잠시 후 다시 시도해 주세요.'} /> : null}
          {submitState === 'ticket_created' ? <StateMessage type="success" title="접수 완료" body="사용자 티켓 상세 화면에서 상태를 확인할 수 있습니다." /> : null}
        </Panel>
      </form>
    </Screen>
  );
}

class TicketCreateError extends Error {}

function uploadFailureMessage(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 400) {
    return '로그 업로드가 거부되었습니다. JSONL/NDJSON 파일 형식 또는 내용 검증에 실패했을 수 있습니다.';
  }
  return '로그 업로드에 실패했습니다. 파일을 다시 선택하거나 잠시 후 다시 시도해 주세요.';
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
        <StateMessage type="warn" title="AS 티켓 조회 실패" body="티켓 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요." />
      </Screen>
    );
  }

  return (
    <Screen>
      <div className="grid grid-cols-[minmax(0,1fr)_420px] gap-5">
        <Panel title={`AS 티켓 #${shortTicketId(ticket.id)}`} subtitle="접수 내용과 처리 상태를 확인할 수 있습니다.">
          <div className="mb-4 flex flex-wrap gap-2">
            <StatusBadge status={ticket.status} />
          </div>
          <DataTable columns={['시간', '주체', '내용']} rows={ticketTimeline(ticket)} />
        </Panel>
        <Panel title="담당자 확인 자료" subtitle="업로드한 로그를 바탕으로 담당자가 접수 내용을 확인합니다.">
          <DataTable columns={['확인 항목', '내용', '상태']} rows={causeRows(ticket.causeCandidates)} />
          <p className="mt-5 text-sm leading-6 text-slate-700">
            담당자가 증상과 로그를 확인한 뒤 필요한 경우 추가 정보를 요청할 수 있습니다.
          </p>
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
      주체: '시스템',
      내용: ticket.causeCandidates.length ? '로그 확인 자료 준비 완료' : '로그 확인 자료 준비 중'
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
    return [{ '확인 항목': '로그 확인', 내용: '티켓 접수 완료', 상태: <StatusBadge status="LOW" /> }];
  }
  return candidates.map((candidate) => ({
    '확인 항목': candidate.label ?? candidate.code ?? '로그 확인 항목',
    내용: candidate.evidenceIds?.length ? candidate.evidenceIds.join(', ') : '업로드한 로그 기반 참고 자료',
    상태: <StatusBadge status={candidate.confidence ?? 'MEDIUM'} />
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
