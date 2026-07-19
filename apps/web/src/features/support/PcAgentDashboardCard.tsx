import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { StateMessage, StatusBadge } from '../../components/ui';
import { ApiError } from '../../lib/api';
import { formatKstDateTime } from '../../lib/dateTime';
import { downloadPcAgentForCurrentUser } from './agentDownload';
import { getLatestPcAgentDiagnosis } from './supportApi';
import type { PcAgentDiagnosisDto, PcAgentDiagnosisResultDto, PcAgentDiagnosisSummaryDto } from './supportApi';
import { diagnosisStatus, usePcAgentDiagnosisPolling } from './usePcAgentDiagnosisPolling';

type LoadState = 'loading' | 'ready' | 'error';
type DownloadState = 'idle' | 'downloading' | 'done' | 'error';

const FAILED_STATES = new Set(['FAILED', 'CANCELLED', 'TIMED_OUT']);

export function PcAgentDashboardCard() {
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [loadError, setLoadError] = useState('');
  const [latestDiagnosis, setLatestDiagnosis] = useState<PcAgentDiagnosisSummaryDto | null>(null);
  const [diagnosisId, setDiagnosisId] = useState('');
  const [downloadState, setDownloadState] = useState<DownloadState>('idle');
  const [downloadMessage, setDownloadMessage] = useState('');
  const polling = usePcAgentDiagnosisPolling(diagnosisId);

  useEffect(() => {
    let disposed = false;
    let hasLoaded = false;
    let controller: AbortController | null = null;

    async function loadLatest(showLoading: boolean) {
      controller?.abort();
      const requestController = new AbortController();
      controller = requestController;
      if (showLoading) {
        setLoadState('loading');
        setLoadError('');
      }
      try {
        const response = await getLatestPcAgentDiagnosis(requestController.signal);
        if (disposed || controller !== requestController) return;
        hasLoaded = true;
        setLatestDiagnosis(response.diagnosis);
        setDiagnosisId(response.diagnosis?.diagnosisId ?? '');
        setLoadState('ready');
      } catch (cause) {
        if (disposed || controller !== requestController || isAbortError(cause)) return;
        if (!hasLoaded) {
          setLatestDiagnosis(null);
          setDiagnosisId('');
          setLoadState('error');
          setLoadError(cause instanceof ApiError
            ? cause.message
            : '최근 PC Agent 진단 상태를 불러오지 못했습니다.');
        }
      }
    }

    const handleFocus = () => {
      void loadLatest(false);
    };

    void loadLatest(true);
    window.addEventListener('focus', handleFocus);
    return () => {
      disposed = true;
      window.removeEventListener('focus', handleFocus);
      controller?.abort();
    };
  }, []);

  async function downloadAgent() {
    setDownloadState('downloading');
    setDownloadMessage('');
    try {
      await downloadPcAgentForCurrentUser();
      setDownloadState('done');
      setDownloadMessage('계정에 귀속된 PCAgent.zip을 내려받았습니다. 압축을 풀고 PCAgent.exe를 실행해 주세요.');
    } catch (cause) {
      setDownloadState('error');
      setDownloadMessage(cause instanceof ApiError && cause.status === 401
        ? '로그인 후 PC Agent를 다운로드해 주세요.'
        : 'PC Agent를 내려받지 못했습니다. 잠시 후 다시 시도해 주세요.');
    }
  }

  const diagnosis = polling.snapshot
    ? {
        ...polling.snapshot,
        asTicket: polling.snapshot.asTicket
          ?? (latestDiagnosis?.diagnosisId === polling.snapshot.diagnosisId ? latestDiagnosis.asTicket : null)
      }
    : latestDiagnosis;

  return (
    <section data-testid="pc-agent-dashboard-card" className="rounded border border-slate-200 bg-slate-50 p-4">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="text-base font-black text-brand-navy">PC Agent 진단</h2>
          <p className="mt-1 text-xs leading-5 text-slate-600">현재 로그인 계정에 연결된 최신 진단과 지원 요청 상태입니다.</p>
        </div>
        {diagnosis ? <StatusBadge status={diagnosisDisplayStatus(diagnosis)} /> : null}
      </div>

      {loadState === 'loading' ? (
        <StateMessage type="info" title="PC Agent 상태 확인 중" body="현재 계정의 최근 진단을 불러오고 있습니다." />
      ) : null}
      {loadState === 'error' ? (
        <StateMessage type="warn" title="PC Agent 상태 조회 실패" body={loadError} />
      ) : null}
      {loadState === 'ready' && !diagnosis ? (
        <NoDiagnosisState
          downloadState={downloadState}
          downloadMessage={downloadMessage}
          onDownload={downloadAgent}
        />
      ) : null}
      {diagnosis ? (
        <DiagnosisState diagnosis={diagnosis} pollingError={polling.error} />
      ) : null}
    </section>
  );
}

function NoDiagnosisState({
  downloadState,
  downloadMessage,
  onDownload
}: {
  downloadState: DownloadState;
  downloadMessage: string;
  onDownload: () => void;
}) {
  return (
    <div className="space-y-3">
      <p className="text-sm leading-6 text-slate-700">아직 이 계정에서 실행한 PC Agent 진단이 없습니다.</p>
      <div className="flex flex-col gap-2 sm:flex-row">
        <button
          type="button"
          onClick={onDownload}
          disabled={downloadState === 'downloading'}
          className="h-10 rounded bg-[#de6c2d] px-4 text-sm font-bold text-white transition hover:bg-[#c45c22] disabled:cursor-not-allowed disabled:opacity-60"
        >
          {downloadState === 'downloading' ? '다운로드 준비 중...' : 'PC Agent 다운로드'}
        </button>
        <Link to="/support/new" className="grid h-10 place-items-center rounded border border-slate-300 bg-white px-4 text-sm font-bold text-slate-700 transition hover:border-[#de6c2d] hover:text-[#de6c2d]">
          진단 시작 안내
        </Link>
      </div>
      {downloadMessage ? (
        <p className={`text-xs leading-5 ${downloadState === 'error' ? 'text-red-600' : 'text-slate-600'}`}>{downloadMessage}</p>
      ) : null}
    </div>
  );
}

function DiagnosisState({ diagnosis, pollingError }: { diagnosis: PcAgentDiagnosisDto | PcAgentDiagnosisSummaryDto; pollingError: string }) {
  const status = dashboardDiagnosisStatus(diagnosis);
  const progress = Math.max(0, Math.min(100, diagnosis.currentProgress ?? 0));
  const failed = FAILED_STATES.has(status);
  const result = 'result' in diagnosis ? diagnosis.result : null;
  const messages = 'events' in diagnosis ? diagnosis.events : diagnosis.recentMessages;
  const logs = messages
    .map((event) => event.message?.trim())
    .filter((message): message is string => Boolean(message))
    .slice(-3);
  const detailPath = `/support/new?diagnosisId=${encodeURIComponent(diagnosis.diagnosisId)}`;

  return (
    <div className="space-y-4">
      <div className="grid gap-2 text-xs text-slate-600 sm:grid-cols-2">
        <p><span className="font-bold text-slate-800">진단 ID</span><br /><span className="break-all">{diagnosis.diagnosisId}</span></p>
        <p><span className="font-bold text-slate-800">진단 시작</span><br />{formatKstDateTime(diagnosis.requestedAt ?? diagnosis.createdAt)}</p>
      </div>

      {!result && !failed ? (
        <div data-testid="pc-agent-dashboard-progress" className="space-y-3">
          <div className="flex items-center justify-between text-xs font-semibold text-slate-700">
            <span>{diagnosis.currentTask || '진단 준비 중'}</span>
            <span>{progress}%</span>
          </div>
          <div role="progressbar" aria-label="대시보드 PC Agent 진단 진행률" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress} className="h-2 overflow-hidden rounded-full bg-slate-200">
            <div className="h-full bg-[#de6c2d] transition-[width]" style={{ width: `${progress}%` }} />
          </div>
          {logs.length > 0 ? (
            <div>
              <p className="mb-1 text-xs font-bold text-slate-700">최근 진행 로그</p>
              <ol className="space-y-1 text-xs leading-5 text-slate-600">
                {logs.map((message, index) => <li key={`${message}-${index}`}>{message}</li>)}
              </ol>
            </div>
          ) : null}
          <Link to={detailPath} className="inline-flex h-10 items-center rounded border border-slate-300 bg-white px-4 text-sm font-bold text-slate-700 hover:border-[#de6c2d] hover:text-[#de6c2d]">
            진단 진행 화면
          </Link>
        </div>
      ) : null}

      {result ? <CompletedDiagnosis result={result} completedAt={diagnosis.completedAt} /> : null}

      {failed ? (
        <div className="space-y-3">
          <StateMessage type="warn" title={failureTitle(status)} body={logs[logs.length - 1] || '서버에 기록된 진단이 정상 완료되지 않았습니다.'} />
          <Link to="/support/new" className="inline-flex h-10 items-center rounded border border-slate-300 bg-white px-4 text-sm font-bold text-slate-700 hover:border-[#de6c2d] hover:text-[#de6c2d]">
            새 진단 시작
          </Link>
        </div>
      ) : null}

      {pollingError ? <StateMessage type="warn" title="진단 상태 조회 확인 필요" body={pollingError} /> : null}

      {result && !failed ? (
        <Link to={detailPath} className="inline-flex h-10 items-center rounded border border-slate-300 bg-white px-4 text-sm font-bold text-slate-700 hover:border-[#de6c2d] hover:text-[#de6c2d]">
          진단 결과 보기
        </Link>
      ) : null}

      {diagnosis.asTicket ? <LinkedTicket ticket={diagnosis.asTicket} /> : null}
    </div>
  );
}

function CompletedDiagnosis({ result, completedAt }: { result: PcAgentDiagnosisResultDto; completedAt?: string | null }) {
  const evidence = result.evidence ?? [];
  const code43Evidence = evidence.filter((item) => JSON.stringify(item).includes('43')).map(diagnosisDetailText);
  const actions = (result.actions ?? []).map(diagnosisDetailText).filter(Boolean);
  const remoteRecommended = isRecord(result.rawPayload) && result.rawPayload.remoteAsRecommended === true;

  return (
    <div data-testid="pc-agent-dashboard-result" className="rounded border border-emerald-200 bg-emerald-50 p-3 text-emerald-950">
      <p className="text-sm font-bold">{result.title}</p>
      <p className="mt-1 text-xs leading-5">{result.summary}</p>
      <p className="mt-2 text-[11px] text-emerald-700">완료 시각: {formatKstDateTime(completedAt ?? result.updatedAt)}</p>
      {code43Evidence.length > 0 ? (
        <div className="mt-3">
          <p className="text-xs font-bold">Code 43 근거</p>
          <ul className="mt-1 space-y-1 text-xs leading-5">
            {code43Evidence.map((item, index) => <li key={`${item}-${index}`}>{item}</li>)}
          </ul>
        </div>
      ) : null}
      {actions.length > 0 ? (
        <div className="mt-3">
          <p className="text-xs font-bold">권장 조치</p>
          <ol className="mt-1 list-decimal space-y-1 pl-4 text-xs leading-5">
            {actions.map((item, index) => <li key={`${item}-${index}`}>{item}</li>)}
          </ol>
        </div>
      ) : null}
      {remoteRecommended ? <p className="mt-3 text-xs font-bold text-emerald-800">원격지원 권장</p> : null}
    </div>
  );
}

function LinkedTicket({ ticket }: { ticket: NonNullable<PcAgentDiagnosisDto['asTicket']> }) {
  return (
    <div data-testid="pc-agent-dashboard-ticket" className="rounded border border-blue-200 bg-blue-50 p-3 text-blue-950">
      <p className="text-sm font-bold">원격지원 요청 접수</p>
      <div className="mt-2 flex flex-wrap gap-2">
        <StatusBadge status={ticket.status} />
        {ticket.reviewStatus ? <StatusBadge status={ticket.reviewStatus} /> : null}
        {ticket.supportDecision ? <StatusBadge status={ticket.supportDecision} /> : <span className="rounded-full bg-white px-3 py-1 text-xs font-bold text-blue-800">관리자 검토 대기</span>}
      </div>
      <p className="mt-2 text-xs text-blue-800">접수 시각: {formatKstDateTime(ticket.createdAt)}</p>
      <Link to={`/support/${encodeURIComponent(ticket.id)}`} className="mt-3 inline-flex h-9 items-center rounded border border-blue-300 bg-white px-3 text-xs font-bold text-blue-800 hover:border-blue-500">
        지원 요청 보기
      </Link>
    </div>
  );
}

function diagnosisDisplayStatus(diagnosis: PcAgentDiagnosisDto | PcAgentDiagnosisSummaryDto) {
  if (diagnosis.asTicket) return diagnosis.asTicket.status;
  return dashboardDiagnosisStatus(diagnosis);
}

function dashboardDiagnosisStatus(diagnosis: PcAgentDiagnosisDto | PcAgentDiagnosisSummaryDto) {
  if ('events' in diagnosis) return diagnosisStatus(diagnosis);
  return diagnosis.currentStatus || diagnosis.status;
}

function failureTitle(status: string) {
  if (status === 'CANCELLED') return 'PC Agent 진단 취소';
  if (status === 'TIMED_OUT') return 'PC Agent 진단 시간 초과';
  return 'PC Agent 진단 실패';
}

function diagnosisDetailText(value: unknown) {
  if (typeof value === 'string') return value.trim();
  if (!isRecord(value)) return String(value ?? '');
  const description = typeof value.description === 'string' ? value.description.trim() : '';
  if (description) return description;
  return Object.entries(value)
    .filter(([, item]) => ['string', 'number', 'boolean'].includes(typeof item))
    .map(([key, item]) => `${key}: ${String(item)}`)
    .join(', ');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isAbortError(cause: unknown) {
  return cause instanceof DOMException && cause.name === 'AbortError';
}
