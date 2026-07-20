import { FormEvent, useEffect, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge, statusLabel } from '../../../components/ui';
import { formatSeoulDateTime } from '../../../lib/dateTime';
import {
  approveAdminTicketRemoteSupport,
  assignAdminTicketToMe,
  completeAdminTicketRemoteSupport,
  createAsRecommendationFeedback,
  getAdminTicket,
  getAdminTicketRemoteAccessCode,
  getAdminTicketRemoteSupport,
  requestAdminTicketMoreInfo,
  startAdminTicketRemoteSupport
} from '../adminApi';
import type { AdminAsTicket, AdminRemoteSupportState } from '../adminApi';
import { AdminTicketSupportChat } from '../components/AdminTicketSupportChat';

const FAILURE_CATEGORY_OPTIONS = ['RECOMMENDATION_BUILD', 'PART_SELECTION', 'COMPATIBILITY', 'PERFORMANCE', 'USER_ENVIRONMENT', 'AGENT_LOG_ONLY', 'OTHER'];
const SEVERITY_OPTIONS = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const CATEGORY_OPTIONS = ['', 'CPU', 'GPU', 'RAM', 'MOTHERBOARD', 'STORAGE', 'PSU', 'CASE', 'COOLER'];

type ReviewAction = 'ASSIGN_TO_ME' | 'REQUEST_MORE_INFO' | 'APPROVE_REMOTE_SUPPORT';
type RemoteSupportAction = 'START' | 'COMPLETE';
const CHROME_REMOTE_DESKTOP_SUPPORT_URL = 'https://remotedesktop.google.com/support';

export function AdminTicketDetailPage() {
  const { ticketId = '' } = useParams();
  const queryClient = useQueryClient();
  const [adminNote, setAdminNote] = useState('');
  const [lastAction, setLastAction] = useState<ReviewAction | null>(null);
  const [remoteSupportNotice, setRemoteSupportNotice] = useState('');
  const [failureCategory, setFailureCategory] = useState('RECOMMENDATION_BUILD');
  const [severity, setSeverity] = useState('MEDIUM');
  const [relatedPartId, setRelatedPartId] = useState('');
  const [relatedBuildId, setRelatedBuildId] = useState('');
  const [recommendationId, setRecommendationId] = useState('');
  const [feedbackCategory, setFeedbackCategory] = useState('');
  const [useForRecommendationTraining, setUseForRecommendationTraining] = useState(true);
  const [labelNote, setLabelNote] = useState('');

  const ticketQuery = useQuery({
    queryKey: ['admin-as-ticket', ticketId],
    queryFn: () => getAdminTicket(ticketId),
    enabled: Boolean(ticketId)
  });
  const remoteSupportApproved = ticketQuery.data?.reviewStatus === 'APPROVED'
    && ticketQuery.data.supportDecision === 'REMOTE_POSSIBLE';
  const remoteSupportQuery = useQuery({
    queryKey: ['admin-as-ticket-remote-support', ticketId],
    queryFn: () => getAdminTicketRemoteSupport(ticketId),
    enabled: Boolean(ticketId && remoteSupportApproved),
    refetchInterval: 5_000
  });

  useEffect(() => {
    const ticket = ticketQuery.data;
    if (ticket) {
      setAdminNote(ticket.adminNote ?? '');
      setFailureCategory(ticket.asTrainingLabel?.failureCategory ?? 'RECOMMENDATION_BUILD');
      setSeverity(ticket.asTrainingLabel?.severity ?? 'MEDIUM');
      setRelatedPartId(ticket.asTrainingLabel?.relatedPartId ?? '');
      setRecommendationId(ticket.asTrainingLabel?.recommendationId ?? '');
      setUseForRecommendationTraining(ticket.asTrainingLabel?.useForRecommendationTraining ?? true);
      setLabelNote(ticket.asTrainingLabel?.note ?? '');
    }
  }, [ticketQuery.data]);

  const reviewMutation = useMutation({
    mutationFn: (action: ReviewAction) => {
      if (action === 'ASSIGN_TO_ME') {
        return assignAdminTicketToMe(ticketId);
      }
      if (action === 'REQUEST_MORE_INFO') {
        return requestAdminTicketMoreInfo(ticketId, adminNote.trim());
      }
      return approveAdminTicketRemoteSupport(ticketId, adminNote.trim() || undefined);
    },
    onSuccess: (updatedTicket) => {
      queryClient.setQueryData(['admin-as-ticket', ticketId], updatedTicket);
      queryClient.invalidateQueries({ queryKey: ['admin-as-tickets'] });
      queryClient.invalidateQueries({ queryKey: ['admin-audit-logs-recent'] });
    }
  });

  const copyAccessCodeMutation = useMutation({
    mutationFn: async () => {
      const response = await getAdminTicketRemoteAccessCode(ticketId);
      if (!navigator.clipboard) {
        throw new Error('이 브라우저에서는 클립보드 복사를 사용할 수 없습니다.');
      }
      await navigator.clipboard.writeText(response.accessCode);
    },
    onSuccess: () => setRemoteSupportNotice('지원 코드를 클립보드에 복사했습니다.'),
    onError: (cause) => setRemoteSupportNotice(cause instanceof Error ? cause.message : '지원 코드를 복사하지 못했습니다.')
  });

  const remoteSupportMutation = useMutation({
    mutationFn: (action: RemoteSupportAction) => action === 'START'
      ? startAdminTicketRemoteSupport(ticketId)
      : completeAdminTicketRemoteSupport(ticketId),
    onSuccess: (state, action) => {
      queryClient.setQueryData(['admin-as-ticket-remote-support', ticketId], state);
      queryClient.invalidateQueries({ queryKey: ['admin-as-ticket', ticketId] });
      setRemoteSupportNotice(action === 'START' ? '원격 지원 시작을 기록했습니다.' : '원격 지원 완료와 코드 제거를 기록했습니다.');
    },
    onError: (cause) => setRemoteSupportNotice(cause instanceof Error ? cause.message : '원격지원 상태를 변경하지 못했습니다.')
  });

  const feedbackMutation = useMutation({
    mutationFn: () => createAsRecommendationFeedback(ticketId, {
      failureCategory,
      severity,
      relatedPartId: relatedPartId.trim() || undefined,
      relatedBuildId: relatedBuildId.trim() || undefined,
      recommendationId: recommendationId.trim() || undefined,
      category: feedbackCategory || undefined,
      useForRecommendationTraining,
      note: labelNote.trim() || undefined,
      reason: labelNote.trim() || '관리자가 AS 티켓을 추천 학습 피드백으로 확정'
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin-as-ticket', ticketId] });
      queryClient.invalidateQueries({ queryKey: ['admin-recommendation-training-overview'] });
    }
  });

  function runReviewAction(action: ReviewAction) {
    setLastAction(action);
    reviewMutation.mutate(action);
  }

  async function requestRemoteSupportFromChat() {
    setLastAction('APPROVE_REMOTE_SUPPORT');
    await reviewMutation.mutateAsync('APPROVE_REMOTE_SUPPORT');
  }

  function submitFeedback(event: FormEvent) {
    event.preventDefault();
    feedbackMutation.mutate();
  }

  const ticket = ticketQuery.data;

  if (ticketQuery.isLoading) {
    return (
      <AdminShell title="AS 티켓 상세">
        <StateMessage type="info" title="AS 티켓 로딩 중" body="관리자 AS 티켓 상세 정보를 불러오고 있습니다." />
      </AdminShell>
    );
  }

  if (ticketQuery.isError || !ticket) {
    return (
      <AdminShell title="AS 티켓 상세">
        <StateMessage type="warn" title="AS 티켓 조회 실패" body="티켓 상세 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요." />
      </AdminShell>
    );
  }

  const reviewCompleted = isReviewCompleted(ticket);
  const remoteApprovalAvailable = canApproveRemoteSupport(ticket);

  return (
    <AdminShell title="AS 티켓 상세">
      <div className="grid min-w-0 gap-5 min-[1000px]:grid-cols-[minmax(0,1fr)_420px]">
        <div data-testid="admin-as-ticket-overview" className="min-w-0 space-y-4">
          <TicketIssueSpotlight ticket={ticket} />
          <Panel title="접수 정보" subtitle={ticket.id}>
            <DataTable columns={['항목', '내용']} rows={receiptRows(ticket)} minWidth={0} nowrapColumns={['항목']} />
          </Panel>
          <Panel title="사용자 요청" subtitle="사용자가 접수한 증상과 요청 내용을 확인합니다.">
            <DataTable columns={['항목', '내용']} rows={userRequestRows(ticket)} minWidth={0} nowrapColumns={['항목']} />
          </Panel>
          <Panel title="Agent 진단" subtitle="PC Agent가 저장한 진단 결과를 그대로 표시합니다.">
            <DataTable columns={['항목', '내용']} rows={agentDiagnosisRows(ticket)} minWidth={0} nowrapColumns={['항목']} />
          </Panel>
          <Panel title="판단 근거" subtitle="수집된 근거와 진단 결과에 실제로 포함된 판단 자료입니다.">
            <DataTable columns={['항목', '내용']} rows={evidenceRows(ticket)} minWidth={0} nowrapColumns={['항목']} />
            <div className="mt-4 border-t border-slate-100 pt-4">
              <AgentLogSamplesToggle ticket={ticket} />
            </div>
          </Panel>
          <Link className="inline-block text-sm font-bold text-brand-blue" to="/admin/as-tickets">목록으로 돌아가기</Link>
        </div>

        <div className="min-w-0 space-y-4">
          <AdminTicketSupportChat
            ticketId={ticketId}
            remoteSupport={remoteSupportApproved || (!reviewCompleted && remoteApprovalAvailable) ? {
              status: remoteSupportQuery.data?.status,
              canRequest: remoteSupportApproved
                ? !['CODE_READY', 'IN_PROGRESS', 'COMPLETED'].includes(remoteSupportQuery.data?.status ?? '')
                : !reviewCompleted && remoteApprovalAvailable,
              isPending: reviewMutation.isPending,
              onRequest: requestRemoteSupportFromChat
            } : undefined}
          />
          <div>
            <Panel title={reviewCompleted ? '처리 결과' : '관리자 검토'} subtitle={reviewCompleted ? '완료된 검토 결과입니다.' : '필요한 업무 행동만 선택해 처리합니다.'}>
            {reviewCompleted ? (
              <DataTable columns={['항목', '내용']} rows={reviewResultRows(ticket)} nowrapColumns={['항목']} />
            ) : (
              <div className="space-y-4">
                <DataTable columns={['항목', '현재 값']} rows={reviewReadOnlyRows(ticket)} nowrapColumns={['항목']} />
                <div>
                  <label htmlFor="admin-ticket-note" className="mb-1 block text-xs font-bold text-slate-600">관리자 메모</label>
                  <textarea
                    id="admin-ticket-note"
                    className="h-28 w-full resize-y rounded border border-slate-300 p-3 text-sm"
                    placeholder="추가 정보 요청 시 요청 사유를 반드시 입력하세요."
                    value={adminNote}
                    onChange={(event) => setAdminNote(event.target.value)}
                  />
                </div>
                <div className="grid gap-2">
                  <button type="button" disabled={reviewMutation.isPending} onClick={() => runReviewAction('ASSIGN_TO_ME')} className="rounded border border-brand-blue px-4 py-3 text-sm font-bold text-brand-blue disabled:opacity-50">
                    내게 배정
                  </button>
                  <button type="button" disabled={reviewMutation.isPending || !adminNote.trim()} onClick={() => runReviewAction('REQUEST_MORE_INFO')} className="rounded border border-amber-300 bg-amber-50 px-4 py-3 text-sm font-bold text-amber-800 disabled:opacity-50">
                    추가 정보 요청
                  </button>
                  {remoteApprovalAvailable ? (
                    <button type="button" disabled={reviewMutation.isPending} onClick={() => runReviewAction('APPROVE_REMOTE_SUPPORT')} className="rounded bg-brand-blue px-4 py-3 text-sm font-bold text-white disabled:bg-slate-400">
                      원격 지원 승인
                    </button>
                  ) : (
                    <p className="rounded bg-slate-50 px-3 py-2 text-xs font-semibold leading-5 text-slate-500">지원 범위 밖 차단 사유가 있어 원격 지원으로 전환할 수 없습니다.</p>
                  )}
                </div>
                {reviewMutation.isSuccess ? <StateMessage type="success" title={reviewActionLabel(lastAction)} body="업무 상태가 일관되게 반영되었습니다." /> : null}
                {reviewMutation.isError ? <StateMessage type="warn" title="처리 실패" body={reviewMutation.error instanceof Error ? reviewMutation.error.message : '현재 상태에서는 요청한 업무를 처리할 수 없습니다.'} /> : null}
              </div>
            )}
            </Panel>
          </div>
          {remoteSupportApproved ? (
            <div id="admin-ticket-remote-support" className="scroll-mt-4">
              <AdminRemoteSupportPanel
                state={remoteSupportQuery.data}
                isLoading={remoteSupportQuery.isLoading}
                isError={remoteSupportQuery.isError}
                notice={remoteSupportNotice}
                isCopying={copyAccessCodeMutation.isPending}
                isUpdating={remoteSupportMutation.isPending}
                onCopy={() => copyAccessCodeMutation.mutate()}
                onStart={() => remoteSupportMutation.mutate('START')}
                onComplete={() => remoteSupportMutation.mutate('COMPLETE')}
              />
            </div>
          ) : null}
        </div>

        <div className="min-w-0 min-[1000px]:col-span-2">
        <Panel title="추천 학습 피드백" subtitle="관리자 확정 라벨입니다. 티켓 상태나 원인 후보를 자동 변경하지 않습니다.">
          <form onSubmit={submitFeedback} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label htmlFor="as-feedback-failure" className="mb-1 block text-xs font-bold text-slate-600">문제 분류</label>
                <select
                  id="as-feedback-failure"
                  className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                  value={failureCategory}
                  onChange={(event) => setFailureCategory(event.target.value)}
                >
                  {FAILURE_CATEGORY_OPTIONS.map((option) => <option key={option} value={option}>{failureCategoryLabel(option)}</option>)}
                </select>
              </div>
              <div>
                <label htmlFor="as-feedback-severity" className="mb-1 block text-xs font-bold text-slate-600">심각도</label>
                <select
                  id="as-feedback-severity"
                  className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                  value={severity}
                  onChange={(event) => setSeverity(event.target.value)}
                >
                  {SEVERITY_OPTIONS.map((option) => <option key={option} value={option}>{statusLabel(option)}</option>)}
                </select>
              </div>
            </div>
            <div>
              <label htmlFor="as-feedback-part" className="mb-1 block text-xs font-bold text-slate-600">연결 부품 public id</label>
              <input
                id="as-feedback-part"
                className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                placeholder="추천 실패와 직접 관련된 부품이 있을 때만 입력"
                value={relatedPartId}
                onChange={(event) => setRelatedPartId(event.target.value)}
              />
            </div>
            <div>
              <label htmlFor="as-feedback-build" className="mb-1 block text-xs font-bold text-slate-600">연결 견적 public id</label>
              <input
                id="as-feedback-build"
                className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                placeholder="추천 견적 전체 문제일 때 입력"
                value={relatedBuildId}
                onChange={(event) => setRelatedBuildId(event.target.value)}
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label htmlFor="as-feedback-rec" className="mb-1 block text-xs font-bold text-slate-600">recommendationId</label>
                <input
                  id="as-feedback-rec"
                  className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                  value={recommendationId}
                  onChange={(event) => setRecommendationId(event.target.value)}
                />
              </div>
              <div>
                <label htmlFor="as-feedback-category" className="mb-1 block text-xs font-bold text-slate-600">카테고리</label>
                <select
                  id="as-feedback-category"
                  className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                  value={feedbackCategory}
                  onChange={(event) => setFeedbackCategory(event.target.value)}
                >
                  {CATEGORY_OPTIONS.map((option) => <option key={option || 'none'} value={option}>{option || '미지정'}</option>)}
                </select>
              </div>
            </div>
            <label className="flex items-center gap-2 text-sm font-semibold text-slate-700">
              <input
                type="checkbox"
                checked={useForRecommendationTraining}
                onChange={(event) => setUseForRecommendationTraining(event.target.checked)}
              />
              추천 XGBoost 학습 후보로 사용
            </label>
            <div>
              <label htmlFor="as-feedback-note" className="mb-1 block text-xs font-bold text-slate-600">라벨 메모</label>
              <textarea
                id="as-feedback-note"
                className="h-24 w-full rounded border border-slate-300 p-3 text-sm"
                value={labelNote}
                onChange={(event) => setLabelNote(event.target.value)}
              />
            </div>
            <button disabled={feedbackMutation.isPending} className="w-full rounded bg-slate-950 px-4 py-3 text-sm font-bold text-white disabled:bg-slate-400">
              {feedbackMutation.isPending ? '피드백 저장 중' : 'AS 학습 피드백 저장'}
            </button>
            {feedbackMutation.isSuccess ? <StateMessage type="success" title="피드백 저장 완료" body="AS 라벨과 추천 학습 bridge 상태를 저장했습니다." /> : null}
            {feedbackMutation.isError ? <StateMessage type="warn" title="피드백 저장 실패" body="연결 부품/견적 ID 또는 라벨 값이 올바른지 확인해 주세요." /> : null}
          </form>
        </Panel>
        </div>
      </div>
    </AdminShell>
  );
}

function AdminRemoteSupportPanel({
  state,
  isLoading,
  isError,
  notice,
  isCopying,
  isUpdating,
  onCopy,
  onStart,
  onComplete
}: {
  state?: AdminRemoteSupportState;
  isLoading: boolean;
  isError: boolean;
  notice: string;
  isCopying: boolean;
  isUpdating: boolean;
  onCopy: () => void;
  onStart: () => void;
  onComplete: () => void;
}) {
  return (
    <Panel title="원격 지원 연결" subtitle="실제 연결과 화면 공유 승인은 Chrome Remote Desktop에서 수행합니다.">
      {isLoading ? <StateMessage type="info" title="연결 상태 확인 중" body="사용자의 지원 코드 등록 상태를 불러오고 있습니다." /> : null}
      {isError ? <StateMessage type="warn" title="연결 상태 조회 실패" body="담당 관리자 배정과 티켓 상태를 확인해 주세요." /> : null}
      {!isLoading && !isError && state?.status ? (
        <div data-testid="admin-remote-support-panel" className="space-y-3">
          <StatusBadge status={state.status} />
          <p className="text-sm font-bold text-slate-900">{adminRemoteSupportTitle(state.status)}</p>
          <p className="text-xs font-semibold leading-5 text-slate-600">{adminRemoteSupportDescription(state.status)}</p>
          {state.status === 'CODE_READY' && state.maskedAccessCode ? (
            <div className="rounded border border-slate-200 bg-slate-50 p-3">
              <p className="text-xs font-bold text-slate-500">마스킹된 지원 코드</p>
              <p className="mt-1 font-mono text-lg font-black tracking-wider text-slate-950">{state.maskedAccessCode}</p>
            </div>
          ) : null}
          <a
            href={CHROME_REMOTE_DESKTOP_SUPPORT_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex rounded border border-slate-300 bg-white px-3 py-2 text-xs font-bold text-brand-blue hover:bg-slate-50"
          >
            Chrome Remote Desktop 열기
          </a>
          {state.status === 'CODE_READY' ? (
            <div className="grid gap-2">
              <button className="rounded border border-brand-blue px-3 py-2 text-sm font-bold text-brand-blue disabled:opacity-50" disabled={isCopying || isUpdating} onClick={onCopy}>
                {isCopying ? '복사 중...' : '코드 복사'}
              </button>
              <button className="rounded bg-brand-blue px-3 py-2 text-sm font-bold text-white disabled:bg-slate-400" disabled={isCopying || isUpdating} onClick={onStart}>
                {isUpdating ? '처리 중...' : '지원 시작'}
              </button>
              <p className="text-[11px] font-semibold leading-4 text-slate-500">Chrome Remote Desktop에서 실제 연결을 확인한 뒤 지원 시작을 기록해 주세요.</p>
            </div>
          ) : null}
          {state.status === 'IN_PROGRESS' ? (
            <button className="w-full rounded bg-slate-950 px-3 py-2 text-sm font-bold text-white disabled:bg-slate-400" disabled={isUpdating} onClick={onComplete}>
              {isUpdating ? '완료 처리 중...' : '지원 완료'}
            </button>
          ) : null}
          {state.startedAt ? <p className="text-xs font-semibold text-slate-600">지원 시작: {formatSeoulDateTime(state.startedAt)}</p> : null}
          {state.completedAt ? <p className="text-xs font-semibold text-slate-600">지원 완료: {formatSeoulDateTime(state.completedAt)}</p> : null}
          {state.status === 'COMPLETED' ? <p className="text-xs font-bold text-emerald-700">저장된 일회용 지원 코드가 제거되었습니다.</p> : null}
          {notice ? <p role="status" className="rounded bg-slate-100 px-3 py-2 text-xs font-semibold text-slate-700">{notice}</p> : null}
        </div>
      ) : null}
    </Panel>
  );
}

function adminRemoteSupportTitle(status?: string | null) {
  if (status === 'CODE_READY') return '지원 코드 등록 완료';
  if (status === 'IN_PROGRESS') return '원격 지원 진행 중';
  if (status === 'COMPLETED') return '원격 지원 완료';
  return '지원 코드 등록 대기';
}

function adminRemoteSupportDescription(status?: string | null) {
  if (status === 'CODE_READY') return '코드를 복사한 뒤 Chrome Remote Desktop에서 연결을 시도해 주세요.';
  if (status === 'IN_PROGRESS') return 'Chrome Remote Desktop에서 진행 중인 지원을 마친 뒤 완료를 기록해 주세요.';
  if (status === 'COMPLETED') return '원격지원 업무가 완료됐으며 코드 복사와 시작 기능은 비활성화되었습니다.';
  return '사용자가 일회용 지원 코드를 등록하기를 기다리고 있습니다.';
}

function TicketIssueSpotlight({ ticket }: { ticket: AdminAsTicket }) {
  const headline = ticket.title ?? ticket.diagnosisTitle ?? firstLine(ticket.symptom);
  const diagnosis = ticket.diagnosisSummary ?? ticket.description ?? ticket.detailDescription ?? ticket.symptom;
  return (
    <section data-testid="admin-ticket-issue-spotlight" className="overflow-hidden rounded-lg border border-slate-800 bg-slate-950 p-5 text-white shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-xs font-black uppercase tracking-[0.18em] text-blue-300">문제 핵심</p>
          <h2 className="mt-2 break-words text-xl font-black leading-8 sm:text-2xl">{headline}</h2>
        </div>
        {ticket.riskLevel ? <StatusBadge status={ticket.riskLevel} /> : null}
      </div>
      <p className="mt-3 break-words text-sm font-semibold leading-6 text-slate-200">{diagnosis}</p>
      <div className="mt-4 grid gap-2 sm:grid-cols-2">
        <div className="rounded-md border border-white/10 bg-white/5 p-3">
          <p className="text-[11px] font-black uppercase tracking-wide text-slate-400">추정 원인</p>
          <p className="mt-1 break-words text-sm font-extrabold leading-6 text-white">{primaryCauseLabel(ticket)}</p>
        </div>
        <div className="rounded-md border border-blue-400/30 bg-blue-400/10 p-3">
          <p className="text-[11px] font-black uppercase tracking-wide text-blue-200">권장 처리</p>
          <p className="mt-1 text-sm font-extrabold leading-6 text-white">{recommendedSupportLabel(ticket)}</p>
        </div>
      </div>
    </section>
  );
}

function receiptRows(ticket: AdminAsTicket) {
  return [
    { '항목': '상태', '내용': <StatusBadge status={ticket.status} /> },
    { '항목': '접수 시각', '내용': formatDateTime(ticket.createdAt) },
    { '항목': '사용자', '내용': ticket.userEmail ?? ticket.userName ?? ticket.userId ?? '-' },
    { '항목': '담당자', '내용': ticket.assignedAdminId ? shortId(ticket.assignedAdminId) : '미배정' }
  ];
}

function userRequestRows(ticket: AdminAsTicket) {
  return [
    { '항목': '제목 또는 증상', '내용': ticket.title ?? ticket.diagnosisTitle ?? firstLine(ticket.symptom) },
    { '항목': '상세 설명', '내용': ticket.description ?? ticket.detailDescription ?? ticket.symptom },
    { '항목': '요청한 지원 방식', '내용': requestTypeLabel(ticket) }
  ];
}

function agentDiagnosisRows(ticket: AdminAsTicket) {
  return [
    { '항목': '진단 결과', '내용': ticket.diagnosisSummary ?? logSummary(ticket) },
    { '항목': '권장 처리', '내용': recommendedSupportLabel(ticket) },
    { '항목': '위험도', '내용': ticket.riskLevel ? <StatusBadge status={ticket.riskLevel} /> : '-' },
    { '항목': '분석 상태', '내용': ticket.analysisStatus ? <StatusBadge status={ticket.analysisStatus} /> : '-' },
    { '항목': '진단 시각', '내용': formatDateTime(ticket.diagnosedAt) },
    { '항목': '문제 발생 구간', '내용': incidentWindowSummary(ticket) }
  ];
}

function evidenceRows(ticket: AdminAsTicket) {
  const diagnosisResult = objectValue(ticket.diagnosisResult);
  const routing = objectValue(ticket.supportRouting);
  return [
    { '항목': 'evidence', '내용': structuredList(ticket.diagnosisEvidence ?? valueList(diagnosisResult?.evidence)) },
    { '항목': 'suspected causes', '내용': structuredList(valueList(diagnosisResult?.suspectedCauses).length > 0 ? valueList(diagnosisResult?.suspectedCauses) : ticket.causeCandidates) },
    { '항목': 'recommended actions', '내용': structuredList(valueList(diagnosisResult?.recommendedActions).length > 0 ? valueList(diagnosisResult?.recommendedActions) : valueList(routing?.recommendedActions)) },
    { '항목': 'unsupported checks', '내용': structuredList(valueList(diagnosisResult?.unsupportedChecks)) }
  ];
}

function reviewReadOnlyRows(ticket: AdminAsTicket) {
  return [
    { '항목': 'Agent 권장 처리', '현재 값': recommendedSupportLabel(ticket) },
    { '항목': '사용자 요청 지원', '현재 값': requestTypeLabel(ticket) },
    { '항목': '위험도', '현재 값': ticket.riskLevel ? <StatusBadge status={ticket.riskLevel} /> : '-' },
    { '항목': '검토 상태', '현재 값': ticket.reviewStatus ? <StatusBadge status={ticket.reviewStatus} /> : '-' },
    { '항목': '담당자', '현재 값': ticket.assignedAdminId ? shortId(ticket.assignedAdminId) : '미배정' }
  ];
}

function reviewResultRows(ticket: AdminAsTicket) {
  return [
    { '항목': '처리 결정', '내용': ticket.supportDecision ? <StatusBadge status={ticket.supportDecision} /> : '-' },
    { '항목': '검토 결과', '내용': ticket.reviewStatus ? <StatusBadge status={ticket.reviewStatus} /> : '-' },
    { '항목': '담당 관리자', '내용': ticket.assignedAdminId ? shortId(ticket.assignedAdminId) : '미배정' },
    { '항목': '처리 시각', '내용': formatDateTime(ticket.reviewedAt ?? ticket.resolvedAt ?? ticket.updatedAt) },
    { '항목': '관리자 메모', '내용': ticket.adminNote ?? '-' },
    { '항목': '현재 처리 상태', '내용': <StatusBadge status={ticket.status} /> }
  ];
}

function AgentLogSamplesToggle({ ticket }: { ticket: AdminAsTicket }) {
  const [expanded, setExpanded] = useState(false);
  const entries = agentLogEntries(ticket);

  if (entries.length === 0) {
    return (
      <span className="text-sm text-slate-500">
        {ticket.logUploadId
          ? '업로드된 로그가 있으나 표시 가능한 샘플이 없습니다.'
          : '연결된 에이전트 로그 또는 진단 근거가 없습니다.'}
      </span>
    );
  }

  const panelId = `agent-log-samples-${ticket.id}`;
  return (
    <div className="min-w-0 max-w-full">
      <button
        type="button"
        aria-expanded={expanded}
        aria-controls={panelId}
        onClick={() => setExpanded((current) => !current)}
        className="inline-flex items-center gap-1.5 rounded border border-brand-blue px-3 py-2 text-xs font-black text-brand-blue transition hover:bg-blue-50 focus:outline-none focus:ring-2 focus:ring-blue-200"
      >
        {expanded ? '에이전트 데이터 닫기' : `에이전트 데이터 보기 (${entries.length}건)`}
        <ChevronDown aria-hidden="true" className={`h-4 w-4 transition-transform ${expanded ? 'rotate-180' : ''}`} />
      </button>

      {expanded ? (
        <div id={panelId} data-testid="agent-log-samples-panel" className="mt-3 max-h-[360px] max-w-full overflow-auto rounded border border-slate-700 bg-slate-950 p-3 text-slate-100">
          <p className="mb-3 text-xs font-semibold leading-5 text-slate-300">
            {agentLogEntriesNotice(entries)}
          </p>
          <div className="space-y-3">
            {entries.map((entry, index) => (
              <article key={agentLogEntryKey(entry, index)} className="min-w-max rounded border border-slate-700 bg-slate-900 p-3">
                <div className="text-xs font-black text-blue-200">{agentLogEntryHeader(entry)}</div>
                <pre className="mt-2 whitespace-pre text-xs leading-5 text-slate-100">{agentLogEntryBody(entry)}</pre>
              </article>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}

type AgentLogEntry = {
  source: 'UPLOADED_LOG' | 'LIVE_DIAGNOSIS';
  data: Record<string, unknown>;
};

function agentLogEntries(ticket: AdminAsTicket): AgentLogEntry[] {
  const summary = objectValue(ticket.logSummary);
  const rawSamples = summary?.rawSamples;
  const uploadedEntries = Array.isArray(rawSamples) ? rawSamples
    .map(objectValue)
    .filter((sample): sample is Record<string, unknown> => sample !== null)
    .map((data): AgentLogEntry => ({ source: 'UPLOADED_LOG', data })) : [];
  const diagnosisEntries = Array.isArray(ticket.diagnosisEvidence) ? ticket.diagnosisEvidence
    .map(objectValue)
    .filter((sample): sample is Record<string, unknown> => sample !== null)
    .map((data): AgentLogEntry => ({ source: 'LIVE_DIAGNOSIS', data })) : [];
  return [...uploadedEntries, ...diagnosisEntries].slice(0, 20);
}

function agentLogEntriesNotice(entries: AgentLogEntry[]) {
  const hasUploadedLog = entries.some((entry) => entry.source === 'UPLOADED_LOG');
  const hasLiveDiagnosis = entries.some((entry) => entry.source === 'LIVE_DIAGNOSIS');
  if (hasUploadedLog && hasLiveDiagnosis) {
    return '개인정보가 마스킹된 업로드 로그 샘플과 서버가 검증한 PC Agent 실시간 진단 근거이며 최대 20건까지 표시됩니다.';
  }
  if (hasLiveDiagnosis) {
    return 'PC Agent 실시간 진단에서 서버가 검증해 티켓에 저장한 측정 근거입니다. 원본 JSONL 업로드와는 별도입니다.';
  }
  return '개인정보가 마스킹된 핵심 업로드 로그 샘플이며 최대 20건까지 표시됩니다.';
}

function agentLogEntryKey(entry: AgentLogEntry, index: number) {
  const sample = entry.data;
  return `${entry.source}-${textValue(sample.refId) ?? textValue(sample.sampleId) ?? textValue(sample.taskId) ?? `${textValue(sample.sequence) ?? 'sample'}-${index}`}`;
}

function agentLogEntryHeader(entry: AgentLogEntry) {
  const sample = entry.data;
  if (entry.source === 'LIVE_DIAGNOSIS') {
    return [
      textValue(sample.sampledAt) ? formatDateTime(textValue(sample.sampledAt)!) : '수집 시각 미상',
      '실시간 진단 근거',
      [textValue(sample.component), textValue(sample.metricType)].filter(Boolean).join(' · ') || '측정 항목 미상'
    ].join(' · ');
  }
  const legacyPayload = parsedAgentLogSampleText(sample);
  const collectedAt = textValue(sample.collectedAt)
    ?? textValue(legacyPayload?.collectedAt)
    ?? textValue(legacyPayload?.capturedAt);
  const sequence = textValue(sample.sequence) ?? textValue(legacyPayload?.sequence);
  return [
    collectedAt ? formatDateTime(collectedAt) : '수집 시각 미상',
    textValue(sample.kind) ?? textValue(legacyPayload?.kind) ?? textValue(legacyPayload?.event) ?? '종류 미상',
    sequence ? `#${sequence}` : '순번 미상'
  ].join(' · ');
}

function agentLogEntryBody(entry: AgentLogEntry) {
  const sample = entry.data;
  if (entry.source === 'LIVE_DIAGNOSIS') {
    return prettyJson(sample);
  }
  if (sample.payload !== undefined) {
    return prettyJson(sample.payload);
  }
  return prettyJson(parsedAgentLogSampleText(sample) ?? sample);
}

function parsedAgentLogSampleText(sample: Record<string, unknown>) {
  const text = textValue(sample.text);
  if (!text) {
    return null;
  }
  try {
    return objectValue(JSON.parse(text));
  } catch {
    return null;
  }
}

function prettyJson(value: unknown) {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function logSummary(ticket: AdminAsTicket) {
  if (ticket.logSummaryText) {
    return ticket.logSummaryText;
  }
  if (ticket.logSummary) {
    if (typeof ticket.logSummary === 'string') {
      return ticket.logSummary;
    }
    const summaryText = textValue(ticket.logSummary.summaryText);
    if (summaryText) {
      return summaryText;
    }
    const correlations = ticket.logSummary.correlations;
    if (Array.isArray(correlations)) {
      const summaries = correlations
        .map((item) => textValue(objectValue(item)?.summary))
        .filter(Boolean);
      if (summaries.length > 0) {
        return summaries.join(' / ');
      }
    }
    return compactJson(ticket.logSummary);
  }
  const diagnosisMessages = (ticket.diagnosisEvents ?? [])
    .map((event) => event.message?.trim())
    .filter((message): message is string => Boolean(message));
  if (diagnosisMessages.length > 0) {
    return diagnosisMessages.join(' / ');
  }
  const diagnosisEvidenceCount = Array.isArray(ticket.diagnosisEvidence)
    ? ticket.diagnosisEvidence.length
    : 0;
  if (diagnosisEvidenceCount > 0) {
    return `PC Agent 실시간 진단 근거 ${diagnosisEvidenceCount}건 연결됨`;
  }
  return ticket.logUploadId ? `업로드된 로그 있음: ${shortId(ticket.logUploadId)}` : '연결된 로그 없음';
}

function recommendedSupportLabel(ticket: AdminAsTicket) {
  const resolutionType = textValue(objectValue(ticket.diagnosisResult)?.resolutionType);
  if (resolutionType) {
    return supportServiceLabel(resolutionType);
  }
  const routing = objectValue(ticket.supportRouting);
  const explicitLabel = textValue(routing?.recommendedServiceLabel);
  if (explicitLabel) {
    return explicitLabel;
  }
  const service = textValue(routing?.recommendedService);
  if (service) {
    return supportServiceLabel(service);
  }
  return serviceLabelForDecision(ticket.supportDecision);
}

function supportServiceLabel(service: string) {
  switch (service) {
    case 'REMOTE_SUPPORT':
      return '원격 지원';
    case 'VISIT_SUPPORT':
    case 'PHYSICAL_INSPECTION':
      return '방문 점검';
    case 'DIAGNOSIS_ONLY':
      return '진단 결과 확인';
    case 'SELF_SERVICE':
    case 'SELF_SOLVABLE':
      return '사용자 자가 조치';
    case 'MONITOR_ONLY':
      return '경과 관찰';
    case 'UNKNOWN':
      return '권장 처리 미확정';
    default:
      return statusLabel(service);
  }
}

function serviceLabelForDecision(decision?: string | null) {
  switch (decision) {
    case 'REMOTE_POSSIBLE':
      return '원격지원 신청';
    case 'VISIT_REQUIRED':
    case 'REPAIR_OR_REPLACE':
      return '방문지원 신청';
    case 'UNSUPPORTED':
      return '지원 범위 밖';
    default:
      return '우선 진단만 받기';
  }
}

function requestTypeLabel(ticket: AdminAsTicket) {
  if (ticket.requestType) {
    return supportServiceLabel(ticket.requestType);
  }
  const routing = objectValue(ticket.supportRouting);
  const requestedService = textValue(routing?.requestedService) ?? textValue(routing?.recommendedService);
  return requestedService ? supportServiceLabel(requestedService) : '지원 방식 미지정';
}

function isReviewCompleted(ticket: AdminAsTicket) {
  return ['APPROVED', 'REJECTED'].includes(ticket.reviewStatus ?? '')
    || ['RESOLVED', 'CLOSED', 'CANCELLED'].includes(ticket.status);
}

function canApproveRemoteSupport(ticket: AdminAsTicket) {
  const routing = objectValue(ticket.supportRouting);
  const blockingFactors = valueList(routing?.blockingFactors)
    .map(textValue)
    .filter((value): value is string => Boolean(value));
  return !blockingFactors.some((factor) => [
    'OUT_OF_SCOPE',
    'UNSUPPORTED_SCOPE',
    'OUT_OF_PC_SCOPE',
    'DATA_RECOVERY_REQUIRED',
    'UNSUPPORTED_SOFTWARE',
    'PHYSICAL_DAMAGE_POLICY_REQUIRED'
  ].includes(factor));
}

function primaryCauseLabel(ticket: AdminAsTicket) {
  const diagnosisResult = objectValue(ticket.diagnosisResult);
  const candidates = valueList(diagnosisResult?.suspectedCauses).length > 0
    ? valueList(diagnosisResult?.suspectedCauses)
    : ticket.causeCandidates;
  const first = candidates[0];
  const firstObject = objectValue(first);
  return textValue(first)
    ?? textValue(firstObject?.label)
    ?? textValue(firstObject?.summary)
    ?? textValue(firstObject?.reason)
    ?? textValue(firstObject?.name)
    ?? textValue(firstObject?.code)
    ?? (first === undefined ? '진단된 원인 후보 없음' : compactValue(first));
}

function reviewActionLabel(action: ReviewAction | null) {
  switch (action) {
    case 'ASSIGN_TO_ME':
      return '담당자 배정 완료';
    case 'REQUEST_MORE_INFO':
      return '추가 정보 요청 완료';
    case 'APPROVE_REMOTE_SUPPORT':
      return '원격 지원 승인 완료';
    default:
      return '처리 완료';
  }
}

function valueList(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function structuredList(items: unknown[]) {
  if (items.length === 0) {
    return '-';
  }
  return (
    <ul className="min-w-0 space-y-2" data-testid="structured-evidence-list">
      {items.map((item, index) => {
        const formatted = structuredJson(item);
        return (
          <li key={`${textValue(item) ?? compactValue(item)}-${index}`} className="min-w-0">
            {formatted ? (
              <pre className="max-h-56 max-w-full overflow-auto rounded-md border border-slate-700 bg-slate-950 p-3 text-xs leading-5 text-slate-100"><code>{formatted}</code></pre>
            ) : (
              <p className="break-words text-sm leading-6 text-slate-700">
                <span aria-hidden="true" className="mr-2 text-slate-400">•</span>
                {textValue(item) ?? compactValue(item)}
              </p>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function structuredJson(value: unknown) {
  if (value && typeof value === 'object') {
    return prettyJson(value);
  }
  if (typeof value !== 'string') {
    return null;
  }
  const candidate = value.trim();
  if (!(candidate.startsWith('{') || candidate.startsWith('['))) {
    return null;
  }
  try {
    return prettyJson(JSON.parse(candidate));
  } catch {
    return null;
  }
}

function compactValue(value: unknown) {
  if (value && typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value ?? '-');
}

function failureCategoryLabel(value: string) {
  const labels: Record<string, string> = {
    RECOMMENDATION_BUILD: '추천 견적',
    PART_SELECTION: '부품 선택',
    COMPATIBILITY: '호환성',
    PERFORMANCE: '성능',
    USER_ENVIRONMENT: '사용자 환경',
    AGENT_LOG_ONLY: 'Agent 로그만',
    OTHER: '기타'
  };
  return labels[value] ?? value;
}

function incidentWindowSummary(ticket: AdminAsTicket) {
  const logSummaryValue = objectValue(ticket.logSummary);
  const window = objectValue(ticket.incidentWindow) ?? objectValue(logSummaryValue?.incidentWindow);
  if (!window) {
    return '-';
  }
  const startedAt = textValue(window.startedAt) ?? textValue(window.rangeStartedAt);
  const endedAt = textValue(window.endedAt) ?? textValue(window.rangeEndedAt);
  const symptomType = textValue(window.symptomType);
  const range = startedAt || endedAt ? `${formatDateTime(startedAt)} ~ ${formatDateTime(endedAt)}` : '-';
  return symptomType ? `${range} / ${symptomType}` : range;
}

function firstLine(value: string) {
  return value.split('\n').find((line) => line.trim())?.trim() ?? value;
}

function objectValue(value: unknown) {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return null;
}

function textValue(value: unknown) {
  if (typeof value === 'string') {
    return value.trim() || null;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  return null;
}

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}

function formatDateTime(value?: string | null) {
  return formatSeoulDateTime(value);
}

function compactJson(value?: Record<string, unknown> | null) {
  if (!value || Object.keys(value).length === 0) {
    return '-';
  }
  return JSON.stringify(value);
}
