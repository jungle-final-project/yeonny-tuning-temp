import { FormEvent, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { getAdminTicket, updateAdminTicket } from '../adminApi';
import type { AdminAsTicket, AsTicketStatus } from '../adminApi';

const STATUS_OPTIONS: AsTicketStatus[] = ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED'];
const REVIEW_OPTIONS = ['', 'NOT_REQUIRED', 'REQUIRED', 'IN_REVIEW', 'APPROVED', 'REJECTED'];
const SUPPORT_DECISION_OPTIONS = ['', 'SELF_SOLVABLE', 'REMOTE_POSSIBLE', 'VISIT_REQUIRED', 'NEEDS_MORE_INFO'];
const RISK_OPTIONS = ['', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

export function AdminTicketDetailPage() {
  const { ticketId = '' } = useParams();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<AsTicketStatus>('OPEN');
  const [assignedAdminId, setAssignedAdminId] = useState('');
  const [adminNote, setAdminNote] = useState('');
  const [reviewStatus, setReviewStatus] = useState('');
  const [supportDecision, setSupportDecision] = useState('');
  const [riskLevel, setRiskLevel] = useState('');
  const [autoResponseAllowed, setAutoResponseAllowed] = useState(false);

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
      setAutoResponseAllowed(Boolean(ticket.autoResponseAllowed));
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
      autoResponseAllowed
    }),
    onSuccess: (updatedTicket) => {
      queryClient.setQueryData(['admin-as-ticket', ticketId], updatedTicket);
      queryClient.invalidateQueries({ queryKey: ['admin-as-tickets'] });
      queryClient.invalidateQueries({ queryKey: ['admin-audit-logs-recent'] });
    }
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    updateMutation.mutate();
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

        <Panel title="관리자 조치" subtitle="처리 상태, 담당자, 검수 판정을 저장합니다.">
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
              <label htmlFor="admin-ticket-review-status" className="mb-1 block text-xs font-bold text-slate-600">검수 상태</label>
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
              <label htmlFor="admin-ticket-support-decision" className="mb-1 block text-xs font-bold text-slate-600">지원 판정</label>
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
                {updateMutation.isPending ? '저장 중' : '상태 저장'}
              </button>
            </div>
            {updateMutation.isSuccess ? <StateMessage type="success" title="저장 완료" body="AS 티켓 상태와 관리자 조치 내용을 저장했습니다." /> : null}
            {updateMutation.isError ? <StateMessage type="warn" title="저장 실패" body="허용되지 않는 상태 전이이거나 담당자 ID가 유효하지 않습니다." /> : null}
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
    { '항목': '검수 상태', '내용': ticket.reviewStatus ?? '-' },
    { '항목': '지원 판정', '내용': ticket.supportDecision ?? '-' },
    { '항목': '위험도', '내용': ticket.riskLevel ?? '-' },
    { '항목': '자동 응답', '내용': ticket.autoResponseAllowed ? '허용' : '차단' },
    { '항목': '제목/증상', '내용': ticket.title ?? firstLine(ticket.symptom) },
    { '항목': '상세 설명', '내용': ticket.description ?? ticket.detailDescription ?? ticket.symptom },
    { '항목': '사용자', '내용': ticket.userEmail ?? ticket.userName ?? ticket.userId ?? '-' },
    { '항목': '로그', '내용': logSummary(ticket) },
    { '항목': '원인 후보', '내용': formatCandidates(ticket.causeCandidates) },
    { '항목': '업그레이드 후보', '내용': formatCandidates(ticket.upgradeCandidates) },
    { '항목': '담당자', '내용': ticket.assignedAdminId ?? '미배정' },
    { '항목': '관리자 메모', '내용': ticket.adminNote ?? '-' },
    { '항목': '생성일', '내용': formatDateTime(ticket.createdAt) },
    { '항목': '해결일', '내용': formatDateTime(ticket.resolvedAt) }
  ];
}

function logSummary(ticket: AdminAsTicket) {
  if (ticket.logSummary) {
    return ticket.logSummary;
  }
  return ticket.logUploadId ? `업로드된 로그 있음: ${shortId(ticket.logUploadId)}` : '연결된 로그 없음';
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

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}
