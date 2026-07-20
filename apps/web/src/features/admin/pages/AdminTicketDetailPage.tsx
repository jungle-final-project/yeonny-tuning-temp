import { FormEvent, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { formatSeoulDateTime } from '../../../lib/dateTime';
import { createAsRecommendationFeedback, getAdminTicket, updateAdminTicket } from '../adminApi';
import type { AdminAsTicket, AsTicketStatus } from '../adminApi';

const STATUS_OPTIONS: AsTicketStatus[] = ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED'];
const REVIEW_OPTIONS = ['', 'NOT_REQUIRED', 'REQUIRED', 'IN_REVIEW', 'APPROVED', 'REJECTED'];
const SUPPORT_DECISION_OPTIONS = [
  '',
  'SELF_SOLVABLE',
  'REMOTE_POSSIBLE',
  'VISIT_REQUIRED',
  'REPAIR_OR_REPLACE',
  'NEEDS_MORE_INFO',
  'MONITOR_ONLY',
  'UNSUPPORTED'
];
const RISK_OPTIONS = ['', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const DIAGNOSTIC_ACCURACY_OPTIONS = ['', 'ACCURATE', 'PARTIAL', 'MISSED', 'UNKNOWN'];
const FAILURE_CATEGORY_OPTIONS = ['RECOMMENDATION_BUILD', 'PART_SELECTION', 'COMPATIBILITY', 'PERFORMANCE', 'USER_ENVIRONMENT', 'AGENT_LOG_ONLY', 'OTHER'];
const SEVERITY_OPTIONS = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const CATEGORY_OPTIONS = ['', 'CPU', 'GPU', 'RAM', 'MOTHERBOARD', 'STORAGE', 'PSU', 'CASE', 'COOLER'];

export function AdminTicketDetailPage() {
  const { ticketId = '' } = useParams();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<AsTicketStatus>('OPEN');
  const [assignedAdminId, setAssignedAdminId] = useState('');
  const [adminNote, setAdminNote] = useState('');
  const [reviewStatus, setReviewStatus] = useState('');
  const [supportDecision, setSupportDecision] = useState('');
  const [riskLevel, setRiskLevel] = useState('');
  const [diagnosticAccuracy, setDiagnosticAccuracy] = useState('');
  const [remoteSupportLink, setRemoteSupportLink] = useState('');
  const [autoResponseAllowed, setAutoResponseAllowed] = useState(false);
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

  useEffect(() => {
    const ticket = ticketQuery.data;
    if (ticket) {
      setStatus(ticket.status);
      setAssignedAdminId(ticket.assignedAdminId ?? '');
      setAdminNote(ticket.adminNote ?? '');
      setReviewStatus(ticket.reviewStatus ?? '');
      setSupportDecision(ticket.supportDecision ?? '');
      setRiskLevel(ticket.riskLevel ?? '');
      setDiagnosticAccuracy(ticket.diagnosticAccuracy ?? '');
      setRemoteSupportLink(ticket.remoteSupportLink ?? '');
      setAutoResponseAllowed(Boolean(ticket.autoResponseAllowed));
      setFailureCategory(ticket.asTrainingLabel?.failureCategory ?? 'RECOMMENDATION_BUILD');
      setSeverity(ticket.asTrainingLabel?.severity ?? 'MEDIUM');
      setRelatedPartId(ticket.asTrainingLabel?.relatedPartId ?? '');
      setRecommendationId(ticket.asTrainingLabel?.recommendationId ?? '');
      setUseForRecommendationTraining(ticket.asTrainingLabel?.useForRecommendationTraining ?? true);
      setLabelNote(ticket.asTrainingLabel?.note ?? '');
    }
  }, [ticketQuery.data]);

  const updateMutation = useMutation({
    mutationFn: () => updateAdminTicket(ticketId, {
      status,
      assignedAdminId: assignedAdminId.trim() || undefined,
      adminNote: adminNote.trim() || undefined,
      reviewStatus: reviewStatus || undefined,
      supportDecision: supportDecision || undefined,
      riskLevel: riskLevel || undefined,
      diagnosticAccuracy: diagnosticAccuracy || undefined,
      remoteSupportLink: remoteSupportLink.trim() || undefined,
      autoResponseAllowed
    }),
    onSuccess: (updatedTicket) => {
      queryClient.setQueryData(['admin-as-ticket', ticketId], updatedTicket);
      queryClient.invalidateQueries({ queryKey: ['admin-as-tickets'] });
      queryClient.invalidateQueries({ queryKey: ['admin-audit-logs-recent'] });
    }
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

  function submit(event: FormEvent) {
    event.preventDefault();
    updateMutation.mutate();
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

  return (
    <AdminShell title="AS 티켓 상세">
      <div className="grid grid-cols-[minmax(0,1fr)_420px] gap-5">
        <Panel title="AS 티켓 확인" subtitle={ticket.id}>
          <DataTable columns={['항목', '내용']} rows={ticketDetailRows(ticket)} />
          <Link className="mt-5 inline-block text-sm font-bold text-brand-blue" to="/admin/as-tickets">목록으로 돌아가기</Link>
        </Panel>

        <Panel title="지원 결정 저장" subtitle="처리 상태, 담당자, 검토 상태, 지원 결정을 저장합니다.">
          <form onSubmit={submit} className="space-y-4">
            <div>
              <label htmlFor="admin-ticket-status" className="mb-1 block text-xs font-bold text-slate-600">상태</label>
              <select
                id="admin-ticket-status"
                className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                value={status}
                onChange={(event) => setStatus(event.target.value as AsTicketStatus)}
              >
                {STATUS_OPTIONS.map((option) => <option key={option} value={option}>{option}</option>)}
              </select>
            </div>

            <div>
              <label htmlFor="admin-ticket-assignee" className="mb-1 block text-xs font-bold text-slate-600">담당자 public id</label>
              <input
                id="admin-ticket-assignee"
                className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                placeholder="비워두면 현재 관리자"
                value={assignedAdminId}
                onChange={(event) => setAssignedAdminId(event.target.value)}
              />
            </div>

            <div>
              <label htmlFor="admin-ticket-review-status" className="mb-1 block text-xs font-bold text-slate-600">검토 상태</label>
              <select
                id="admin-ticket-review-status"
                className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                value={reviewStatus}
                onChange={(event) => setReviewStatus(event.target.value)}
              >
                {REVIEW_OPTIONS.map((option) => <option key={option || 'keep-review'} value={option}>{option || '기존 값 유지'}</option>)}
              </select>
            </div>

            <div>
              <label htmlFor="admin-ticket-support-decision" className="mb-1 block text-xs font-bold text-slate-600">지원 결정</label>
              <select
                id="admin-ticket-support-decision"
                className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                value={supportDecision}
                onChange={(event) => setSupportDecision(event.target.value)}
              >
                {SUPPORT_DECISION_OPTIONS.map((option) => <option key={option || 'keep-decision'} value={option}>{option || '기존 값 유지'}</option>)}
              </select>
            </div>

            <div>
              <label htmlFor="admin-ticket-risk-level" className="mb-1 block text-xs font-bold text-slate-600">위험도</label>
              <select
                id="admin-ticket-risk-level"
                className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                value={riskLevel}
                onChange={(event) => setRiskLevel(event.target.value)}
              >
                {RISK_OPTIONS.map((option) => <option key={option || 'keep-risk'} value={option}>{option || '기존 값 유지'}</option>)}
              </select>
            </div>

            <div>
              <label htmlFor="admin-ticket-diagnostic-accuracy" className="mb-1 block text-xs font-bold text-slate-600">진단 적중 여부</label>
              <select
                id="admin-ticket-diagnostic-accuracy"
                className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                value={diagnosticAccuracy}
                onChange={(event) => setDiagnosticAccuracy(event.target.value)}
              >
                {DIAGNOSTIC_ACCURACY_OPTIONS.map((option) => <option key={option || 'keep-accuracy'} value={option}>{option || '기존 값 유지'}</option>)}
              </select>
            </div>

            <div>
              <label htmlFor="admin-ticket-remote-link" className="mb-1 block text-xs font-bold text-slate-600">원격지원 링크</label>
              <input
                id="admin-ticket-remote-link"
                className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                placeholder="Quick Assist 또는 원격지원 안내 링크"
                value={remoteSupportLink}
                onChange={(event) => setRemoteSupportLink(event.target.value)}
              />
            </div>

            <label className="flex items-center gap-2 text-sm font-semibold text-slate-700">
              <input type="checkbox" checked={autoResponseAllowed} onChange={(event) => setAutoResponseAllowed(event.target.checked)} />
              자동 응답 허용
            </label>

            <div>
              <label htmlFor="admin-ticket-note" className="mb-1 block text-xs font-bold text-slate-600">관리자 메모</label>
              <textarea
                id="admin-ticket-note"
                className="h-28 w-full rounded border border-slate-300 p-3 text-sm"
                value={adminNote}
                onChange={(event) => setAdminNote(event.target.value)}
              />
            </div>

            <div className="grid grid-cols-2 gap-3">
              <button
                type="button"
                disabled={updateMutation.isPending}
                onClick={() => { setStatus('ASSIGNED'); setAssignedAdminId(''); }}
                className="rounded border border-brand-blue px-4 py-3 text-sm font-bold text-brand-blue disabled:opacity-50"
              >
                담당자 배정
              </button>
              <button disabled={updateMutation.isPending} className="rounded bg-brand-blue px-4 py-3 text-sm font-bold text-white disabled:bg-slate-400">
                {updateMutation.isPending ? '결정 저장 중' : '결정 저장'}
              </button>
            </div>
            {updateMutation.isSuccess ? <StateMessage type="success" title="결정 저장 완료" body="AS 티켓 상태와 관리자 조치 내용을 저장했습니다." /> : null}
            {updateMutation.isError ? <StateMessage type="warn" title="저장 실패" body="허용되지 않는 상태 전이이거나 담당자 ID가 유효하지 않습니다." /> : null}
          </form>
        </Panel>

        <Panel title="로그 요약" subtitle="raw 로그가 아니라 서버가 만든 학습용 요약 피처입니다.">
          <DataTable columns={['항목', '내용']} rows={logSummaryRows(ticket)} />
        </Panel>

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
                  {FAILURE_CATEGORY_OPTIONS.map((option) => <option key={option} value={option}>{option}</option>)}
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
                  {SEVERITY_OPTIONS.map((option) => <option key={option} value={option}>{option}</option>)}
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
    </AdminShell>
  );
}

function ticketDetailRows(ticket: AdminAsTicket) {
  return [
    { '항목': '상태', '내용': <StatusBadge status={ticket.status} /> },
    { '항목': '분석 상태', '내용': ticket.analysisStatus ?? '-' },
    { '항목': '검토 상태', '내용': ticket.reviewStatus ? <StatusBadge status={ticket.reviewStatus} /> : '-' },
    { '항목': '지원 결정', '내용': ticket.supportDecision ? <StatusBadge status={ticket.supportDecision} /> : '-' },
    { '항목': '추천 서비스', '내용': recommendedSupportLabel(ticket) },
    { '항목': '추천 신뢰도', '내용': routingConfidence(ticket) },
    { '항목': '위험도', '내용': ticket.riskLevel ?? '-' },
    { '항목': '진단 적중 여부', '내용': ticket.diagnosticAccuracy ?? '-' },
    { '항목': '자동 응답', '내용': ticket.autoResponseAllowed ? '허용' : '차단' },
    { '항목': '제목/증상', '내용': ticket.title ?? firstLine(ticket.symptom) },
    { '항목': '상세 설명', '내용': ticket.description ?? ticket.detailDescription ?? ticket.symptom },
    { '항목': '사용자', '내용': ticket.userEmail ?? ticket.userName ?? ticket.userId ?? '-' },
    { '항목': '문제 발생 구간', '내용': incidentWindowSummary(ticket) },
    { '항목': '로그', '내용': logSummary(ticket) },
    { '항목': '추천 근거 코드', '내용': routingListText(ticket, 'reasonCodes') },
    { '항목': '원격 조치 후보', '내용': routingListText(ticket, 'remoteActions') },
    { '항목': '방문 판단 근거', '내용': routingListText(ticket, 'visitReasons') },
    { '항목': '차단 요인', '내용': routingListText(ticket, 'blockingFactors') },
    { '항목': '원인 후보', '내용': formatCandidates(ticket.causeCandidates) },
    { '항목': '업그레이드 후보', '내용': formatCandidates(ticket.upgradeCandidates) },
    { '항목': '원격지원', '내용': remoteSupport(ticket) },
    { '항목': '방문지원', '내용': visitSupport(ticket) },
    { '항목': '담당자', '내용': ticket.assignedAdminId ?? '미배정' },
    { '항목': '관리자 메모', '내용': ticket.adminNote ?? '-' },
    { '항목': '생성일', '내용': formatDateTime(ticket.createdAt) },
    { '항목': '해결일', '내용': formatDateTime(ticket.resolvedAt) }
  ];
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
  return ticket.logUploadId ? `업로드된 로그 있음: ${shortId(ticket.logUploadId)}` : '연결된 로그 없음';
}

function logSummaryRows(ticket: AdminAsTicket) {
  return [
    { '항목': '요약 ID', '내용': ticket.logSummaryId ?? '-' },
    { '항목': '요약', '내용': logSummary(ticket) },
    { '항목': '핵심 요약', '내용': compactJson(ticket.logSummaryPayload) },
    { '항목': '학습 피처', '내용': compactJson(ticket.logFeaturePayload) },
    { '항목': '위험 플래그', '내용': compactJson(ticket.logRiskFlags) },
    { '항목': '지원 라우팅', '내용': compactJson(ticket.supportRouting) },
    { '항목': 'AI 진단 요청', '내용': compactJson(ticket.aiDiagnosisRequest) },
    { '항목': '현재 라벨', '내용': ticket.asTrainingLabel ? compactJson(ticket.asTrainingLabel) : '-' }
  ];
}

function recommendedSupportLabel(ticket: AdminAsTicket) {
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
      return '원격지원 신청';
    case 'VISIT_SUPPORT':
      return '방문지원 신청';
    case 'DIAGNOSIS_ONLY':
      return '우선 진단만 받기';
    default:
      return service;
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

function routingConfidence(ticket: AdminAsTicket) {
  const confidence = textValue(objectValue(ticket.supportRouting)?.confidence);
  return confidence ? <StatusBadge status={confidence} /> : '-';
}

function routingListText(ticket: AdminAsTicket, key: string) {
  const value = objectValue(ticket.supportRouting)?.[key];
  if (Array.isArray(value)) {
    const items = value.map((item) => textValue(item)).filter(Boolean);
    return items.length > 0 ? items.join(' / ') : '-';
  }
  return textValue(value) || '-';
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

function remoteSupport(ticket: AdminAsTicket) {
  if (!ticket.remoteSupportLink && !ticket.remoteSupportStatus) {
    return '-';
  }
  return `${ticket.remoteSupportStatus ?? 'LINK_SENT'} ${ticket.remoteSupportLink ?? ''}`.trim();
}

function visitSupport(ticket: AdminAsTicket) {
  if (!ticket.visitSupportRequired) {
    return '-';
  }
  return `${ticket.visitSupportStatus ?? 'REQUESTED'} ${ticket.visitPreferredDate ?? ''} ${visitSlotLabel(ticket.visitTimeSlot)}`.trim();
}

function formatCandidates(candidates: Record<string, unknown>[]) {
  if (!candidates.length) {
    return '-';
  }
  return candidates.map((candidate) => {
    const summary = candidate.summary ?? candidate.reason ?? candidate.name ?? candidate.title;
    return summary == null ? JSON.stringify(candidate) : String(summary);
  }).join(' / ');
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

function visitSlotLabel(value?: string | null) {
  if (value === 'MORNING') {
    return '오전';
  }
  if (value === 'AFTERNOON') {
    return '오후';
  }
  if (value === 'EVENING') {
    return '저녁';
  }
  return value ?? '';
}
