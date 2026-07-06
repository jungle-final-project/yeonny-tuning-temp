import type { BuildGraphResolveResponse } from '../../../quote/aiSelection';
import type { QuoteDraftItem } from '../../types';

type PerformanceDetails = {
  cpu?: string | null;
  gpu?: string | null;
  cpuBenchmarkScore?: number | null;
  gpuBenchmarkScore?: number | null;
  cpuBenchmarkSummary?: string | null;
  gpuBenchmarkSummary?: string | null;
  vramGb?: number | null;
  benchmarkSource?: string | null;
};

// 담긴 견적의 성능을 셀프견적에 바로 보여준다 — resolveBuildGraph가 이미 내려주는 performance 툴 결과를
// 읽어(신규 백엔드 없음) CPU/GPU 성능 점수와 용도 적합도를 표시한다. 점수는 카테고리 내부 비교용(0~100)
// 공개 벤치마크/스펙 기반 참고값이며 정확 FPS·실성능을 보장하지 않는다(정책: guaranteePolicy).
export function QuotePerformancePanel({
  graph,
  draftItems
}: {
  graph?: BuildGraphResolveResponse;
  draftItems: QuoteDraftItem[];
}) {
  // CPU·GPU가 하나도 없으면 성능을 말할 근거가 없다 — 패널을 숨긴다.
  const hasScorable = draftItems.some((item) => item.category === 'CPU' || item.category === 'GPU');
  if (!hasScorable) {
    return null;
  }
  const performance = (graph?.toolResults ?? []).find((result) => result.tool === 'performance');
  if (!performance) {
    return null;
  }
  const details = (performance.details ?? {}) as PerformanceDetails;
  const cpuScore = toScore(details.cpuBenchmarkScore);
  const gpuScore = toScore(details.gpuBenchmarkScore);
  // 벤치마크 근거 vs 스펙 추정 — 사용자에게 신뢰 수준을 숨기지 않는다.
  const benchmarkBacked = details.benchmarkSource === 'benchmark_summaries';
  const fit = performance.status === 'PASS'
    ? { label: '성능 충분', tone: 'emerald' as const }
    : { label: '여유 낮음', tone: 'amber' as const };

  return (
    <section data-testid="quote-performance-panel" className="panel p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h2 className="text-sm font-black text-commerce-ink">담긴 견적 성능</h2>
          <span
            data-testid="quote-performance-fit"
            className={`rounded-full border px-2 py-0.5 text-[10px] font-black ${
              fit.tone === 'emerald'
                ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                : 'border-amber-200 bg-amber-50 text-amber-700'
            }`}
          >
            {fit.label}
          </span>
        </div>
        <span className="text-[10px] font-bold text-slate-400">
          {benchmarkBacked ? '공개 벤치마크 기준' : '스펙 추정 기준'} · 카테고리 내부 비교용 참고값
        </span>
      </div>

      <div className="grid gap-2.5 sm:grid-cols-2">
        <ScoreBar
          testid="quote-performance-cpu"
          label="CPU"
          name={details.cpu}
          score={cpuScore}
        />
        <ScoreBar
          testid="quote-performance-gpu"
          label="GPU"
          name={details.gpu}
          score={gpuScore}
          fallbackNote={gpuScore === null && details.vramGb ? `VRAM ${details.vramGb}GB` : undefined}
        />
      </div>

      <p className="mt-3 text-[10px] leading-relaxed text-slate-400">
        점수는 공개 벤치마크·공식 스펙 기반 참고값입니다 — 실제 성능이나 정확한 FPS를 보장하지 않습니다.
      </p>
    </section>
  );
}

function ScoreBar({
  testid,
  label,
  name,
  score,
  fallbackNote
}: {
  testid: string;
  label: string;
  name?: string | null;
  score: number | null;
  fallbackNote?: string;
}) {
  const empty = !name;
  // 점수대별 색: 높음=초록, 중간=파랑, 낮음=노랑 (호환 상태색과 구분되도록 성능은 파랑 계열 기준).
  const barTone = score === null ? 'bg-slate-300' : score >= 80 ? 'bg-emerald-500' : score >= 55 ? 'bg-brand-blue' : 'bg-amber-500';
  return (
    <div data-testid={testid} className="rounded-lg border border-commerce-line bg-slate-50/60 p-2.5">
      <div className="flex items-baseline justify-between gap-2">
        <span className="flex items-baseline gap-1.5">
          <span className="text-[11px] font-black text-slate-600">{label}</span>
          {score !== null ? <span className="text-[10px] font-bold text-slate-400">{scoreTier(score)}</span> : null}
        </span>
        {score !== null ? (
          <span data-testid={`${testid}-score`} className="text-[11px] font-black text-commerce-ink">
            {Math.round(score)}
            <span className="text-[9px] font-bold text-slate-400"> / 100</span>
          </span>
        ) : (
          <span className="text-[10px] font-bold text-slate-400">{fallbackNote ?? '점수 없음'}</span>
        )}
      </div>
      <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-slate-200">
        <div
          className={`h-full rounded-full transition-all ${barTone}`}
          style={{ width: `${score === null ? 0 : Math.min(100, Math.max(4, score))}%` }}
        />
      </div>
      <p className="mt-1.5 min-w-0 truncate text-[10px] text-slate-500" title={empty ? undefined : name ?? undefined}>
        {empty ? '아직 선택 안 함' : name}
      </p>
    </div>
  );
}

// 카테고리 내부 정규화 점수(0~100)를 사용자 언어 등급으로 — 원어(benchmark summary) 대신 노출한다.
function scoreTier(score: number): string {
  if (score >= 85) return '최상위급';
  if (score >= 70) return '상위급';
  if (score >= 55) return '중상위급';
  if (score >= 40) return '중급';
  return '입문급';
}

function toScore(value: unknown): number | null {
  const num = Number(value);
  return Number.isFinite(num) && num > 0 ? num : null;
}
