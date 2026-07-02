const STATUS_LABELS: Record<string, string> = {
  QUEUED: '대기',
  RUNNING: '실행 중',
  RAG_SEARCHED: '근거 검색 완료',
  TOOLS_CALLED: '도구 호출 완료',
  SUMMARY_READY: '요약 준비',
  FALLBACK_READY: '대체 응답 준비',
  SUCCEEDED: '성공',
  FAILED: '실패',
  PASS: '통과',
  WARN: '주의',
  FAIL: '실패',
  HIGH: '높음',
  MEDIUM: '보통',
  LOW: '낮음',
  READY: '준비',
  ACTIVE: '활성',
  OPEN: '열림',
  RESOLVED: '해결'
};

const TOOL_LABELS: Record<string, string> = {
  compatibility: '호환성 확인',
  power: '전력 여유 확인',
  size: '장착 규격 확인',
  performance: '성능 적합도',
  price: '예산 확인'
};

const SUCCESS_STATUSES = new Set(['SUCCEEDED', 'PASS', 'HIGH', 'ACTIVE', 'RESOLVED']);
const WARN_STATUSES = new Set(['WARN', 'MEDIUM', 'OPEN', 'READY', 'QUEUED', 'FALLBACK_READY']);
const FAIL_STATUSES = new Set(['FAILED', 'FAIL', 'LOW']);
const INFO_STATUSES = new Set(['RUNNING', 'RAG_SEARCHED', 'TOOLS_CALLED', 'SUMMARY_READY']);

export function koreanStatusLabel(status?: string | null) {
  if (!status) {
    return '-';
  }
  return STATUS_LABELS[status.toUpperCase()] ?? status;
}

export function koreanToolLabel(toolName?: string | null) {
  if (!toolName) {
    return '-';
  }
  return TOOL_LABELS[toolName.toLowerCase()] ?? toolName;
}

export function KoreanStatusBadge({ status }: { status: string }) {
  const normalized = status.toUpperCase();
  const cls = SUCCESS_STATUSES.has(normalized)
    ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
    : FAIL_STATUSES.has(normalized)
      ? 'bg-red-50 text-red-700 border-red-200'
      : WARN_STATUSES.has(normalized)
        ? 'bg-orange-50 text-orange-700 border-orange-200'
        : INFO_STATUSES.has(normalized)
          ? 'bg-blue-50 text-blue-700 border-blue-200'
          : 'bg-slate-100 text-slate-600 border-slate-200';

  return (
    <span title={status} className={`inline-flex rounded-full border px-2 py-1 text-[11px] font-bold ${cls}`}>
      {koreanStatusLabel(status)}
    </span>
  );
}
