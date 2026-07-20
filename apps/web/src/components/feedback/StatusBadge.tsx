export function StatusBadge({ status }: { status: string }) {
  const s = status.toUpperCase();
  const label = statusLabel(status);
  const cls = POSITIVE_STATUSES.has(s)
    ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
    : WARNING_STATUSES.has(s)
      ? 'bg-orange-50 text-orange-700 border-orange-200'
      : DANGER_STATUSES.has(s)
        ? 'bg-rose-50 text-rose-700 border-rose-200'
      : 'bg-slate-100 text-slate-600 border-slate-200';
  return <span className={`inline-flex rounded-full border px-2 py-1 text-[11px] font-bold ${cls}`} title={status}>{label}</span>;
}

export function statusLabel(status: string) {
  return STATUS_LABELS[status.toUpperCase()] ?? status;
}

const STATUS_LABELS: Record<string, string> = {
  PASS: '통과',
  WARN: '주의',
  FAIL: '불가',
  ACTIVE: '활성',
  RULE_READY: '규칙 진단 완료',
  REQUIRED: '검토 필요',
  NOT_REQUIRED: '검토 불필요',
  IN_REVIEW: '검토 중',
  APPROVED: '승인됨',
  REJECTED: '반려됨',
  SELF_SOLVABLE: '자가 조치 가능',
  REMOTE_POSSIBLE: '원격 지원 가능',
  REPAIR_OR_REPLACE: '수리/교체 필요',
  MONITOR_ONLY: '관찰 필요',
  UNSUPPORTED: '지원 범위 밖',
  NEEDS_MORE_INFO: '추가 정보 필요',
  REQUESTED: '신청됨',
  ADMIN_REVIEWING: '관리자 검토 중',
  ADMIN_APPROVED: '승인됨',
  SCHEDULED: '예약 확정',
  RESCHEDULE_REQUESTED: '일정 변경 요청',
  COMPLETED: '완료',
  LINK_SENT: '원격 코드 발송',
  SESSION_STARTED: '원격 연결 시작',
  SESSION_ENDED: '원격 연결 종료',
  VISIT_IN_PROGRESS: '방문 진행 중',
  VISIT_COMPLETED: '방문 완료',
  VISIT_REQUIRED: '방문 지원 필요',
  NONE: '위험 신호 없음',
  CAUTION: '주의 필요',
  STOP_USE_UNTIL_REVIEW: '사용 중지 권고',
  OPEN: '접수됨',
  ASSIGNED: '담당자 배정',
  IN_PROGRESS: '처리 중',
  RESOLVED: '해결됨',
  CLOSED: '종료됨',
  CANCELLED: '취소됨',
  LOW: '낮음',
  MEDIUM: '중간',
  HIGH: '높음'
};

const POSITIVE_STATUSES = new Set(['PASS', 'HIGH', 'ACTIVE', 'RESOLVED', 'APPROVED', 'ADMIN_APPROVED', 'COMPLETED', 'VISIT_COMPLETED', 'RULE_READY', 'REMOTE_POSSIBLE', 'SELF_SOLVABLE']);
const WARNING_STATUSES = new Set(['WARN', 'MEDIUM', 'OPEN', 'REQUESTED', 'ADMIN_REVIEWING', 'SCHEDULED', 'LINK_SENT', 'SESSION_STARTED', 'VISIT_IN_PROGRESS', 'REQUIRED', 'NEEDS_MORE_INFO', 'MONITOR_ONLY', 'CAUTION']);
const DANGER_STATUSES = new Set(['FAIL', 'LOW', 'REJECTED', 'CANCELLED', 'VISIT_REQUIRED', 'REPAIR_OR_REPLACE', 'UNSUPPORTED', 'STOP_USE_UNTIL_REVIEW']);
