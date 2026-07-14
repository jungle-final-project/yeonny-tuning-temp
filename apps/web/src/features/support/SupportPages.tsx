import { ChangeEvent, FormEvent, useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { DataTable, Panel, Screen, StateMessage, StatusBadge, statusLabel } from '../../components/ui';
import { ApiError, getCachedAuthUser } from '../../lib/api';
import { formatSeoulTime } from '../../lib/dateTime';
import { getAsChat, sendAsChat, streamAsChat } from './asChatApi';
import type { AsChatEvidence, AsChatResponse, AsChatToolResult } from './asChatApi';
import { downloadPcAgentForCurrentUser } from './agentDownload';
import { prepareSupportLogFile } from './logFileProcessing';
import { createSupportTicket, getSupportDraft, getSupportTicket, previewAgentLogRag, requestRemoteSupport, submitSupportFeedback, uploadAgentLog } from './supportApi';
import { getCurrentSupportChat } from './supportChatApi';
import type { AsRagAnalysisDto, AsTicketDraftDto, AsTicketDto, CauseCandidate, SupportChatContact } from './types';

type SubmitState = 'default' | 'validation_error' | 'consent_required' | 'uploading' | 'upload_error' | 'ticket_error' | 'ticket_created';
type AgentDownloadState = 'idle' | 'issuing' | 'done' | 'error';
type AsRagPreviewState = 'idle' | 'loading' | 'ready' | 'error';
type SupportRequestKind = 'DIAGNOSIS_ONLY' | 'REMOTE_REQUESTED' | 'VISIT_REQUESTED';
type BlockingSupportChat = {
  asTicketId: string;
  supportChatRoomId?: string | null;
};
const remoteSymptomTypes = new Set([
  'REMOTE_AGENT',
  'REMOTE_DRIVER_OS',
  'REMOTE_APP_LAUNCHER',
  'REMOTE_STORAGE_MEMORY',
  'REMOTE_STARTUP_SERVICE',
  'REMOTE_LOCAL_NETWORK'
]);

const visitSymptomTypes = new Set([
  'VISIT_BOOT_REMOTE_BLOCKED',
  'VISIT_DISK_FAILURE',
  'VISIT_WHEA_BSOD',
  'VISIT_POWER_SHUTDOWN',
  'VISIT_FAN_THERMAL'
]);

const symptomTypeOptions = [
  ['REMOTE_AGENT', 'Agent 설치/등록/업로드/권한 오류'],
  ['REMOTE_DRIVER_OS', '드라이버/OS 업데이트/장치 오류'],
  ['REMOTE_APP_LAUNCHER', '앱/런처 실행 오류'],
  ['REMOTE_STORAGE_MEMORY', '저장공간 부족/메모리 압박'],
  ['REMOTE_STARTUP_SERVICE', '시작프로그램/서비스 부하'],
  ['REMOTE_LOCAL_NETWORK', '로컬 네트워크/DNS/어댑터 문제'],
  ['VISIT_BOOT_REMOTE_BLOCKED', '부팅 불가 또는 원격 연결 불가'],
  ['VISIT_DISK_FAILURE', '디스크 장애 의심'],
  ['VISIT_WHEA_BSOD', 'WHEA/블루스크린 반복'],
  ['VISIT_POWER_SHUTDOWN', '부하 시 전원 꺼짐'],
  ['VISIT_FAN_THERMAL', '팬/과열/열로 인한 강제 종료']
];

export function AsChatPage() {
  const [searchParams] = useSearchParams();
  const requestedTicketId = searchParams.get('asTicketId')?.trim() ?? '';
  const [ticketId, setTicketId] = useState(requestedTicketId);
  const [message, setMessage] = useState('게임 20분 뒤 프레임이 급락하고 GPU 온도가 95도까지 올라가요.');
  const [latestResponse, setLatestResponse] = useState<AsChatResponse | null>(null);
  const [error, setError] = useState('');
  const [progressMessage, setProgressMessage] = useState('');
  const [progressSteps, setProgressSteps] = useState<string[]>([]);

  // 티켓 번호를 타이핑하는 글자마다 조회가 나가지 않도록, 입력이 멈춘 뒤(300ms) 확정값으로만 조회한다.
  const [committedTicketId, setCommittedTicketId] = useState(requestedTicketId);
  useEffect(() => {
    const timer = setTimeout(() => setCommittedTicketId(ticketId.trim()), 300);
    return () => clearTimeout(timer);
  }, [ticketId]);

  const currentSupportQuery = useQuery({
    queryKey: ['support-chat-current', 'as-ai-entry'],
    queryFn: () => getCurrentSupportChat(),
    enabled: requestedTicketId.length === 0
  });

  useEffect(() => {
    const currentTicketId = currentSupportQuery.data?.contact?.asTicketId?.trim();
    if (!ticketId.trim() && currentTicketId) {
      setTicketId(currentTicketId);
      setCommittedTicketId(currentTicketId);
    }
  }, [currentSupportQuery.data?.contact?.asTicketId, ticketId]);

  const chatQuery = useQuery({
    queryKey: ['as-chat', committedTicketId],
    queryFn: () => getAsChat(committedTicketId),
    enabled: committedTicketId.length > 0
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
        setError('AI 상담 기능이 아직 준비되지 않았습니다. 잠시 후 다시 시도하거나 담당자에게 문의해 주세요.');
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
  const hasTicket = Boolean(ticketId.trim());
  const canSend = Boolean(ticketId.trim() && message.trim()) && !isBusy;

  function submitTicket(event: FormEvent) {
    event.preventDefault();
    setLatestResponse(null);
    setError('');
    if (!ticketId.trim()) {
      setError('먼저 AS 접수를 생성하거나 티켓 번호를 입력해 주세요.');
      return;
    }
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
      <div className="grid min-w-0 gap-5 xl:grid-cols-[minmax(0,1fr)_430px]">
        <div className="min-w-0 max-w-full">
        <Panel title="AS AI 챗봇" subtitle="AS 접수 후 티켓 증상과 검증 근거를 사용해 1차 상담 답변을 생성합니다.">
          <form onSubmit={submitTicket} className="mb-4 flex min-w-0 flex-col gap-3 sm:flex-row">
            <input
              className="h-11 min-w-0 w-full flex-1 rounded border border-slate-300 px-3 text-sm"
              value={ticketId}
              onChange={(event) => setTicketId(event.target.value)}
              aria-label="AS 티켓 번호"
            />
            <button className="w-full rounded border border-slate-300 px-4 py-2 text-sm font-bold sm:w-auto">티켓 불러오기</button>
          </form>

          {currentSupportQuery.isLoading ? <StateMessage type="info" title="최근 AS 접수 확인 중" body="현재 사용자에게 연결된 AS 티켓을 확인하고 있습니다." /> : null}
          {currentSupportQuery.isError ? <StateMessage type="warn" title="최근 AS 접수 확인 실패" body="티켓 번호를 직접 입력하거나 AS 접수 화면에서 새 접수를 만들어 주세요." /> : null}
          {chatQuery.isLoading ? <StateMessage type="info" title="챗봇 세션 조회 중" body="AS 티켓과 기존 대화 이력을 불러오고 있습니다." /> : null}
          {chatQuery.isError ? <StateMessage type="warn" title="챗봇 세션 조회 실패" body="대화 이력을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요." /> : null}
          {error ? <div className="mb-4"><StateMessage type="warn" title="AS AI 확인 필요" body={error} /></div> : null}

          <div className="h-[560px] overflow-y-auto rounded border border-slate-200 bg-slate-50 p-4">
            {!hasTicket && !currentSupportQuery.isLoading ? (
              <div className="flex h-full flex-col items-center justify-center gap-4 px-4 text-center">
                <StateMessage type="info" title="연결된 AS 접수가 없습니다" body="AS 접수를 먼저 생성하면 해당 티켓의 증상과 진단 근거로 AI 상담을 이어갈 수 있습니다." />
                <Link
                  to={currentSupportQuery.data?.supportNewPath ?? '/support/new'}
                  className="rounded bg-brand-blue px-4 py-2 text-sm font-black text-white"
                >
                  AS 접수 시작하기
                </Link>
              </div>
            ) : chat?.messages.length ? (
              <div className="space-y-3">
                {chat.messages.map((item) => (
                  <div key={item.id} className={`flex ${item.role === 'USER' ? 'justify-end' : 'justify-start'}`}>
                    <div className={`max-w-[88%] break-words rounded px-4 py-3 text-sm leading-6 shadow-sm sm:max-w-[72%] ${item.role === 'USER' ? 'bg-brand-blue text-white' : 'border border-slate-200 bg-white text-slate-800'}`}>
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
                <div className="font-bold">{progressMessage || 'AI가 근거 자료와 검증 결과를 확인하고 있습니다...'}</div>
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

          <form onSubmit={submitMessage} className="mt-4 flex min-w-0 flex-col gap-3 sm:flex-row">
            <textarea
              className="h-24 min-w-0 w-full flex-1 rounded border border-slate-300 p-3 text-sm"
              placeholder="예: 게임 20분 뒤 프레임이 급락하고 GPU 온도가 95도까지 올라가요."
              value={message}
              onChange={(event) => setMessage(event.target.value)}
            />
            <button disabled={!canSend} className="h-11 w-full rounded bg-brand-blue text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-400 sm:h-auto sm:w-32">
              {isBusy ? '전송 중' : '전송'}
            </button>
          </form>
        </Panel>
        </div>

        <div className="min-w-0 max-w-full space-y-5">
          <Panel title="티켓 / 모델">
            <div className="space-y-3 text-sm">
              <InfoRow label="AS 티켓" value={(chat?.asTicketId ?? ticketId) || '-'} />
              <InfoRow label="모델" value={chat?.model ?? '-'} />
              <InfoRow label="Agent 세션" value={chat?.agentSessionId ?? '-'} />
              <InfoRow label="상태" value={chat?.ticket.status ?? '-'} />
              <div>
                <div className="mb-1 text-xs font-bold text-slate-500">증상 요약</div>
                <p className="rounded border border-slate-200 bg-white p-3 leading-6 text-slate-700">{chat?.ticket.symptom ?? '-'}</p>
              </div>
            </div>
          </Panel>

          <Panel title="AI 분석 결과">
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
                  body={chat.escalation?.reason ?? '상담원 연결 판단 결과가 아직 없습니다.'}
                />
              </div>
            ) : (
              <StateMessage type="info" title="답변 대기" body="메시지를 보내면 AI 분석 결과가 여기에 표시됩니다." />
            )}
          </Panel>

          <Panel title="검증 결과">
            <DataTable columns={['검증 항목', '판정', '요약']} rows={toolRows(chat?.toolResults ?? [])} />
          </Panel>

          <Panel title="근거 자료">
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
  if (eventName === 'TOOLS_READY') return '검증 결과를 정리했습니다.';
  if (eventName === 'LLM_RUNNING') return 'AI 답변을 생성하고 있습니다.';
  return 'AS AI 처리를 진행하고 있습니다.';
}

export function SupportNewPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const draftId = searchParams.get('draftId')?.trim() ?? '';
  const [symptomTitle, setSymptomTitle] = useState('');
  const [symptomDetail, setSymptomDetail] = useState('');
  const [symptomType, setSymptomType] = useState('REMOTE_DRIVER_OS');
  const [detectedAt, setDetectedAt] = useState(() => datetimeLocalValue(new Date()));
  const [windowStartedAt, setWindowStartedAt] = useState(() => {
    const base = new Date();
    base.setMinutes(base.getMinutes() - 15);
    return datetimeLocalValue(base);
  });
  const [windowEndedAt, setWindowEndedAt] = useState(() => {
    const base = new Date();
    base.setMinutes(base.getMinutes() + 5);
    return datetimeLocalValue(base);
  });
  const [supportRequestKind, setSupportRequestKind] = useState<SupportRequestKind>('DIAGNOSIS_ONLY');
  const [consentAccepted, setConsentAccepted] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [draftLogUploadId, setDraftLogUploadId] = useState('');
  const [logFileNotice, setLogFileNotice] = useState('');
  const [asRagPreview, setAsRagPreview] = useState<AsRagAnalysisDto | null>(null);
  const [asRagPreviewState, setAsRagPreviewState] = useState<AsRagPreviewState>('idle');
  const [asRagPreviewError, setAsRagPreviewError] = useState('');
  const [submitState, setSubmitState] = useState<SubmitState>('default');
  const [agentDownloadState, setAgentDownloadState] = useState<AgentDownloadState>('idle');
  const [agentDownloadMessage, setAgentDownloadMessage] = useState('');
  const [error, setError] = useState('');
  const [conflictChat, setConflictChat] = useState<BlockingSupportChat | null>(null);
  const authScope = authScopeKey(getCachedAuthUser());
  const currentChatQuery = useQuery({
    queryKey: ['support-chat', authScope, 'intake-current'],
    queryFn: () => getCurrentSupportChat(),
    retry: false
  });
  const blockingChat = conflictChat ?? blockingChatFromContact(currentChatQuery.data?.contact ?? null);

  const draftQuery = useQuery({
    queryKey: ['as-ticket-draft', draftId],
    queryFn: () => getSupportDraft(draftId),
    enabled: Boolean(draftId)
  });

  useEffect(() => {
    const draft = draftQuery.data;
    if (!draft) return;
    setDraftLogUploadId(draft.logUploadId);
    setSymptomTitle(draft.title || 'PCAgent가 문제를 감지했습니다');
    setSymptomDetail(draft.detailDescription || draft.symptom || 'PCAgent가 문제 이벤트를 감지했습니다.');
    setSymptomType(draft.symptomType || 'REMOTE_AGENT');
    setSupportRequestKind(toSupportRequestKind(draft.supportRequestKind));
    setDetectedAt(datetimeLocalFromIso(draft.detectedAt) || datetimeLocalValue(new Date()));
    const draftStartedAt = datetimeLocalFromIso(draft.incidentWindow?.startedAt);
    const draftEndedAt = datetimeLocalFromIso(draft.incidentWindow?.endedAt);
    if (draftStartedAt) setWindowStartedAt(draftStartedAt);
    if (draftEndedAt) setWindowEndedAt(draftEndedAt);
    setConsentAccepted(true);
    setSelectedFile(null);
    setLogFileNotice('');
    setAsRagPreview(null);
    setAsRagPreviewState('idle');
    setAsRagPreviewError('');
    setSubmitState('default');
    setError('');
  }, [draftQuery.data]);

  function applyDefaultWindow(nextSymptomType = symptomType, nextDetectedAt = detectedAt) {
    const detected = datetimeLocalToDate(nextDetectedAt);
    const { preMinutes, postMinutes } = incidentWindowPreset(nextSymptomType);
    const start = new Date(detected);
    start.setMinutes(start.getMinutes() - preMinutes);
    const end = new Date(detected);
    end.setMinutes(end.getMinutes() + postMinutes);
    setWindowStartedAt(datetimeLocalValue(start));
    setWindowEndedAt(datetimeLocalValue(end));
  }

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

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0] ?? null;
    setError('');
    setSelectedFile(null);
    setLogFileNotice('');
    setAsRagPreview(null);
    setAsRagPreviewState('idle');
    setAsRagPreviewError('');
    setSubmitState('default');

    if (!file) return;

    let preparedFile: File;
    try {
      const prepared = await prepareSupportLogFile(file);
      preparedFile = prepared.file;
      setSelectedFile(prepared.file);
      setLogFileNotice(prepared.notice);
    } catch (cause) {
      setSubmitState('validation_error');
      setError(cause instanceof Error && cause.message ? cause.message : '로그 파일을 확인하지 못했습니다.');
      event.target.value = '';
      return;
    }

    setAsRagPreviewState('loading');
    previewAgentLogRag(incidentRangeMinutes(windowStartedAt, windowEndedAt), preparedFile)
      .then((analysis) => {
        setAsRagPreview(analysis);
        setAsRagPreviewState('ready');
        setSupportRequestKind(supportKindFromRecommendation(analysis.recommendedService));
      })
      .catch((cause) => {
        setAsRagPreviewState('error');
        setAsRagPreviewError(ragPreviewFailureMessage(cause));
      });
  }

  async function downloadPcAgent() {
    setAgentDownloadState('issuing');
    setAgentDownloadMessage('');
    try {
      await downloadPcAgentForCurrentUser();
      setAgentDownloadState('done');
      setAgentDownloadMessage('PCAgent.zip을 내려받았습니다. 압축을 풀고 PCAgent.exe를 실행하면 자동 등록됩니다.');
    } catch (cause) {
      setAgentDownloadState('error');
      setAgentDownloadMessage(cause instanceof ApiError && cause.status === 401
        ? '로그인 후 PCAgent를 다운로드해 주세요.'
        : 'Agent 등록 토큰 발급에 실패했습니다. 잠시 후 다시 시도해 주세요.');
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    if (blockingChat) {
      setSubmitState('ticket_error');
      setError('진행 중인 AS 상담이 있습니다.');
      return;
    }

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
    if (!windowEndedAt || !windowStartedAt || datetimeLocalToDate(windowEndedAt) <= datetimeLocalToDate(windowStartedAt)) {
      setSubmitState('validation_error');
      setError('업로드 구간의 종료 시각은 시작 시각보다 뒤여야 합니다.');
      return;
    }
    if (!selectedFile && !draftLogUploadId) {
      setSubmitState('validation_error');
      setError('선택한 문제 발생 전후 로그 구간의 PCAgent 로그 파일을 선택해 주세요. .jsonl 또는 .ndjson 파일을 사용할 수 있습니다.');
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
      if (cause instanceof ApiError && cause.status === 409 && cause.code === 'CONFLICT_STATE') {
        const refetched = await currentChatQuery.refetch();
        setConflictChat(blockingChatFromError(cause) ?? blockingChatFromContact(refetched.data?.contact ?? null));
        setSubmitState('ticket_error');
        setError('진행 중인 AS 상담이 있습니다.');
        return;
      }
      if (cause instanceof TicketCreateError) {
        setSubmitState('ticket_error');
        setError('AS 티켓 생성에 실패했습니다. 로그인 상태를 확인한 뒤 다시 시도해 주세요.');
        return;
      }
      setSubmitState('upload_error');
      setError(uploadFailureMessage(cause));
    }
  }

  async function uploadAndCreateTicket(title: string, detail: string, file: File | null) {
    const rangeMinutes = incidentRangeMinutes(windowStartedAt, windowEndedAt);
    const incidentId = `web-incident-${crypto.randomUUID()}`;
    const uploadedLog = file ? await uploadAgentLog(rangeMinutes, consentAccepted, file, {
      incidentId,
      triggerType: 'USER_REQUEST',
      symptomType,
      detectedAt: toIsoFromDatetimeLocal(detectedAt),
      rangeStartedAt: toIsoFromDatetimeLocal(windowStartedAt),
      rangeEndedAt: toIsoFromDatetimeLocal(windowEndedAt),
      selectedByUser: true,
      consentId: `web-consent-${incidentId}`
    }) : null;
    const logUploadId = uploadedLog?.id || draftLogUploadId;
    const symptom = [
      title,
      '',
      detail,
      '',
      `[증상 유형] ${symptomLabel(symptomType)}`,
      `[문제 발생 전후 로그] ${windowStartedAt} ~ ${windowEndedAt}`,
      `[지원 신청] ${supportRequestLabel(supportRequestKind)}`
    ].join('\n');
    try {
      const ticket = await createSupportTicket(symptom, logUploadId);
      setSubmitState('ticket_created');
      navigate(`/support/${ticket.id}`);
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409 && cause.code === 'CONFLICT_STATE') {
        throw cause;
      }
      throw new TicketCreateError();
    }
  }

  const isUploading = submitState === 'uploading';
  const isSubmitDisabled = isUploading || Boolean(blockingChat);

  return (
    <Screen>
      <form onSubmit={submit} className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_360px]">
        {blockingChat ? (
          <div className="lg:col-span-2">
            <ActiveSupportChatNotice chat={blockingChat} />
          </div>
        ) : null}
        <Panel title="AS 접수" subtitle="증상과 PCAgent 로그를 함께 보내면 담당자가 더 정확히 확인할 수 있습니다.">
          <div className="space-y-4">
            {draftQuery.isLoading ? <StateMessage type="info" title="Agent 초안 불러오는 중" body="감지된 문제와 선택 구간 로그 정보를 확인하고 있습니다." /> : null}
            {draftQuery.isError ? <StateMessage type="warn" title="Agent 초안 조회 실패" body="초안을 불러오지 못했습니다. 로그인 상태를 확인하거나 수동으로 AS를 접수해 주세요." /> : null}
            {draftLogUploadId ? <StateMessage type="success" title="Agent 초안 적용됨" body="감지된 문제 시각과 선택 구간 로그가 접수 폼에 반영되었습니다." /> : null}
            <div>
              <label className="mb-1 block text-xs font-bold text-slate-600">증상 제목</label>
              <input
                id="support-symptom-title"
                aria-label="증상 제목"
                className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                placeholder="예: 게임 중 프레임 드랍"
                value={symptomTitle}
                onChange={(event) => setSymptomTitle(event.target.value)}
              />
            </div>
            <div className="grid gap-3 md:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-bold text-slate-600">증상 유형</label>
                <select
                  className="h-11 w-full rounded border border-slate-300 bg-white px-3 text-sm"
                  value={symptomType}
                  onChange={(event) => {
                    const next = event.target.value;
                    setSymptomType(next);
                    applyDefaultWindow(next, detectedAt);
                  }}
                >
                  {symptomTypeOptions.map(([value, label]) => (
                    <option key={value} value={value}>{label}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="mb-1 block text-xs font-bold text-slate-600">증상 발생 시각</label>
                <input
                  type="datetime-local"
                  className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                  value={detectedAt}
                  onChange={(event) => {
                    setDetectedAt(event.target.value);
                    applyDefaultWindow(symptomType, event.target.value);
                  }}
                />
              </div>
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-slate-600">증상 상세</label>
              <textarea
                id="support-symptom-detail"
                aria-label="증상 상세"
                className="h-36 w-full rounded border border-slate-300 p-4 text-sm"
                placeholder="언제부터 발생했는지, 어떤 작업 중 재현되는지 입력해 주세요."
                value={symptomDetail}
                onChange={(event) => setSymptomDetail(event.target.value)}
              />
            </div>
            <div className="rounded border border-slate-200 bg-slate-50 p-4">
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <p className="text-sm font-bold text-slate-800">문제 발생 전후 로그</p>
                <button
                  type="button"
                  className="rounded border border-slate-300 bg-white px-3 py-2 text-xs font-bold"
                  onClick={() => applyDefaultWindow()}
                >
                  기본 구간 다시 적용
                </button>
              </div>
              <p className="mb-3 text-xs leading-5 text-slate-500">
                증상 발생 시점을 기준으로 필요한 구간의 로그만 업로드합니다. 필요하면 시작/종료 시각을 직접 조정할 수 있습니다.
              </p>
              <div className="grid gap-3 md:grid-cols-2">
                <div>
                  <label className="mb-1 block text-xs font-bold text-slate-600">시작 시각</label>
                  <input
                    type="datetime-local"
                    className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                    value={windowStartedAt}
                    onChange={(event) => setWindowStartedAt(event.target.value)}
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-bold text-slate-600">종료 시각</label>
                  <input
                    type="datetime-local"
                    className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                    value={windowEndedAt}
                    onChange={(event) => setWindowEndedAt(event.target.value)}
                  />
                </div>
              </div>
              <p className="mt-2 text-xs font-semibold text-slate-600">선택 구간: 약 {incidentRangeMinutes(windowStartedAt, windowEndedAt)}분</p>
            </div>
            <div>
              <label className="mb-2 block text-xs font-bold text-slate-600">지원 신청 방식</label>
              <div className="grid gap-2 md:grid-cols-3">
                {(['DIAGNOSIS_ONLY', 'REMOTE_REQUESTED', 'VISIT_REQUESTED'] as SupportRequestKind[]).map((kind) => (
                  <label key={kind} className="flex min-h-12 items-center gap-2 rounded border border-slate-200 px-3 py-2 text-sm font-bold text-slate-700">
                    <input
                      type="radio"
                      checked={supportRequestKind === kind}
                      onChange={() => setSupportRequestKind(kind)}
                    />
                    {supportRequestLabel(kind)}
                  </label>
                ))}
              </div>
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-slate-600">선택 구간 로그 파일</label>
              <div className="mb-2 flex flex-wrap gap-2">
                <button
                  type="button"
                  className="rounded border border-brand-blue px-3 py-2 text-xs font-bold text-brand-blue"
                  onClick={downloadPcAgent}
                  disabled={agentDownloadState === 'issuing'}
                >
                  {agentDownloadState === 'issuing' ? '등록 토큰 발급 중...' : 'PCAgent 다운로드'}
                </button>
                <a
                  className="rounded border border-slate-300 px-3 py-2 text-xs font-bold"
                  href="/downloads/pc-agent/README.txt"
                  download
                >
                  실행 안내
                </a>
                <button
                  type="button"
                  className="rounded border border-slate-300 px-3 py-2 text-xs font-bold"
                  onClick={downloadSampleJsonl}
                >
                  샘플 JSONL 다운로드
                </button>
              </div>
              <p className="mb-2 text-xs leading-5 text-slate-500">
                {draftLogUploadId
                  ? 'PCAgent가 선택한 구간의 로그를 이미 gzip으로 전송했습니다. 다른 로그로 교체할 때만 파일을 선택해 주세요.'
                  : 'PCAgent는 더블클릭 시 트레이 아이콘으로 백그라운드 수집을 시작합니다. 선택한 구간의 로그만 gzip 또는 JSONL로 전송합니다.'}
              </p>
              {agentDownloadMessage ? (
                <p className={`mb-2 text-xs font-semibold ${agentDownloadState === 'error' ? 'text-red-600' : 'text-emerald-700'}`}>
                  {agentDownloadMessage}
                </p>
              ) : null}
              <input
                id="support-log-file"
                className="block w-full rounded border border-slate-300 p-3 text-sm file:mr-4 file:rounded file:border-0 file:bg-brand-blue file:px-4 file:py-2 file:text-sm file:font-bold file:text-white"
                type="file"
                accept=".jsonl,.ndjson,application/x-ndjson,application/json,text/plain"
                onChange={handleFileChange}
              />
              {selectedFile ? <p className="mt-2 text-xs text-slate-500">{selectedFile.name} · {selectedFile.size.toLocaleString()} bytes</p> : null}
              {logFileNotice ? <p className="mt-2 text-xs font-semibold text-emerald-700">{logFileNotice}</p> : null}
              {draftLogUploadId && !selectedFile ? <p className="mt-2 text-xs font-semibold text-brand-blue">Agent 업로드 로그 ID: {draftLogUploadId}</p> : null}
            </div>
            {asRagPreviewState === 'loading' ? <StateMessage type="info" title="AS RAG 분석 중" body="업로드한 로그를 바탕으로 적절한 지원 방식을 찾고 있습니다." /> : null}
            {asRagPreviewState === 'error' ? <StateMessage type="warn" title="AS RAG 추천 실패" body={asRagPreviewError || '추천 결과를 불러오지 못했습니다. AS 접수는 계속 진행할 수 있습니다.'} /> : null}
            {asRagPreview ? <AsRagRecommendation analysis={asRagPreview} /> : null}
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={consentAccepted} onChange={(event) => setConsentAccepted(event.target.checked)} />
              선택한 구간의 로그 업로드와 30일 보관 후 삭제 정책에 동의합니다.
            </label>
            {error ? <StateMessage type="warn" title="AS 접수 확인 필요" body={error} /> : null}
            {submitState === 'ticket_created' ? <StateMessage type="success" title="AS 티켓 생성 완료" body="생성된 티켓 상세 화면으로 이동합니다." /> : null}
            <button disabled={isSubmitDisabled} className="rounded bg-brand-blue px-5 py-3 text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-400">
              {isUploading ? '로그 업로드 및 티켓 생성 중...' : 'AS 접수하기'}
            </button>
          </div>
        </Panel>
        <Panel title="접수 상태">
          {submitState === 'default' ? <StateMessage type="info" title="접수 준비" body="증상 유형, 발생 시각, 선택 구간 로그를 함께 제출하면 AS 접수가 시작됩니다." /> : null}
          {submitState === 'validation_error' ? <StateMessage type="warn" title="입력 확인 필요" body={error || '증상과 로그 파일 입력값을 확인해 주세요.'} /> : null}
          {submitState === 'consent_required' ? <StateMessage type="warn" title="동의 필요" body="PCAgent 로그에는 사용 환경 정보가 포함될 수 있어 업로드 동의가 필요합니다." /> : null}
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

function ActiveSupportChatNotice({ chat }: { chat: BlockingSupportChat }) {
  return (
    <div className="space-y-3">
      <StateMessage
        type="info"
        title="진행 중인 AS 상담이 있습니다."
        body="관리자가 상담을 닫기 전까지 새 AS 접수를 만들 수 없습니다."
      />
      <Link
        to={`/support/${chat.asTicketId}?chat=1`}
        className="inline-flex rounded bg-brand-blue px-4 py-3 text-sm font-bold text-white hover:bg-blue-700"
      >
        진행 중인 상담방으로 이동
      </Link>
    </div>
  );
}

function blockingChatFromContact(contact: SupportChatContact | null): BlockingSupportChat | null {
  if (!contact || contact.canSendMessage === false) {
    return null;
  }
  return {
    asTicketId: contact.asTicketId,
    supportChatRoomId: contact.id
  };
}

function blockingChatFromError(error: ApiError): BlockingSupportChat | null {
  const asTicketId = error.details?.asTicketId;
  if (typeof asTicketId !== 'string' || !asTicketId) {
    return null;
  }
  const supportChatRoomId = error.details?.supportChatRoomId;
  return {
    asTicketId,
    supportChatRoomId: typeof supportChatRoomId === 'string' ? supportChatRoomId : null
  };
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

function AsRagRecommendation({ analysis }: { analysis: AsRagAnalysisDto }) {
  return (
    <div className="rounded border border-blue-200 bg-blue-50 p-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-bold text-blue-950">추천 서비스</span>
        <StatusBadge status={analysis.supportDecision ?? 'NEEDS_MORE_INFO'} />
        {analysis.confidence ? <StatusBadge status={analysis.confidence} /> : null}
      </div>
      <p className="mt-2 text-base font-bold text-blue-950">
        {analysis.recommendationMessage ?? `이 증상은 ${analysis.recommendedServiceLabel ?? '우선 진단만 받기'} 서비스를 받는 것이 좋습니다.`}
      </p>
      {analysis.summaryText ? <p className="mt-2 text-sm leading-6 text-blue-900">{analysis.summaryText}</p> : null}
      {analysis.evidence?.length ? (
        <div className="mt-3 space-y-1 text-xs leading-5 text-blue-800">
          {analysis.evidence.slice(0, 2).map((item, index) => (
            <p key={`${String(item.sourceId ?? index)}`}>근거 {index + 1}. {String(item.summary ?? item.title ?? item.sourceId ?? 'AS RAG 근거')}</p>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function supportKindFromRecommendation(value?: string): SupportRequestKind {
  if (value === 'REMOTE_SUPPORT') return 'REMOTE_REQUESTED';
  if (value === 'VISIT_SUPPORT') return 'VISIT_REQUESTED';
  return 'DIAGNOSIS_ONLY';
}

function uploadFailureMessage(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 413) {
    return '로그 파일이 너무 큽니다. agent-metrics.jsonl을 선택하면 브라우저에서 최신 기록 기준 최근 30분만 추출해 업로드합니다.';
  }
  if (cause instanceof ApiError && cause.status === 400) {
    if (cause.code === 'FILE_VALIDATION_ERROR') {
      return '로그 파일 검증에 실패했습니다. JSONL/NDJSON 형식, 10MiB 이하 크기, 최대 20000라인 기준을 확인해 주세요.';
    }
    return '로그 업로드가 거부되었습니다. 선택 구간, JSONL/NDJSON 파일 형식 또는 내용 검증에 실패했을 수 있습니다.';
  }
  return '로그 업로드에 실패했습니다. 파일을 다시 선택하거나 잠시 후 다시 시도해 주세요.';
}

function ragPreviewFailureMessage(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 413) {
    return '파일이 너무 커서 RAG 미리보기를 만들 수 없습니다. agent-metrics.jsonl은 최신 기록 기준 최근 30분만 추출해 다시 분석합니다.';
  }
  if (cause instanceof ApiError && cause.status === 400 && cause.code === 'FILE_VALIDATION_ERROR') {
    return 'AS RAG 추천을 만들 수 없습니다. JSONL/NDJSON 형식, 10MiB 이하 크기, 최대 20000라인 기준을 확인해 주세요.';
  }
  return cause instanceof Error && cause.message ? cause.message : 'AS RAG 추천을 불러오지 못했습니다.';
}

export function SupportTicketPage() {
  const { ticketId = '00000000-0000-4000-8000-000000006001' } = useParams();
  const queryClient = useQueryClient();
  const [remoteRequestReason, setRemoteRequestReason] = useState('원격지원으로 화면을 함께 확인하고 싶습니다.');
  const [remoteRequestError, setRemoteRequestError] = useState('');
  const [feedbackRating, setFeedbackRating] = useState('5');
  const [feedbackComment, setFeedbackComment] = useState('');
  const [feedbackError, setFeedbackError] = useState('');
  const { data: ticket, isError, isLoading } = useQuery({
    queryKey: ['support-ticket', ticketId],
    queryFn: () => getSupportTicket(ticketId),
    refetchInterval: 5_000
  });
  const remoteRequestMutation = useMutation({
    mutationFn: async () => {
      const reason = remoteRequestReason.trim();
      return requestRemoteSupport(ticketId, {
        reason: reason || '사용자가 원격지원을 요청했습니다.'
      });
    },
    onSuccess: (updatedTicket) => {
      setRemoteRequestError('');
      queryClient.setQueryData(['support-ticket', ticketId], updatedTicket);
    },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === 409) {
        setRemoteRequestError('이미 진행 중인 원격지원 요청이 있습니다.');
        return;
      }
      if (cause instanceof ApiError && cause.status === 400) {
        setRemoteRequestError('원격지원 요청 사유를 입력해 주세요.');
        return;
      }
      setRemoteRequestError('원격지원 요청을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    }
  });
  const feedbackMutation = useMutation({
    mutationFn: async () => submitSupportFeedback(ticketId, {
      rating: Number(feedbackRating),
      comment: feedbackComment.trim() || undefined
    }),
    onSuccess: (updatedTicket) => {
      setFeedbackError('');
      queryClient.setQueryData(['support-ticket', ticketId], updatedTicket);
    },
    onError: () => {
      setFeedbackError('피드백을 저장하지 못했습니다. 평점 값을 확인한 뒤 다시 시도해 주세요.');
    }
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

  const remoteRequestLocked = isActiveRemoteSupportStatus(ticket.remoteSupportStatus);

  return (
    <Screen>
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_420px]">
        <Panel title={`AS 티켓 #${shortTicketId(ticket.id)}`} subtitle="접수 내용과 처리 상태를 확인할 수 있습니다.">
          <div className="mb-4 flex flex-wrap gap-2">
            <StatusBadge status={ticket.status} />
            {ticket.analysisStatus ? <StatusBadge status={ticket.analysisStatus} /> : null}
            {ticket.reviewStatus ? <StatusBadge status={ticket.reviewStatus} /> : null}
            {ticket.supportDecision ? <StatusBadge status={ticket.supportDecision} /> : null}
          </div>
          {hasSafetyAdvice(ticket) ? <SafetyNoticePanel ticket={ticket} /> : null}
          <DataTable columns={['시간', '주체', '내용']} rows={ticketTimeline(ticket)} />
        </Panel>
        <Panel title="담당자 확인 자료" subtitle="업로드한 로그를 바탕으로 담당자가 접수 내용을 확인합니다.">
          <DataTable columns={['확인 항목', '내용', '상태']} rows={causeRows(ticket.causeCandidates)} />
          <div className="mt-5">
            <DataTable columns={['항목', '값']} rows={ticketDecisionRows(ticket)} />
          </div>
          {ticket.remoteSupportLink ? (
            <div className="mt-5 rounded border border-blue-200 bg-blue-50 p-4">
              <p className="text-sm font-bold text-blue-900">Quick Assist 안내</p>
              <p className="mt-2 break-all text-sm leading-6 text-blue-800">{ticket.remoteSupportLink}</p>
              <p className="mt-2 text-xs text-blue-700">원격 연결 전 사용자 추가 확인이 필요합니다. Quick Assist는 사용자가 직접 코드를 입력해 연결합니다.</p>
            </div>
          ) : null}
          {!ticket.remoteSupportLink ? (
            <form
              className="mt-5 rounded border border-slate-200 bg-slate-50 p-4"
              onSubmit={(event) => {
                event.preventDefault();
                remoteRequestMutation.mutate();
              }}
            >
              <label className="mb-2 block text-sm font-bold text-slate-800">원격지원 요청</label>
              <textarea
                className="h-20 w-full rounded border border-slate-300 bg-white p-3 text-sm"
                value={remoteRequestReason}
                onChange={(event) => setRemoteRequestReason(event.target.value)}
                disabled={remoteRequestMutation.isPending || remoteRequestLocked}
              />
              {remoteRequestError ? <p className="mt-2 text-xs font-semibold text-red-600">{remoteRequestError}</p> : null}
              {remoteRequestLocked ? (
                <p className="mt-2 text-xs font-semibold text-brand-blue">원격지원 상태: {statusLabel(ticket.remoteSupportStatus ?? 'REQUESTED')}</p>
              ) : null}
              <button
                className="mt-3 rounded bg-brand-blue px-4 py-2 text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-400"
                disabled={remoteRequestMutation.isPending || remoteRequestLocked}
              >
                {remoteRequestMutation.isPending ? '요청 저장 중...' : '원격지원 요청'}
              </button>
            </form>
          ) : null}
          <form
            className="mt-5 rounded border border-slate-200 bg-white p-4"
            onSubmit={(event) => {
              event.preventDefault();
              feedbackMutation.mutate();
            }}
          >
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <label className="text-sm font-bold text-slate-800">처리 피드백</label>
              {ticket.feedbackRating ? <span className="text-xs font-semibold text-slate-500">저장된 평점 {ticket.feedbackRating}/5</span> : null}
            </div>
            <div className="grid gap-3 md:grid-cols-[120px_minmax(0,1fr)]">
              <select
                className="h-10 rounded border border-slate-300 bg-white px-3 text-sm"
                value={feedbackRating}
                onChange={(event) => setFeedbackRating(event.target.value)}
              >
                {[5, 4, 3, 2, 1].map((rating) => (
                  <option key={rating} value={rating}>{rating}점</option>
                ))}
              </select>
              <input
                className="h-10 rounded border border-slate-300 px-3 text-sm"
                value={feedbackComment}
                onChange={(event) => setFeedbackComment(event.target.value)}
                placeholder={ticket.feedbackComment || '처리 결과에 대한 의견을 남겨 주세요.'}
              />
            </div>
            {feedbackError ? <p className="mt-2 text-xs font-semibold text-red-600">{feedbackError}</p> : null}
            <button
              className="mt-3 rounded border border-slate-300 px-4 py-2 text-sm font-bold disabled:cursor-not-allowed disabled:bg-slate-100"
              disabled={feedbackMutation.isPending}
            >
              {feedbackMutation.isPending ? '피드백 저장 중...' : '피드백 저장'}
            </button>
          </form>
          <p className="mt-5 text-sm leading-6 text-slate-700">
            담당자가 증상과 로그를 확인한 뒤 필요한 경우 추가 정보를 요청할 수 있습니다.
          </p>
          <Link to="/support/new" className="mt-5 block rounded border border-slate-300 px-4 py-3 text-center text-sm font-bold">새 AS 접수</Link>
        </Panel>
      </div>
    </Screen>
  );
}

function SafetyNoticePanel({ ticket }: { ticket: AsTicketDto }) {
  const notices = ticket.safetyNotices?.length ? ticket.safetyNotices : [{ message: safetyAdviceMessage(ticket.safetyAdviceLevel) }];
  return (
    <div className="mb-4 rounded border border-red-200 bg-red-50 p-4">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <p className="text-sm font-bold text-red-900">안전 안내</p>
        {ticket.safetyAdviceLevel ? <StatusBadge status={ticket.safetyAdviceLevel} /> : null}
      </div>
      <ul className="space-y-1 text-sm leading-6 text-red-800">
        {notices.map((notice, index) => (
          <li key={`${notice.code ?? 'notice'}-${index}`}>{notice.message || safetyAdviceMessage(ticket.safetyAdviceLevel)}</li>
        ))}
      </ul>
    </div>
  );
}

function hasSafetyAdvice(ticket: AsTicketDto) {
  return Boolean(
    (ticket.safetyAdviceLevel && ticket.safetyAdviceLevel !== 'NONE')
    || ticket.safetyNotices?.length
  );
}

function isActiveRemoteSupportStatus(status?: string | null) {
  return status === 'REQUESTED' || status === 'LINK_SENT' || status === 'IN_PROGRESS';
}

function safetyAdviceMessage(level?: string | null) {
  if (level === 'STOP_USE_UNTIL_REVIEW') {
    return '담당자 검토 전까지 해당 PC 사용을 중지하거나 중요한 작업을 피해 주세요.';
  }
  if (level === 'CAUTION') {
    return '하드웨어 오류 가능성이 있어 추가 조치 전 상태를 주의 깊게 확인해 주세요.';
  }
  return '담당자가 위험 신호를 확인하고 있습니다.';
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
      내용: ticket.analysisStatus ? `진단 상태: ${statusLabel(ticket.analysisStatus)}` : ticket.causeCandidates.length ? '로그 확인 자료 준비 완료' : '로그 확인 자료 준비 중'
    },
    {
      시간: '-',
      주체: '상담원',
      내용: ticket.supportDecision ? `지원 결정: ${statusLabel(ticket.supportDecision)}` : ticket.assignedAdminId ? '담당자 배정 완료' : '담당자 배정 대기'
    }
  ];
}

function ticketDecisionRows(ticket: AsTicketDto) {
  return [
    { 항목: '진단 상태', 값: ticket.analysisStatus ? <StatusBadge status={ticket.analysisStatus} /> : '-' },
    { 항목: '검토 상태', 값: ticket.reviewStatus ? <StatusBadge status={ticket.reviewStatus} /> : '-' },
    { 항목: '지원 결정', 값: ticket.supportDecision ? <StatusBadge status={ticket.supportDecision} /> : '-' },
    { 항목: '위험도', 값: ticket.riskLevel ? <StatusBadge status={ticket.riskLevel} /> : '-' },
    { 항목: '관리자 메모', 값: ticket.adminNote ?? '-' },
    { 항목: '원격지원', 값: ticket.remoteSupportLink ? `${statusLabel(ticket.remoteSupportStatus ?? 'LINK_SENT')} · ${ticket.remoteSupportLink}` : ticket.remoteSupportStatus ? statusLabel(ticket.remoteSupportStatus) : '-' },
    { 항목: '방문지원', 값: ticket.visitSupportRequired ? `${statusLabel(ticket.visitSupportStatus ?? 'REQUESTED')} ${ticket.visitPreferredDate ?? ''} ${visitSlotLabel(ticket.visitTimeSlot)}`.trim() : '-' }
  ];
}

function causeRows(candidates: CauseCandidate[]) {
  if (!candidates.length) {
    return [{ '확인 항목': '로그 확인', 내용: '티켓 접수 완료', 상태: <StatusBadge status="LOW" /> }];
  }
  return candidates.map((candidate) => ({
    '확인 항목': candidate.label ?? candidate.code ?? '로그 확인 항목',
    내용: candidate.reason ?? (candidate.evidenceIds?.length ? candidate.evidenceIds.join(', ') : '업로드한 로그 기반 참고 자료'),
    상태: <StatusBadge status={candidate.confidence ?? 'MEDIUM'} />
  }));
}

function shortTicketId(id: string) {
  const parts = id.split('-');
  const lastPart = parts[parts.length - 1];
  return lastPart ? lastPart.replace(/^0+/, '') || lastPart : id;
}

function formatTime(value?: string) {
  return formatSeoulTime(value);
}

function datetimeLocalValue(date: Date) {
  const offset = date.getTimezoneOffset();
  const local = new Date(date.getTime() - offset * 60_000);
  return local.toISOString().slice(0, 16);
}

function datetimeLocalFromIso(value?: string | null) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return datetimeLocalValue(date);
}

function datetimeLocalToDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? new Date() : date;
}

function toIsoFromDatetimeLocal(value: string) {
  return datetimeLocalToDate(value).toISOString();
}

function incidentWindowPreset(symptomType: string) {
  if (symptomType === 'VISIT_BOOT_REMOTE_BLOCKED') {
    return { preMinutes: 30, postMinutes: 0 };
  }
  if (visitSymptomTypes.has(symptomType)) {
    return { preMinutes: 30, postMinutes: 10 };
  }
  if (remoteSymptomTypes.has(symptomType)) {
    return { preMinutes: 15, postMinutes: 5 };
  }
  return { preMinutes: 15, postMinutes: 5 };
}

function incidentRangeMinutes(startValue: string, endValue: string) {
  const start = datetimeLocalToDate(startValue).getTime();
  const end = datetimeLocalToDate(endValue).getTime();
  if (Number.isNaN(start) || Number.isNaN(end) || end <= start) {
    return 0;
  }
  return Math.max(1, Math.ceil((end - start) / 60_000));
}

function symptomLabel(value: string) {
  return symptomTypeOptions.find(([option]) => option === value)?.[1] ?? value;
}

function supportRequestLabel(value: SupportRequestKind) {
  if (value === 'REMOTE_REQUESTED') return '원격지원 신청';
  if (value === 'VISIT_REQUESTED') return '방문지원 신청';
  return '우선 진단만 받기';
}

function toSupportRequestKind(value?: string | null): SupportRequestKind {
  if (value === 'REMOTE_REQUESTED' || value === 'VISIT_REQUESTED' || value === 'DIAGNOSIS_ONLY') {
    return value;
  }
  return 'DIAGNOSIS_ONLY';
}

function visitSlotLabel(value?: string | null) {
  if (value === 'MORNING') return '오전';
  if (value === 'AFTERNOON') return '오후';
  if (value === 'EVENING') return '저녁';
  return value ?? '';
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
    return [{ 원인: '원인 후보 없음', 신뢰도: <StatusBadge status="LOW" />, 근거: 'AI 응답에 원인 후보가 없습니다.' }];
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
    return [{ 조치: '추가 조치 없음', 우선순위: <StatusBadge status="LOW" />, 안내: 'AI 응답에 다음 조치가 없습니다.' }];
  }
  return actions.map((action) => ({
    조치: action.label ?? '다음 조치',
    우선순위: <StatusBadge status={action.priority ?? 'MEDIUM'} />,
    안내: action.instruction ?? '-'
  }));
}

const TOOL_NAME_LABELS: Record<string, string> = {
  compatibility: '호환성',
  power: '전력',
  size: '규격',
  performance: '성능',
  price: '가격'
};

function toolRows(toolResults: AsChatToolResult[]) {
  if (!toolResults.length) {
    return [{ '검증 항목': '대기', 판정: <StatusBadge status="LOW" />, 요약: '메시지 전송 후 검증 결과가 표시됩니다.' }];
  }
  return toolResults.map((tool) => ({
    '검증 항목': TOOL_NAME_LABELS[tool.toolName] ?? tool.toolName,
    판정: <StatusBadge status={tool.status} />,
    요약: tool.summary
  }));
}

function evidenceRows(evidence: AsChatEvidence[]) {
  if (!evidence.length) {
    return [{ 근거: '대기', 점수: '-', 요약: '메시지 전송 후 근거 자료가 표시됩니다.' }];
  }
  return evidence.map((item) => ({
    근거: item.sourceId,
    점수: item.score == null ? '-' : String(item.score),
    요약: item.summary
  }));
}
