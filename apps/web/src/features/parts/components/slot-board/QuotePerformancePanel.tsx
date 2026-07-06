import { useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { BuildGraphResolveResponse } from '../../../quote/aiSelection';
import { checkBuildPerformance, type GameFpsEvidence } from '../../../quote/quoteApi';

// 담긴 견적으로 FPS를 조회할 수 있는 게임·해상도 — game_fps_benchmarks 시드 커버리지 기준.
const FPS_GAMES = [
  { key: 'pubg', label: '배그', query: 'pubg' },
  { key: 'valorant', label: '발로란트', query: 'valorant' },
  { key: 'overwatch-2', label: '오버워치2', query: 'overwatch' },
  { key: 'lost-ark', label: '로스트아크', query: 'lost ark' },
  { key: 'cyberpunk-2077', label: '사이버펑크', query: 'cyberpunk' }
] as const;
const FPS_RESOLUTIONS = [
  { key: 'FHD', label: 'FHD', query: 'fhd' },
  { key: 'QHD', label: 'QHD', query: 'qhd' },
  { key: '4K', label: '4K', query: '4k' }
] as const;

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
// 셀프견적 드래프트·저장 견적 어디서든 재사용할 수 있게 최소 필드(category, partId)만 받는다.
type PerfItem = { category: string; partId: string };

export function QuotePerformancePanel({
  graph,
  items
}: {
  graph?: BuildGraphResolveResponse;
  items: PerfItem[];
}) {
  // CPU·GPU가 하나도 없으면 성능을 말할 근거가 없다 — 패널을 숨긴다.
  const hasScorable = items.some((item) => item.category === 'CPU' || item.category === 'GPU');
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
  // FPS 참고범위는 GPU가 있어야 의미 있다 — CPU·GPU partIds만 조회에 넘긴다.
  const hasGpu = items.some((item) => item.category === 'GPU');
  const perfPartIds = items
    .filter((item) => item.category === 'CPU' || item.category === 'GPU')
    .map((item) => item.partId)
    .filter(Boolean);
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

      {hasGpu ? <GameFpsSection partIds={perfPartIds} /> : null}

      <p className="mt-3 text-[10px] leading-relaxed text-slate-400">
        점수는 공개 벤치마크·공식 스펙 기반 참고값입니다 — 실제 성능이나 정확한 FPS를 보장하지 않습니다.
      </p>
    </section>
  );
}

// 게임별 FPS 참고범위: 게임·해상도를 고르면 담긴 CPU/GPU 기준 공개 자료 FPS를 조회한다(기존 툴 엔드포인트).
function GameFpsSection({ partIds }: { partIds: string[] }) {
  const [gameKey, setGameKey] = useState<string>(FPS_GAMES[0].key);
  const [resKey, setResKey] = useState<string>('QHD');
  const game = FPS_GAMES.find((g) => g.key === gameKey) ?? FPS_GAMES[0];
  const resolution = FPS_RESOLUTIONS.find((r) => r.key === resKey) ?? FPS_RESOLUTIONS[1];
  const partKey = useMemo(() => [...partIds].sort().join(','), [partIds]);

  const { data, isFetching, isError } = useQuery({
    queryKey: ['quote-fps', partKey, game.key, resolution.key],
    queryFn: () => checkBuildPerformance({ partIds, game: game.query, resolution: resolution.query }),
    enabled: partIds.length > 0,
    placeholderData: keepPreviousData,
    staleTime: 5 * 60 * 1000
  });

  // 가장 근접한 근거(정렬 1순위)를 대표값으로 쓴다 — 서버가 exactness 순으로 정렬해 내려준다.
  const evidence: GameFpsEvidence | undefined = data?.details?.gameFpsEvidence?.[0];
  const avg = Number(evidence?.avgFps);
  const hasAvg = Number.isFinite(avg) && avg > 0;
  const low = Number(evidence?.onePercentLowFps);
  const hasLow = Number.isFinite(low) && low > 0;

  return (
    <div data-testid="quote-fps-section" className="mt-3 border-t border-commerce-line pt-3">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <span className="text-[11px] font-black text-slate-600">게임 예상 성능 <span className="text-slate-400">(참고)</span></span>
        <div className="flex gap-0.5 rounded-md border border-commerce-line bg-slate-50 p-0.5" role="group" aria-label="해상도 선택">
          {FPS_RESOLUTIONS.map((res) => (
            <button
              key={res.key}
              type="button"
              data-testid={`fps-res-${res.key}`}
              aria-pressed={resKey === res.key}
              onClick={() => setResKey(res.key)}
              className={`rounded px-2 py-0.5 text-[10px] font-black transition ${
                resKey === res.key ? 'bg-white text-commerce-ink shadow-sm' : 'text-slate-400 hover:text-slate-600'
              }`}
            >
              {res.label}
            </button>
          ))}
        </div>
      </div>

      <div className="mb-2.5 flex flex-wrap gap-1.5">
        {FPS_GAMES.map((g) => (
          <button
            key={g.key}
            type="button"
            data-testid={`fps-game-${g.key}`}
            aria-pressed={gameKey === g.key}
            onClick={() => setGameKey(g.key)}
            className={`rounded-full border px-2.5 py-1 text-[11px] font-black transition ${
              gameKey === g.key
                ? 'border-brand-blue bg-brand-blue text-white'
                : 'border-commerce-line bg-white text-slate-600 hover:border-brand-blue'
            }`}
          >
            {g.label}
          </button>
        ))}
      </div>

      {isFetching && !evidence ? (
        <div className="h-16 animate-pulse rounded-lg bg-slate-100" />
      ) : hasAvg ? (
        <div data-testid="fps-result" className="rounded-lg border border-commerce-line bg-slate-50/60 p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-[11px] font-black text-slate-600">
              {game.label} · {evidence?.resolution ?? resolution.label}
              {presetLabel(evidence?.graphicsPreset) ? ` · ${presetLabel(evidence?.graphicsPreset)}` : ''}
            </span>
            <span className={`text-[10px] font-black ${feelTone(avg).text}`}>{feelLabel(avg)}</span>
          </div>
          <div className="mt-1.5 flex items-baseline gap-1.5">
            <span data-testid="fps-avg" className="text-2xl font-black text-commerce-ink">{Math.round(avg)}</span>
            <span className="text-[11px] font-bold text-slate-500">fps 평균 (참고)</span>
          </div>
          <FpsGauge avg={avg} low={hasLow ? low : undefined} />
          <div className="mt-2 flex flex-wrap items-center justify-between gap-1.5 text-[10px]">
            <span className="font-bold text-slate-500">
              {hasLow ? `최저 약 ${Math.round(low)} fps (1% low)` : '최저값 자료 없음'}
            </span>
            <span className="font-bold text-slate-400">
              {exactnessLabel(evidence?.match?.evidenceExactness)}
              {evidence?.sourceName ? ` · ${evidence.sourceName}` : ''}
            </span>
          </div>
        </div>
      ) : (
        <div data-testid="fps-empty" className="rounded-lg border border-dashed border-slate-300 bg-white p-3 text-center text-[11px] font-bold text-slate-500">
          {isError ? '참고 자료를 불러오지 못했습니다.' : '이 조합의 공개 참고 자료가 아직 없어요.'}
        </div>
      )}

      <p className="mt-2 text-[10px] leading-relaxed text-slate-400">
        공개 자료 기준 참고 범위입니다 — 실제 FPS는 게임 설정·패치·드라이버에 따라 달라집니다.
      </p>
    </div>
  );
}

// FPS를 체감 경험으로 — 게이지 색과 라벨이 "얼마나 부드러운지"를 직관적으로 전한다.
function FpsGauge({ avg, low }: { avg: number; low?: number }) {
  const cap = 165; // 165Hz 상단 기준으로 스케일링
  const avgPct = Math.min(100, Math.max(3, (avg / cap) * 100));
  const lowPct = low ? Math.min(100, Math.max(0, (low / cap) * 100)) : null;
  return (
    <div className="relative mt-2 h-2.5 overflow-hidden rounded-full bg-gradient-to-r from-red-200 via-amber-200 to-emerald-200">
      {/* 평균 FPS 채움 */}
      <div className={`h-full rounded-full ${feelTone(avg).bar}`} style={{ width: `${avgPct}%` }} />
      {/* 1% low 마커 — 실제 체감 하한 */}
      {lowPct !== null ? (
        <span
          aria-hidden="true"
          className="absolute top-1/2 h-3.5 w-0.5 -translate-y-1/2 rounded-full bg-slate-600"
          style={{ left: `${lowPct}%` }}
        />
      ) : null}
    </div>
  );
}

function feelLabel(fps: number): string {
  if (fps >= 100) return '매우 부드러움';
  if (fps >= 60) return '부드러움';
  if (fps >= 40) return '무난';
  if (fps >= 30) return '다소 끊김';
  return '끊김';
}

function feelTone(fps: number): { text: string; bar: string } {
  if (fps >= 100) return { text: 'text-brand-blue', bar: 'bg-brand-blue' };
  if (fps >= 60) return { text: 'text-emerald-600', bar: 'bg-emerald-500' };
  if (fps >= 40) return { text: 'text-amber-600', bar: 'bg-amber-500' };
  return { text: 'text-red-600', bar: 'bg-red-500' };
}

// 그래픽 프리셋 → 사용자 언어(원어·소스 접두 노출 금지). 'PC_BUILDS_MEDIUM' 같은 원문에서 등급만 뽑는다.
function presetLabel(preset?: string | null): string {
  if (!preset) return '';
  const upper = preset.toUpperCase();
  if (upper.includes('ULTRA') || upper.includes('EPIC') || upper.includes('MAX')) return '최고 옵션';
  if (upper.includes('HIGH')) return '높음 옵션';
  if (upper.includes('MEDIUM') || upper.includes('MED')) return '중간 옵션';
  if (upper.includes('LOW')) return '낮음 옵션';
  return '';
}

// evidenceExactness → 사용자 언어(원어 노출 금지). 근거가 얼마나 이 견적에 가까운지.
function exactnessLabel(exactness?: string): string {
  switch (exactness) {
    case 'EXACT_PART_AND_RESOLUTION':
      return '이 부품 기준';
    case 'SAME_CLASS_AND_RESOLUTION':
      return '동급 부품 기준';
    case 'GPU_CLASS_REFERENCE':
    case 'GPU_CLASS_RESOLUTION_FALLBACK':
      return '동급 그래픽카드 기준';
    default:
      return '공개 참고 자료';
  }
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
