import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode, type MutableRefObject } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { ChevronDown, Sparkles } from 'lucide-react';
import {
  DEFAULT_AI_DRAFT_PERFORMANCE_SELECTION,
  getAiStorageOwnerKey,
  getScopedAiStorageKey,
  rememberPerformanceView,
  PART_CATEGORY_LABELS,
  type BuildGraphResolveResponse,
  type PartCategory
} from '../../../quote/aiSelection';
import { CompositeScoreGauge } from '../../../quote/components/CompositeScoreGauge';
import { openAiAssistant, type PerfCompareTarget } from '../../../../lib/events';
import { checkBuildPerformance, resolveBuildGraph, type GameFpsEvidence } from '../../../quote/quoteApi';
import { listParts } from '../../partsApi';
import type { PartCompatibility } from '../../types';
import { HelpTip } from './HelpTip';
import { PerformanceComparisonSpotlight } from './PerformanceComparisonSpotlight';
import { CompositeGhostArc, useAnimatedNumber } from './PerformanceComparisonVisuals';
import { withObjectParticle } from './koreanParticle';

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
const DEFAULT_FPS_GAME = FPS_GAMES.find((game) => game.query === DEFAULT_AI_DRAFT_PERFORMANCE_SELECTION.gameQuery) ?? FPS_GAMES[0];
const DEFAULT_FPS_RESOLUTION = FPS_RESOLUTIONS.find(
  (resolution) => resolution.query === DEFAULT_AI_DRAFT_PERFORMANCE_SELECTION.resolutionQuery
) ?? FPS_RESOLUTIONS[2];

// FPS 수평 바 스케일 상한 — 일반적인 게이밍 모니터 주사율(165Hz) 기준.
const FPS_CAP = 165;
const PERFORMANCE_SPOTLIGHT_SEEN_KEY = 'buildgraph.performanceComparisonSpotlight.seen.v1';
const PERFORMANCE_SPOTLIGHT_SEEN_LIMIT = 20;

function performanceSpotlightStorageKey() {
  return getScopedAiStorageKey(PERFORMANCE_SPOTLIGHT_SEEN_KEY, getAiStorageOwnerKey());
}

function hasSeenPerformanceSpotlight(requestKey: string) {
  if (typeof window === 'undefined') return false;
  const storageKey = performanceSpotlightStorageKey();
  if (!storageKey) return false;
  try {
    const parsed = JSON.parse(window.sessionStorage.getItem(storageKey) ?? '[]');
    return Array.isArray(parsed) && parsed.includes(requestKey);
  } catch {
    return false;
  }
}

function rememberPerformanceSpotlight(requestKey: string) {
  if (typeof window === 'undefined') return;
  const storageKey = performanceSpotlightStorageKey();
  if (!storageKey) return;
  let keys: string[] = [];
  try {
    const parsed = JSON.parse(window.sessionStorage.getItem(storageKey) ?? '[]');
    if (Array.isArray(parsed)) keys = parsed.filter((value): value is string => typeof value === 'string');
  } catch {
    keys = [];
  }
  const next = [...keys.filter((value) => value !== requestKey), requestKey].slice(-PERFORMANCE_SPOTLIGHT_SEEN_LIMIT);
  window.sessionStorage.setItem(storageKey, JSON.stringify(next));
}

// 담긴 견적의 성능을 셀프견적에 바로 보여준다 — resolveBuildGraph가 내려주는 compositeScore를
// 단일 1000점 종합점수로 표시한다. CPU/GPU 카테고리 내부 점수는 더 이상 사용자 대표 점수로 노출하지 않는다.
// 셀프견적 드래프트·저장 견적 어디서든 재사용할 수 있게 최소 필드(category, partId)만 받는다.
// name/currentPrice는 교체 비교(가격·성능 향상)에만 쓰는 선택 필드다 — 없으면 비용 문구만 생략된다.
// quantity는 종합점수 고스트 비교 resolve에만 쓰고 없으면 1로 본다.
type PerfItem = { category: string; partId: string; name?: string; currentPrice?: number; quantity?: number };

export type QuotePerformanceView = {
  gameLabel: string;
  gameQuery: string;
  resolutionLabel: string;
  resolutionQuery: string;
  sourceFingerprint: string;
  evidenceSettled: boolean;
  avgFps?: number;
};

// 팀장 확정 설계(최종 개편): 헤더 한 줄 + 데이터 시각화 본문 + 하단 액션 줄.
// 헤더 = 타이틀·적합 배지(왼쪽) + [CPU|GPU 토글 + 교체 후보 선택 ▾] 콤보(오른쪽 끝, 팝오버).
// 본문 = 왼쪽 카드 [종합점수 아크(비교 시 기존 회색/변경 파랑 고스트 아크) | 가격·성능 향상
//        (0 기준 분기형 막대 + 추가 비용 강조, 미선택 시에도 빈 구조 고정, 칸 높이 기준 세로 중앙 정렬)],
//        오른쪽 [게임 예상 성능 수평 막대 + 게임 칩·해상도, 비교 시 기존/변경 0→값 게이지 바 2줄 + 델타].
//        "교체 비교 · A → B" 배너는 없다 — 후보명은 헤더 콤보가 보여준다.
// 액션 줄 = 비교 활성 시에만 패널 하단 전체 폭([이 제품으로 교체해 담기] + [비교 해제]).
// onStartComparison이 없으면(저장 견적 등) 헤더 콤보·향상 그래프 없이 [종합점수 | 게임 예상 성능]으로 렌더된다.
export function QuotePerformancePanel({
  graph,
  items,
  comparison = null,
  onClearComparison,
  onStartComparison,
  onApplyComparison,
  checkoutActions,
  isLoading = false,
  isError = false,
  onRetry,
  onPerformanceViewChange,
  compact = false
}: {
  graph?: BuildGraphResolveResponse;
  items: PerfItem[];
  comparison?: PerfCompareTarget | null;
  onClearComparison?: () => void;
  onStartComparison?: (target: PerfCompareTarget) => void;
  onApplyComparison?: (target: PerfCompareTarget) => Promise<unknown>;
  checkoutActions?: ReactNode;
  isLoading?: boolean;
  isError?: boolean;
  onRetry?: () => void;
  onPerformanceViewChange?: (view: QuotePerformanceView) => void;
  compact?: boolean;
}) {
  const compositeScore = graph?.compositeScore;
  const roundedCompositeScore = compositeScore ? Math.round(compositeScore.score) : null;
  const previousCompositeScoreRef = useRef<number | null>(roundedCompositeScore);
  const [scoreDelta, setScoreDelta] = useState<number | null>(null);

  useEffect(() => {
    if (roundedCompositeScore === null) return;
    const previousScore = previousCompositeScoreRef.current;
    if (previousScore === null) {
      previousCompositeScoreRef.current = roundedCompositeScore;
      return;
    }
    if (previousScore === roundedCompositeScore) return;
    setScoreDelta(roundedCompositeScore - previousScore);
    previousCompositeScoreRef.current = roundedCompositeScore;
  }, [roundedCompositeScore]);

  if (!compositeScore) {
    return compact ? (
      <CompactPerformancePlaceholder
        hasItems={items.length > 0}
        isLoading={isLoading}
        isError={isError}
        onRetry={onRetry}
        checkoutActions={checkoutActions}
      />
    ) : null;
  }
  // FPS 참고범위는 GPU가 있어야 의미 있다 — CPU·GPU만 조회에 넘긴다.
  const hasGpu = items.some((item) => item.category === 'GPU');
  const perfItems = items.filter((item) => item.category === 'CPU' || item.category === 'GPU');

  return (
    <section data-testid="quote-performance-panel">
      <PerfPanelBody
        compositeScore={compositeScore}
        perfItems={perfItems}
        allItems={items}
        hasGpu={hasGpu}
        comparison={comparison}
        onClearComparison={onClearComparison}
        onStartComparison={onStartComparison}
        onApplyComparison={onApplyComparison}
        checkoutActions={checkoutActions}
        scoreDelta={scoreDelta}
        onPerformanceViewChange={onPerformanceViewChange}
        compact={compact}
      />
    </section>
  );
}

function CompactPerformancePlaceholder({
  hasItems,
  isLoading,
  isError,
  onRetry,
  checkoutActions
}: {
  hasItems: boolean;
  isLoading: boolean;
  isError: boolean;
  onRetry?: () => void;
  checkoutActions?: ReactNode;
}) {
  const statusText = !hasItems
    ? '부품을 담으면 계산됩니다'
    : isError
      ? '점수를 확인하지 못했습니다'
      : isLoading
        ? '견적을 분석하고 있습니다'
        : '점수 계산을 준비하고 있습니다';

  return (
    <section data-testid="quote-performance-panel" data-state={isError ? 'error' : isLoading ? 'loading' : 'empty'}>
      <div data-testid="quote-performance-grid" className="rounded-lg border border-commerce-line bg-white px-3 py-2 lg:min-h-[108px]">
        <div className="grid gap-3 lg:grid-cols-[190px_minmax(0,1fr)_auto] lg:items-center">
          <div className="border-b border-commerce-line pb-2 lg:border-b-0 lg:border-r lg:pb-0 lg:pr-3">
            <div className="text-[10px] font-black text-slate-500">종합 점수</div>
            <div className="mt-1 flex items-baseline gap-1">
              <strong className="text-2xl font-black text-slate-300">—</strong>
              <span className="text-[10px] font-bold text-slate-300">/ 1,000</span>
            </div>
            <div className="mt-1 text-[10px] font-bold text-slate-400">{statusText}</div>
          </div>

          <div className="min-w-0">
            <div className="text-[11px] font-black text-slate-600">게임 예상 성능</div>
            <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-slate-100">
              <div className={`h-full rounded-full bg-slate-200 ${isLoading ? 'w-2/5 animate-pulse' : 'w-0'}`} />
            </div>
            {isError && onRetry ? (
              <button
                type="button"
                data-testid="quote-performance-retry"
                onClick={onRetry}
                className="mt-2 rounded border border-commerce-line bg-white px-2.5 py-1 text-[10px] font-black text-slate-600 transition hover:border-brand-blue hover:text-brand-blue"
              >
                다시 계산
              </button>
            ) : null}
          </div>

          {checkoutActions ? (
            <div data-testid="quote-checkout-actions" className="flex flex-wrap items-center justify-end gap-2 border-t border-commerce-line pt-2 lg:border-l lg:border-t-0 lg:pl-3 lg:pt-0">
              {checkoutActions}
            </div>
          ) : null}
        </div>
      </div>
    </section>
  );
}

// 패널 본문: 게임/해상도 선택과 FPS 조회(기존·변경 조합)를 한곳에서 소유하고,
// 헤더 콤보(후보 선택)·왼쪽 카드(종합점수 아크 + 가격·성능 향상)·오른쪽 작업창(게임 예상 성능)·하단 액션 줄에 같은 상태를 내려준다.
function PerfPanelBody({
  compositeScore,
  perfItems,
  allItems,
  hasGpu,
  comparison,
  onClearComparison,
  onStartComparison,
  onApplyComparison,
  checkoutActions,
  scoreDelta,
  onPerformanceViewChange,
  compact
}: {
  compositeScore: NonNullable<BuildGraphResolveResponse['compositeScore']>;
  perfItems: PerfItem[];
  allItems: PerfItem[];
  hasGpu: boolean;
  comparison: PerfCompareTarget | null;
  onClearComparison?: () => void;
  onStartComparison?: (target: PerfCompareTarget) => void;
  onApplyComparison?: (target: PerfCompareTarget) => Promise<unknown>;
  checkoutActions?: ReactNode;
  scoreDelta: number | null;
  onPerformanceViewChange?: (view: QuotePerformanceView) => void;
  compact: boolean;
}) {
  const queryClient = useQueryClient();
  const [gameKey, setGameKey] = useState<string>(DEFAULT_FPS_GAME.key);
  const [resKey, setResKey] = useState<string>(DEFAULT_FPS_RESOLUTION.key);
  const game = FPS_GAMES.find((g) => g.key === gameKey) ?? DEFAULT_FPS_GAME;
  const resolution = FPS_RESOLUTIONS.find((r) => r.key === resKey) ?? DEFAULT_FPS_RESOLUTION;
  // 챗봇의 "더 부드럽게"는 지금 이 화면의 게임·해상도를 기준으로 해석된다 — 선택을 남겨 둔다.
  useEffect(() => {
    rememberPerformanceView({ gameQuery: game.query, resolution: resolution.key });
  }, [game.query, resolution.key]);

  // 비교 헤더에서 토글(구분선 왼쪽)과 후보 선택(오른쪽)을 떨어뜨려 놓기 위해 카테고리를 여기서 소유한다.
  const [compareCategory, setCompareCategory] = useState<PerfCompareTarget['category']>(comparison?.category ?? 'GPU');
  const compareToggleRef = useRef<HTMLDivElement | null>(null);

  const partIds = perfItems.map((item) => item.partId).filter(Boolean);
  const partKey = useMemo(() => [...partIds].sort().join(','), [partIds]);

  // 비교 대상이 현재 견적과 어긋나면(같은 부품이거나 기존 부품이 없음) 비교를 그리지 않는다 — 상위에서 해제하지만 이중 안전망.
  const currentPart = comparison ? perfItems.find((item) => item.category === comparison.category) : undefined;
  const activeComparison = comparison && currentPart && currentPart.partId !== comparison.partId ? comparison : null;
  // 연계 변경(예: GPU와 함께 바뀌는 파워)을 카테고리→새 partId 맵으로 — 변경 조합은 제안된 조합 그대로 계산해야 한다.
  const linkedByCategory = useMemo(() => {
    const map = new Map<string, { partId: string; quantity: number }>();
    (activeComparison?.linkedChanges ?? []).forEach((change) => {
      if (change.partId && change.category !== activeComparison?.category) {
        map.set(change.category, { partId: change.partId, quantity: 1 });
      }
    });
    return map;
  }, [activeComparison]);
  const comparePartIds = activeComparison
    ? perfItems
        .map((item) => (item.category === activeComparison.category
          ? activeComparison.partId
          : linkedByCategory.get(item.category)?.partId ?? item.partId))
        .filter(Boolean)
    : [];
  const compareKey = useMemo(() => [...comparePartIds].sort().join(','), [comparePartIds]);

  const { data, isFetching, isError, isPlaceholderData } = useQuery({
    queryKey: ['quote-fps', partKey, game.key, resolution.key],
    queryFn: () => checkBuildPerformance({ partIds, game: game.query, resolution: resolution.query }),
    enabled: hasGpu && partIds.length > 0,
    placeholderData: keepPreviousData,
    staleTime: 5 * 60 * 1000
  });
  // 변경 조합 조회 — queryKey에 비교 대상 partId가 섞인 목록이 들어가 후보가 바뀌면 다시 조회한다.
  const compareQuery = useQuery({
    queryKey: ['quote-fps', compareKey, game.key, resolution.key],
    queryFn: () => checkBuildPerformance({ partIds: comparePartIds, game: game.query, resolution: resolution.query }),
    enabled: hasGpu && Boolean(activeComparison) && comparePartIds.length > 0,
    placeholderData: keepPreviousData,
    staleTime: 5 * 60 * 1000
  });

  // 종합점수 고스트 비교 — 현재 드래프트 items에서 비교 카테고리와 연계 변경(파워 등) partId를
  // 제안된 조합 그대로 치환해 기존 resolve 계약(source=AI_BUILD + items)으로 compositeScore만 쓴다.
  // 연계 변경을 빼면 "기존 파워+새 GPU" 같은 미제안 조합이 전력 FAIL로 0점 고스트를 그린다.
  // 로딩 중엔 단일값을 유지하고, 실패하거나 compositeScore가 없으면 조용히 단일값으로 폴백한다(비교 강제 금지).
  const ghostItems = activeComparison
    ? allItems
        .filter((item) => Boolean(item.partId) && isPartCategory(item.category))
        .map((item) => ({
          partId: item.category === activeComparison.category
            ? activeComparison.partId
            : linkedByCategory.get(item.category)?.partId ?? item.partId,
          category: item.category as PartCategory,
          quantity: linkedByCategory.has(item.category) ? 1 : item.quantity ?? 1
        }))
    : [];
  const ghostKey = ghostItems.map((item) => `${item.category}:${item.partId}x${item.quantity}`).sort().join(',');
  const ghostQuery = useQuery({
    queryKey: ['build-graph', 'perf-compare-composite', ghostKey],
    queryFn: () => resolveBuildGraph({
      source: 'AI_BUILD',
      view: 'FOCUSED',
      items: ghostItems,
      focus: { mode: 'BUILD_OVERVIEW', tool: 'price' }
    }),
    enabled: Boolean(activeComparison) && ghostItems.length > 0,
    staleTime: 5 * 60 * 1000,
    retry: false
  });
  const ghostScore = activeComparison ? ghostQuery.data?.compositeScore?.score : undefined;
  const hasGhostScore = typeof ghostScore === 'number' && Number.isFinite(ghostScore);
  const [spotlightRequestKey, setSpotlightRequestKey] = useState<string | null>(null);
  const activeAiRequestKey = activeComparison?.origin === 'AI' ? activeComparison.requestKey?.trim() : undefined;

  useEffect(() => {
    if (!activeAiRequestKey || !hasGhostScore || hasSeenPerformanceSpotlight(activeAiRequestKey)) return;
    rememberPerformanceSpotlight(activeAiRequestKey);
    setSpotlightRequestKey(activeAiRequestKey);
  }, [activeAiRequestKey, hasGhostScore]);

  useEffect(() => {
    if (!spotlightRequestKey) return;
    if (!activeComparison || activeComparison.requestKey !== spotlightRequestKey) {
      setSpotlightRequestKey(null);
    }
  }, [activeComparison, spotlightRequestKey]);

  const dismissSpotlight = useCallback(() => setSpotlightRequestKey(null), []);
  const spotlightPriceComparison = useMemo(() => {
    if (!activeComparison) return null;
    const exact = activeComparison.totalPriceComparison;
    if (exact && Number.isFinite(exact.before) && Number.isFinite(exact.after) && exact.before > 0 && exact.after > 0) {
      return exact;
    }

    const before = allItems.reduce((sum, item) => sum + Math.max(0, item.currentPrice ?? 0) * Math.max(1, item.quantity ?? 1), 0);
    if (before <= 0) return null;
    const replacements = new Map<string, number>([[activeComparison.category, activeComparison.price]]);
    for (const change of activeComparison.linkedChanges ?? []) {
      if (change.category && Number.isFinite(change.price) && change.price > 0) replacements.set(change.category, change.price);
    }
    let after = before;
    for (const [category, replacementPrice] of replacements) {
      const currentCategoryTotal = allItems
        .filter((item) => item.category === category)
        .reduce((sum, item) => sum + Math.max(0, item.currentPrice ?? 0) * Math.max(1, item.quantity ?? 1), 0);
      after += replacementPrice - currentCategoryTotal;
    }
    return after > 0 ? { before, after } : null;
  }, [activeComparison, allItems]);

  // 근거 행 선택 — 서버 정렬(정확도순)을 신뢰하되, 요청한 게임·해상도와 실제로 일치하는 행이 있으면
  // 그 행을 우선한다([0]이 다른 해상도 폴백인데 뒤에 정합 행이 남아 있는 데이터 어긋남 방어). 없으면 [0] 유지.
  const evidence: GameFpsEvidence | undefined = pickEvidenceRow(data?.details?.gameFpsEvidence);
  const avg = Number(evidence?.avgFps);
  const hasAvg = Number.isFinite(avg) && avg > 0;
  const low = Number(evidence?.onePercentLowFps);
  const hasLow = Number.isFinite(low) && low > 0;

  useEffect(() => {
    onPerformanceViewChange?.({
      gameLabel: game.label,
      gameQuery: game.query,
      resolutionLabel: resolution.label,
      resolutionQuery: resolution.query,
      sourceFingerprint: partKey,
      evidenceSettled: !isFetching && !isPlaceholderData,
      avgFps: !isFetching && !isPlaceholderData && hasAvg ? Math.round(avg) : undefined
    });
  }, [avg, game.label, game.query, hasAvg, isFetching, isPlaceholderData, onPerformanceViewChange, partKey, resolution.label, resolution.query]);

  const compareEvidence: GameFpsEvidence | undefined = activeComparison
    ? pickEvidenceRow(compareQuery.data?.details?.gameFpsEvidence)
    : undefined;
  const compareAvg = Number(compareEvidence?.avgFps);
  const hasCompareAvg = Number.isFinite(compareAvg) && compareAvg > 0;
  const compareLow = Number(compareEvidence?.onePercentLowFps);
  const hasCompareLow = Number.isFinite(compareLow) && compareLow > 0;
  // 비교가 켜져 있는데 자료가 아직 안 왔으면 로딩, 끝내 없으면 사유를 알리고 비교를 강제하지 않는다.
  const isCompareReady = Boolean(activeComparison) && hasAvg && hasCompareAvg;
  const isCompareLoading = Boolean(activeComparison) && !isCompareReady && (isFetching || compareQuery.isFetching);
  // 비교 가드 — 두 근거 행의 측정 조건(해상도·그래픽 옵션·출처)이 다르거나, 어느 쪽이든 요청 해상도와
  // 다른 폴백 행이면 ±% 하락/상승을 확정 표기하지 않는다(상위 부품이 조건 차이 탓에 낮아 보이는 사고 방어).
  // 두 값 자체는 숨기지 않는다 — 각자의 조건과 함께 참고치로 보여주고 중립 고지로 대체한다.
  const isCompareConditionMismatch = isCompareReady && Boolean(
    evidence && compareEvidence && (
      evidence.resolution !== compareEvidence.resolution
      || (evidence.graphicsPreset ?? null) !== (compareEvidence.graphicsPreset ?? null)
      || (evidence.sourceName ?? null) !== (compareEvidence.sourceName ?? null)
      || evidence.match?.resolutionMatched === false
      || compareEvidence.match?.resolutionMatched === false
    )
  );
  // 확정 델타(배지·성능 ±% 막대)는 같은 조건에서 잰 두 값일 때만 내린다.
  const isCompareComparable = isCompareReady && !isCompareConditionMismatch;
  // 중앙 강조는 이전 query placeholder를 새 변경안 근거로 보여주지 않는다.
  const isSpotlightFpsReady = isCompareReady && !isPlaceholderData && !compareQuery.isPlaceholderData;
  const isSpotlightFpsLoading = Boolean(activeComparison) && !isSpotlightFpsReady && (
    isFetching || isPlaceholderData || compareQuery.isFetching || compareQuery.isPlaceholderData
  );

  // 큰 FPS 숫자 카운트업(단일 표시) — 값이 바뀌면 이전 값→새 값으로 rAF easeOut 스윕.
  const animatedAvg = useAnimatedNumber(hasAvg ? avg : 0);

  // 교체 담기: 비교 중인 후보를 실제 견적에 반영한다(성공 시 상위에서 드래프트 갱신 → 비교 자동 해제 → 값 스윕).
  const [isApplying, setIsApplying] = useState(false);
  const [applyError, setApplyError] = useState<string | null>(null);
  const activeComparisonPartId = activeComparison?.partId ?? null;
  useEffect(() => {
    // 비교 대상이 바뀌거나 해제되면 이전 교체 실패 안내를 지운다.
    setApplyError(null);
  }, [activeComparisonPartId]);
  const applyComparison = async () => {
    if (!activeComparison || !onApplyComparison || isApplying) return;
    setIsApplying(true);
    setApplyError(null);
    try {
      await onApplyComparison(activeComparison);
      // 교체 후에는 후보 호환 평가가 새 조합 기준으로 달라진다 — 선택기 목록을 재평가한다.
      void queryClient.invalidateQueries({ queryKey: ['parts', 'perf-compare-candidates'] });
    } catch {
      setApplyError('부품을 교체하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setIsApplying(false);
    }
  };

  const hasWorkspace = Boolean(onStartComparison);

  // 종합점수 아크 — 왼쪽 카드 첫 칸. 비교 활성 + 변경 조합 점수가 준비되면
  // CompositeScoreGauge(공용, 수정 금지) 대신 패널 로컬 고스트 아크로 기존(회색)/변경(파랑)을 겹쳐 보여준다.
  const compositeCard = (
      <div data-testid="quote-composite-score-card">
        <div className="mb-1 flex flex-nowrap items-center justify-between gap-2 text-[11px]">
          <span data-testid="quote-composite-score-title" className="shrink-0 whitespace-nowrap font-black text-slate-600">종합 점수</span>
          {hasWorkspace ? (
            <span className="inline-flex items-center gap-2">
              <span className="hidden font-bold text-slate-400 xl:inline">호환·성능·여유 종합 1000점</span>
            <button
              type="button"
              data-testid="quote-score-ai-explain"
              onClick={() => openAiAssistant({
                prefill: '왜 이 견적의 종합 점수가 이렇게 나왔는지 설명해줘',
                autoSubmit: true,
                assessmentContext: { source: 'QUOTE_DRAFT_CURRENT', focusType: 'SCORE' }
              })}
              className="inline-flex items-center gap-1 rounded border border-[#f4c8b2] bg-[#fff5ef] px-2 py-1 font-black text-[#de6c2d] transition hover:border-[#de6c2d] hover:bg-white focus:outline-none focus-visible:ring-2 focus-visible:ring-[#f4c8b2]"
            >
              <Sparkles size={12} aria-hidden="true" />
              AI에게 설명
            </button>
          </span>
          ) : (
            <span className="font-bold text-slate-400">호환·성능·여유 종합 1000점</span>
          )}
        </div>
      {activeComparison && hasGhostScore ? (
        <CompositeGhostArc
          baseScore={compositeScore.score}
          compareScore={ghostScore as number}
          maxScore={compositeScore.maxScore}
          compareKey={activeComparison.partId}
        />
      ) : (
        <CompositeScoreGauge
          score={compositeScore}
          size="large"
          className="mx-auto"
          scoreTestId="quote-composite-score"
          gaugeTestId="quote-composite-score-gauge"
          delta={scoreDelta}
          deltaTestId="quote-composite-score-delta"
        />
      )}
      {compositeScore.requestFit ? (
        <div className={`mt-2 rounded px-2 py-1 text-[10px] font-black ${requestFitTone(compositeScore.requestFit.status)}`}>
          {requestFitLabel(compositeScore.requestFit)}
        </div>
      ) : null}
    </div>
  );

  // 셀프 견적의 한 화면 캔버스에서는 상세 카드 대신 결과 요약 바를 쓴다.
  // 점수·FPS·교체 선택·저장/구매 동작은 유지하고, 설명과 큰 게이지만 덜어 보드와 채팅 높이를 확보한다.
  const resolutionPicker = (
    <div className="flex shrink-0 gap-0.5 rounded-md border border-commerce-line bg-slate-50 p-0.5" role="group" aria-label="해상도 선택">
      {FPS_RESOLUTIONS.map((res) => (
        <button
          key={res.key}
          type="button"
          data-testid={`fps-res-${res.key}`}
          aria-pressed={resKey === res.key}
          onClick={() => setResKey(res.key)}
          className={`rounded px-2.5 py-1 text-xs font-black transition ${
            resKey === res.key ? 'bg-white text-commerce-ink shadow-sm' : 'text-slate-400 hover:text-slate-600'
          }`}
        >
          {res.label}
        </button>
      ))}
    </div>
  );

  const performanceSpotlight = spotlightRequestKey && activeComparison?.requestKey === spotlightRequestKey && hasGhostScore ? (
    <PerformanceComparisonSpotlight
      open
      requestKey={spotlightRequestKey}
      categoryLabel={PART_CATEGORY_LABELS[activeComparison.category]}
      currentPartName={currentPart?.name ?? `현재 ${PART_CATEGORY_LABELS[activeComparison.category]}`}
      targetPartName={activeComparison.name}
      baseScore={compositeScore.score}
      compareScore={ghostScore as number}
      maxScore={compositeScore.maxScore}
      beforeTotalPrice={spotlightPriceComparison?.before}
      afterTotalPrice={spotlightPriceComparison?.after}
      gameLabel={game.label}
      resolutionLabel={resolution.label}
      baseFps={isSpotlightFpsReady ? avg : undefined}
      compareFps={isSpotlightFpsReady ? compareAvg : undefined}
      fpsComparable={isSpotlightFpsReady && isCompareComparable}
      fpsLoading={isSpotlightFpsLoading}
      onDismiss={dismissSpotlight}
    />
  ) : null;

  if (compact) {
    const resultAvg = activeComparison && hasCompareAvg ? compareAvg : hasAvg ? animatedAvg : null;
    const resultLabel = activeComparison && hasCompareAvg ? '변경 예상' : '현재 예상';

    return (
      <div
        data-testid="quote-performance-grid"
        className="rounded-lg border border-commerce-line bg-white px-3 py-2 lg:min-h-[106px]"
      >
        <div className="grid gap-3 lg:grid-cols-[calc(clamp(256px,18vw,320px)-13px)_minmax(0,1fr)] lg:items-center">
          <div data-testid="quote-performance-score-column" className={`flex items-center justify-center border-b border-commerce-line pb-1.5 lg:border-b-0 lg:border-r lg:pb-0 lg:pr-2 ${
            activeComparison ? 'min-h-[88px]' : 'min-h-[80px]'
          }`}>
            <div className="w-full min-w-0">
              <div className="flex flex-nowrap items-center justify-between gap-2 text-[9px] font-black">
                <span className="flex shrink-0 items-center gap-0.5">
                  <span data-testid="quote-composite-score-title" className="whitespace-nowrap text-slate-600">종합 점수</span>
                  <HelpTip
                    label="종합 점수 설명"
                    text="현재 견적의 호환성·성능·전력 여유를 종합해 1000점 만점으로 계산한 점수입니다. 부품을 바꾸면 점수 변화를 바로 보여줍니다."
                    placement="top"
                    align="left"
                    overlay
                  />
                </span>
                {hasWorkspace ? (
                  <span className="flex min-w-0 items-center gap-1">
                    <span className="truncate text-slate-400">호환·성능·여유 종합 1000점</span>
                    <button
                      type="button"
                      data-testid="quote-score-ai-explain"
                      title="AI에게 종합 점수 설명 요청"
                      aria-label="AI에게 종합 점수 설명 요청"
                      onClick={() => openAiAssistant({
                        prefill: '왜 이 견적의 종합 점수가 이렇게 나왔는지 설명해줘',
                        autoSubmit: true,
                        assessmentContext: { source: 'QUOTE_DRAFT_CURRENT', focusType: 'SCORE' }
                      })}
                      className="grid h-5 w-5 shrink-0 place-items-center rounded border border-[#f4c8b2] bg-[#fff5ef] text-[#de6c2d] transition hover:border-[#de6c2d] hover:bg-white focus:outline-none focus-visible:ring-2 focus-visible:ring-[#f4c8b2]"
                    >
                      <Sparkles size={10} aria-hidden="true" />
                    </button>
                  </span>
                ) : (
                  <span className="truncate text-slate-400">호환·성능·여유 종합 1000점</span>
                )}
              </div>
              {activeComparison && hasGhostScore ? (
                <CompactCompositeGhostArc
                  baseScore={compositeScore.score}
                  compareScore={ghostScore as number}
                  maxScore={compositeScore.maxScore}
                  compareKey={activeComparison.partId}
                />
              ) : (
                <CompositeScoreGauge
                  score={compositeScore}
                  size="compact"
                  className="mx-auto"
                  scoreTextClassName="text-amber-600"
                  showLabel={false}
                  scoreTestId="quote-composite-score"
                  gaugeTestId="quote-composite-score-gauge"
                  delta={scoreDelta}
                  deltaTestId="quote-composite-score-delta"
                />
              )}
            </div>
          </div>

          <div data-testid="quote-fps-section" className="min-w-0 py-1">
            {(() => {
              const resolutionPicker = (
                <div className="flex shrink-0 gap-0.5 rounded-md border border-commerce-line bg-slate-50 p-0.5" role="group" aria-label="해상도 선택">
                  {FPS_RESOLUTIONS.map((res) => (
                    <button
                      key={res.key}
                      type="button"
                      data-testid={`fps-res-${res.key}`}
                      aria-pressed={resKey === res.key}
                      onClick={() => setResKey(res.key)}
                      className={`rounded px-2.5 py-1 text-xs font-black transition ${
                        resKey === res.key ? 'bg-white text-commerce-ink shadow-sm' : 'text-slate-400 hover:text-slate-600'
                      }`}
                    >
                      {res.label}
                    </button>
                  ))}
                </div>
              );

              // 비교 모드의 조작부는 아래 좌우 본문 섹션에서만 렌더링한다.
              if (activeComparison && hasWorkspace && onStartComparison) {
                return null;
              }

              return (
                <div className={`flex items-start justify-between gap-2 ${activeComparison ? 'flex-wrap' : 'flex-nowrap'}`}>
                  <div className="flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5">
                    <span className="shrink-0 text-sm font-black text-slate-600">
                      {activeComparison ? '가격·성능 향상' : '게임 예상 성능'}
                    </span>
                    {!activeComparison && hasGpu ? (
                      resultAvg !== null ? (
                        <span className="truncate text-xs font-bold text-slate-500">
                          {game.label} · {resolution.label} · {resultLabel}
                          <strong data-testid="fps-avg" className="ml-1 text-xl font-black text-commerce-ink">
                            {Math.round(resultAvg)} FPS
                          </strong>
                        </span>
                      ) : (
                        <span className="truncate text-xs font-bold text-slate-400">
                          {isFetching ? '성능을 계산하고 있어요' : '참고 자료 없음'}
                        </span>
                      )
                    ) : !activeComparison ? (
                      <span className="truncate text-xs font-bold text-slate-400">GPU를 담으면 표시됩니다</span>
                    ) : null}
                  </div>

                  <div className="flex shrink-0 flex-nowrap items-center justify-end gap-2">
                    {hasWorkspace && onStartComparison ? (
                      <CandidateCombo
                        perfItems={perfItems}
                        activeComparison={activeComparison}
                        onStartComparison={onStartComparison}
                        onClearComparison={onClearComparison}
                        compact
                        category={compareCategory}
                        onCategoryChange={setCompareCategory}
                      />
                    ) : null}
                    {resolutionPicker}
                  </div>
                </div>
              );
            })()}

            {activeComparison ? (
              <div className="mt-1.5 grid min-w-0 gap-2 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
                <section className="min-w-0">
                  <div className="mb-1.5 flex min-w-0 items-center justify-between gap-2">
                    <span className="shrink-0 text-sm font-black text-slate-600">가격·성능 향상</span>
                    <PerfCategoryToggle
                      innerRef={compareToggleRef}
                      category={compareCategory}
                      compact
                      onSelect={(pickerCategory) => {
                        setCompareCategory(pickerCategory);
                        if (activeComparison.category !== pickerCategory) {
                          onClearComparison?.();
                        }
                      }}
                    />
                  </div>
                  {isCompareReady ? (
                    <>
                      <div data-testid="cost-effect-block" className="perf-block-in rounded-md border border-commerce-line bg-slate-50/60 px-2 py-1.5">
                        <CostEffectBars
                          currentPrice={currentPart?.currentPrice}
                          targetPrice={activeComparison.price}
                          baseAvg={avg}
                          compareAvg={compareAvg}
                          perfComparable={isCompareComparable}
                        />
                      </div>
                      <div className="perf-block-in mt-1 flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5">
                        <CostEffectEmphasis {...costEffectDisplay(currentPart?.currentPrice, activeComparison.price)} compact />
                        <span data-testid="cost-effect-fps" className="truncate text-[11px] font-bold text-slate-500">
                          예상 FPS {fpsRangeText(avg, hasLow ? low : undefined)} → {fpsRangeText(compareAvg, hasCompareLow ? compareLow : undefined)}
                        </span>
                      </div>
                    </>
                  ) : isCompareLoading ? (
                    <div className="h-[58px] animate-pulse rounded-md bg-slate-100" />
                  ) : (
                    <div data-testid="cost-effect-empty" className="rounded-md border border-dashed border-slate-200 bg-slate-50/40 px-2 py-1.5">
                      <EffectBar testId="effect-bar-price" label="가격" percent={null} scale={EFFECT_SCALE_MIN} barClass="bg-slate-300" textClass="text-slate-400" />
                      <div className="mt-1">
                        <EffectBar testId="effect-bar-perf" label="성능" percent={null} scale={EFFECT_SCALE_MIN} barClass="bg-slate-300" textClass="text-slate-400" />
                      </div>
                    </div>
                  )}
                </section>

                <section className="min-w-0 border-t border-commerce-line pt-1.5 lg:border-l lg:border-t-0 lg:pl-2 lg:pt-0">
                  <div className="mb-1.5 flex min-w-0 flex-nowrap items-center justify-between gap-2">
                    {onStartComparison ? (
                      <CandidateCombo
                        perfItems={perfItems}
                        activeComparison={activeComparison}
                        onStartComparison={onStartComparison}
                        onClearComparison={onClearComparison}
                        compact
                        hideToggle
                        category={compareCategory}
                        onCategoryChange={setCompareCategory}
                        keepOpenRef={compareToggleRef}
                      />
                    ) : null}
                    {resolutionPicker}
                    <div className="ml-auto flex shrink-0 items-center justify-end gap-1">
                      {onApplyComparison ? (
                        <button
                          type="button"
                          data-testid="perf-apply-replace"
                          disabled={isApplying}
                          onClick={() => void applyComparison()}
                          className="whitespace-nowrap rounded bg-[#de6c2d] px-2 py-1.5 text-[10px] font-black text-white transition hover:bg-[#c45c22] disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          {isApplying ? '교체 중…' : '교체해 담기'}
                        </button>
                      ) : null}
                      {onClearComparison ? (
                        <button
                          type="button"
                          data-testid="compare-clear"
                          onClick={onClearComparison}
                          className="whitespace-nowrap rounded border border-commerce-line bg-white px-2 py-1.5 text-[10px] font-black text-slate-600"
                        >
                          비교 해제
                        </button>
                      ) : null}
                    </div>
                  </div>
                  <div className="flex min-w-0 items-center gap-2">
                    <div className="flex shrink-0 gap-1" role="group" aria-label="게임 선택">
                      {FPS_GAMES.map((g) => (
                        <button
                          key={g.key}
                          type="button"
                          data-testid={`fps-game-${g.key}`}
                          aria-pressed={gameKey === g.key}
                          onClick={() => setGameKey(g.key)}
                          className={`rounded-full border px-2.5 py-1 text-xs font-black transition ${
                            gameKey === g.key
                              ? 'border-[#de6c2d] bg-[#de6c2d] text-white'
                              : 'border-commerce-line bg-white text-slate-500 hover:border-[#de6c2d]'
                          }`}
                        >
                          {g.label}
                        </button>
                      ))}
                    </div>
                  </div>
                  {isCompareReady ? (
                    <>
                      <div className="mt-1.5 grid grid-cols-[auto_1fr] items-center gap-x-2 gap-y-1">
                        <span className="text-xs font-black text-slate-500">FPS</span>
                        <div className="flex items-baseline gap-1 font-black leading-none">
                          <span data-testid="fps-avg" className="text-base text-slate-400">{Math.round(avg)}</span>
                          <span className="text-xs text-slate-400">→</span>
                          <span data-testid="fps-compare-avg" className="text-lg text-brand-blue">{Math.round(compareAvg)}</span>
                          {isCompareComparable ? (
                            <span data-testid="fps-compare-delta" className={`rounded-full border px-1 py-0.5 text-[10px] ${deltaBadgeTone(percentDelta(avg, compareAvg))}`}>
                              {formatSignedPercent(percentDelta(avg, compareAvg))}
                            </span>
                          ) : null}
                        </div>
                        <span className="text-[10px] font-bold text-slate-400">기존</span>
                        <div className="h-1.5 overflow-hidden rounded-full bg-slate-100">
                          <div className="h-full rounded-full bg-slate-400" style={{ width: `${Math.max(3, fpsPercent(avg))}%` }} />
                        </div>
                        <span className="text-[10px] font-black text-brand-blue">변경</span>
                        <div className="h-1.5 overflow-hidden rounded-full bg-slate-100">
                          <div className="perf-bar-grow h-full rounded-full bg-brand-blue" style={{ width: `${Math.max(3, fpsPercent(compareAvg))}%` }} />
                        </div>
                      </div>
                      {isCompareConditionMismatch ? (
                        <CompareConditionNotice baseEvidence={evidence} compareEvidence={compareEvidence} />
                      ) : null}
                    </>
                  ) : (
                    <div className="mt-1.5 text-xs font-bold text-slate-400">
                      {isCompareLoading ? '비교 성능을 계산하고 있어요' : '변경 조합의 참고 자료가 없습니다'}
                    </div>
                  )}
                </section>
              </div>
            ) : (
              <div className="mt-2 min-w-0">
                <div className="flex min-w-0 items-center gap-1.5 overflow-hidden" role="group" aria-label="게임 선택">
                  {FPS_GAMES.map((g) => (
                    <button
                      key={g.key}
                      type="button"
                      data-testid={`fps-game-${g.key}`}
                      aria-pressed={gameKey === g.key}
                      onClick={() => setGameKey(g.key)}
                      className={`rounded-full border px-2.5 py-1 text-xs font-black transition ${
                        gameKey === g.key
                          ? 'border-[#de6c2d] bg-[#de6c2d] text-white'
                          : 'border-commerce-line bg-white text-slate-500 hover:border-[#de6c2d]'
                      }`}
                    >
                      {g.label}
                    </button>
                  ))}
                </div>
                <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-slate-100">
                  <div
                    className="h-full rounded-full bg-gradient-to-r from-brand-blue to-emerald-300 transition-[width] duration-500"
                    style={{ width: `${resultAvg === null ? 0 : Math.min(100, (resultAvg / FPS_CAP) * 100)}%` }}
                  />
                </div>
              </div>
            )}
          </div>

          {checkoutActions ? (
            <div className="flex flex-wrap items-center justify-end gap-2 border-t border-commerce-line pt-1.5 lg:border-l lg:border-t-0 lg:pl-2 lg:pt-0">
              <div data-testid="quote-checkout-actions" className="flex flex-wrap justify-end gap-2">
                {checkoutActions}
              </div>
            </div>
          ) : null}
        </div>
        {applyError ? (
          <div data-testid="perf-apply-error" className="mt-1 rounded-md border border-red-100 bg-red-50/70 px-2.5 py-1 text-[11px] font-bold text-red-600">
            {applyError}
          </div>
        ) : null}
        {performanceSpotlight}
      </div>
    );
  }

  // 게임 예상 성능 — 원래 수평 막대 스타일(큰 FPS 숫자 + 그라데이션 바 + 1% low 마커 + 체감 라벨).
  // 비교 중엔 단일 모드와 같은 결의 0→값 게이지 바 2줄(기존 회색/변경 파랑)로 두 조합을 나란히 본다.
  const fpsSection = hasGpu ? (
    <div data-testid="quote-fps-section">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <span className="text-[11px] font-black text-slate-600">게임 예상 성능 <span className="text-slate-400">(참고)</span></span>
        <div className="flex flex-wrap items-center justify-end gap-2">
          {hasWorkspace && onStartComparison ? (
            <CandidateCombo
              perfItems={perfItems}
              activeComparison={activeComparison}
              onStartComparison={onStartComparison}
              onClearComparison={onClearComparison}
            />
          ) : (
            <span className="text-[10px] font-bold text-slate-400">공개 자료 기준 참고치</span>
          )}
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
      </div>

      {/* 게임 선택: 컴팩트 칩 버튼 — 유일한 게임 선택기(비교 중에도 동작). */}
      <div className="mb-2.5 flex flex-wrap gap-1.5" role="group" aria-label="게임 선택">
        {FPS_GAMES.map((g) => (
          <button
            key={g.key}
            type="button"
            data-testid={`fps-game-${g.key}`}
            aria-pressed={gameKey === g.key}
            onClick={() => setGameKey(g.key)}
            className={`rounded-full border px-2.5 py-1 text-[11px] font-black transition ${
              gameKey === g.key
                ? 'border-[#de6c2d] bg-[#de6c2d] text-white'
                : 'border-commerce-line bg-white text-slate-600 hover:border-[#de6c2d]'
            }`}
          >
            {g.label}
          </button>
        ))}
      </div>

      {activeComparison && !isCompareReady && !isCompareLoading ? (
        <div data-testid="fps-compare-empty" className="mb-2.5 rounded-md border border-dashed border-slate-300 bg-white px-2.5 py-2 text-[11px] font-bold text-slate-500">
          변경 조합의 공개 참고 자료가 없어요 — 지금 담긴 조합 기준으로만 보여드려요.
        </div>
      ) : null}

      {isFetching && !evidence ? (
        <div className="h-16 animate-pulse rounded-lg bg-slate-100" />
      ) : hasAvg ? (
        <div data-testid="fps-result" className="rounded-lg border border-commerce-line bg-slate-50/60 p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-[11px] font-black text-slate-600">
              {game.label} · {evidence?.resolution ?? resolution.label}
              {presetLabel(evidence?.graphicsPreset) ? ` · ${presetLabel(evidence?.graphicsPreset)}` : ''}
            </span>
            <span className="text-[10px] font-black">
              <span className={feelTone(avg).text}>{feelLabel(avg)}</span>
              {isCompareComparable && feelLabel(avg) !== feelLabel(compareAvg) ? (
                <span className={feelTone(compareAvg).text}> → {feelLabel(compareAvg)}</span>
              ) : null}
            </span>
          </div>

          {isCompareReady && activeComparison ? (
            <>
              {/* 기존 → 변경 숫자 + 델타 배지(팝인 모션 유지) — 배지는 같은 측정 조건일 때만 확정 표기한다. */}
              <div className="mt-1.5 flex flex-wrap items-baseline gap-1.5">
                <span data-testid="fps-avg" className="text-xl font-black text-slate-500">{Math.round(avg)}</span>
                <span className="text-sm font-black text-slate-400">→</span>
                <span data-testid="fps-compare-avg" className="text-2xl font-black text-brand-blue">{Math.round(compareAvg)}</span>
                {isCompareComparable ? (
                  <span
                    key={activeComparison.partId}
                    data-testid="fps-compare-delta"
                    className={`perf-pop-in rounded-full border px-1.5 py-0.5 text-[10px] font-black ${deltaBadgeTone(percentDelta(avg, compareAvg))}`}
                  >
                    {formatSignedPercent(percentDelta(avg, compareAvg))}
                  </span>
                ) : null}
                <span className="text-[11px] font-bold text-slate-500">FPS 평균 (참고)</span>
              </div>
              {/* 기존/변경 평균 FPS 게이지 바 2줄 — 단일 모드처럼 0→값 채움, 1% 최저는 채움 위 눈금. */}
              <div data-testid="fps-compare-gauges" className="mt-2 space-y-1.5">
                <FpsCompareGaugeBar label="기존" avg={avg} low={hasLow ? low : undefined} tone="base" />
                <FpsCompareGaugeBar label="변경" avg={compareAvg} low={hasCompareLow ? compareLow : undefined} tone="changed" />
              </div>
              {isCompareConditionMismatch ? (
                <CompareConditionNotice baseEvidence={evidence} compareEvidence={compareEvidence} />
              ) : null}
            </>
          ) : (
            <>
              <div className="mt-1.5 flex items-baseline gap-1.5">
                <span data-testid="fps-avg" className="text-2xl font-black text-commerce-ink">{Math.round(animatedAvg)}</span>
                <span className="text-[11px] font-bold text-slate-500">FPS 평균 (참고)</span>
              </div>
              <FpsGauge avg={avg} low={hasLow ? low : undefined} />
            </>
          )}

          <div className="mt-2 flex flex-wrap items-center justify-between gap-1.5 text-[10px]">
            <span className="font-bold text-slate-500">
              {isCompareReady
                ? hasLow || hasCompareLow
                  ? '눈금은 순간 최저(하위 1% 평균) 지점입니다'
                  : '최저값 자료 없음'
                : hasLow
                  ? `최저 약 ${Math.round(low)} FPS (하위 1% 평균)`
                  : '최저값 자료 없음'}
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

    </div>
  ) : (
    <div data-testid="fps-no-gpu" className="rounded-lg border border-dashed border-slate-300 bg-white p-4 text-center text-[11px] font-bold text-slate-500">
      그래픽카드를 담으면 게임 예상 성능을 보여드려요.
    </div>
  );

  return (
    <>
      <div
        data-testid="quote-performance-grid"
        className={`grid gap-3 lg:items-start ${
          hasWorkspace ? 'lg:grid-cols-[minmax(0,0.95fr)_minmax(0,1.05fr)]' : 'lg:grid-cols-[minmax(260px,38%)_minmax(0,1fr)]'
        }`}
      >
        {/* 왼쪽 카드 하나 = [종합점수 아크 | 가격·성능 향상 그래프] 가로 배치(모바일은 세로 스택) — 구분은 얇은 디바이더만.
            작업창이 없는 사용처(저장 견적 등)는 향상 그래프 없이 종합점수만 둔다. */}
        <div className="rounded-lg border border-commerce-line bg-white p-2.5">
          {hasWorkspace ? (
            <div className="grid gap-3 sm:grid-cols-2">
              {compositeCard}
              {/* 칸 전체를 flex 열로 만들고 헤더 아래 콘텐츠(막대 + 추가 비용 강조 + 예상 FPS 줄)를
                  grow + justify-center로 칸 높이 기준 세로 중앙에 앉힌다 — 빈 상태도 동일. */}
              <div data-testid="price-effect-panel" className="flex flex-col border-t border-commerce-line pt-3 sm:border-l sm:border-t-0 sm:pl-3 sm:pt-0">
                <div className="mb-1 flex flex-wrap items-center justify-between gap-2 text-[11px]">
                  <span className="font-black text-slate-600">가격·성능 향상</span>
                </div>
                <div className="flex min-h-0 grow flex-col justify-center">
                  {isCompareReady && activeComparison ? (
                    <>
                      {/* 가격 변화 % vs 성능(FPS 평균) 변화 % — 0 기준선을 가운데 둔 분기형 막대. */}
                      <div data-testid="cost-effect-block" className="perf-block-in rounded-lg border border-commerce-line bg-slate-50/60 p-2.5">
                        <CostEffectBars
                          currentPrice={currentPart?.currentPrice}
                          targetPrice={activeComparison.price}
                          baseAvg={avg}
                          compareAvg={compareAvg}
                          perfComparable={isCompareComparable}
                        />
                      </div>
                      {/* 추가 비용이 이 블록의 결론 — 막대 아래 가장 큰 숫자로 강조하고, 예상 FPS는 보조 텍스트로 받친다. */}
                      <div className="perf-block-in mt-2">
                        <CostEffectEmphasis {...costEffectDisplay(currentPart?.currentPrice, activeComparison.price)} />
                        <div data-testid="cost-effect-fps" className="mt-1 text-[10px] font-bold text-slate-500">
                          예상 FPS {fpsRangeText(avg, hasLow ? low : undefined)} → {fpsRangeText(compareAvg, hasCompareLow ? compareLow : undefined)}
                        </div>
                      </div>
                    </>
                  ) : isCompareLoading ? (
                    <div className="h-24 animate-pulse rounded-lg bg-slate-100" />
                  ) : (
                    <>
                      {/* 빈 구조 고정: 비교 미선택(또는 자료 없음)에도 중앙 기준선 트랙·라벨·추가 비용 자리가 그대로 보여
                          자리가 흔들리지 않고, 값 영역은 미묘한 placeholder만 둔다. 후보를 고르면 grow 모션으로 채워진다. */}
                      <div data-testid="cost-effect-empty" className="rounded-lg border border-dashed border-slate-200 bg-slate-50/40 p-2.5">
                        <div className="space-y-1.5">
                          <EffectBar testId="effect-bar-price" label="가격" percent={null} scale={EFFECT_SCALE_MIN} barClass="bg-slate-300" textClass="text-slate-400" />
                          <EffectBar testId="effect-bar-perf" label="성능" percent={null} scale={EFFECT_SCALE_MIN} barClass="bg-slate-300" textClass="text-slate-400" />
                        </div>
                      </div>
                    </>
                  )}
                </div>
              </div>
            </div>
          ) : (
            compositeCard
          )}
        </div>

        {/* 오른쪽 열: 게임 예상 성능 작업창 — 선택기는 헤더 콤보로 올라갔고, 여기는 데이터 시각화만 남는다.
            "교체 비교 · A → B" 텍스트 배너는 제거 — 후보명은 헤더 콤보가 이미 보여줘 중복이었다. */}
        {hasWorkspace ? (
          <div data-testid="perf-compare-workspace" className="rounded-lg border border-commerce-line bg-white p-2.5">
            {fpsSection}
            {checkoutActions ? (
              <div data-testid="quote-checkout-actions" className="mt-3 flex flex-wrap justify-end gap-2 border-t border-commerce-line pt-3">
                {checkoutActions}
              </div>
            ) : null}
          </div>
        ) : (
          <div className="rounded-lg border border-commerce-line bg-white p-2.5">
            {fpsSection}
            {checkoutActions ? (
              <div data-testid="quote-checkout-actions" className="mt-3 flex flex-wrap justify-end gap-2 border-t border-commerce-line pt-3">
                {checkoutActions}
              </div>
            ) : null}
          </div>
        )}
      </div>

      {/* 액션 줄: 비교 활성 시에만 패널 하단 전체 폭으로 등장 — 두 열(향상 폭·예상 성능)이 모두 이 비교의
          결과라서, 다 보고 난 뒤의 마지막 결정 단계로 한 줄을 깐다(모바일 스택에서도 데이터 아래 자연 위치). */}
      {hasWorkspace && activeComparison ? (
        <div data-testid="perf-action-row" className="perf-block-in mt-3 border-t border-commerce-line pt-3">
          {applyError ? (
            <div data-testid="perf-apply-error" className="mb-2 rounded-md border border-red-100 bg-red-50/70 px-2.5 py-1.5 text-[11px] font-bold text-red-600">
              {applyError}
            </div>
          ) : null}
          <div className="flex flex-wrap items-center gap-2">
            {onApplyComparison ? (
              <button
                type="button"
                data-testid="perf-apply-replace"
                disabled={isApplying}
                onClick={() => void applyComparison()}
                className="rounded bg-[#de6c2d] px-3 py-2 text-[11px] font-black text-white transition hover:bg-[#c45c22] disabled:cursor-not-allowed disabled:opacity-60"
              >
                {isApplying ? '교체해 담는 중…' : '이 제품으로 교체해 담기'}
              </button>
            ) : null}
            {onClearComparison ? (
              <button
                type="button"
                data-testid="compare-clear"
                onClick={onClearComparison}
                className="rounded border border-commerce-line bg-white px-2.5 py-2 text-[11px] font-black text-slate-600 transition hover:border-commerce-ink hover:text-commerce-ink"
              >
                비교 해제
              </button>
            ) : null}
          </div>
        </div>
      ) : null}
      {performanceSpotlight}
    </>
  );
}

// 교체 비교가 의미 있는 카테고리(벤치마크 근거가 있는 CPU/GPU)만 선택기에 노출한다.
const PERF_PICKER_CATEGORIES: Array<PerfCompareTarget['category']> = ['CPU', 'GPU'];

// 카드 헤더 줄의 한 줄 콤보: [CPU|GPU 토글] + [교체 후보 선택 ▾ 버튼] — 클릭하면 팝오버로 호환 후보 리스트를 겹쳐 띄운다.
// PASS/WARN은 선택 즉시 비교가 켜지고, FAIL은 숨기지 않고 회색 비활성 + 선택 불가 사유를 보여준다.
// CPU|GPU 카테고리 토글 — CandidateCombo 내부와 분리 배치(비교 헤더의 구분선 왼쪽) 양쪽에서 쓴다.
function PerfCategoryToggle({
  category,
  onSelect,
  compact = false,
  innerRef
}: {
  category: PerfCompareTarget['category'];
  onSelect: (category: PerfCompareTarget['category']) => void;
  compact?: boolean;
  innerRef?: MutableRefObject<HTMLDivElement | null>;
}) {
  return (
    <div ref={innerRef} className="flex shrink-0 gap-0.5 rounded-md border border-commerce-line bg-white p-0.5" role="group" aria-label="비교할 부품 종류 선택">
      {PERF_PICKER_CATEGORIES.map((pickerCategory) => (
        <button
          key={pickerCategory}
          type="button"
          data-testid={`perf-candidate-category-${pickerCategory}`}
          aria-pressed={category === pickerCategory}
          onClick={() => onSelect(pickerCategory)}
          className={`rounded font-black transition ${compact ? 'px-2.5 py-1 text-xs' : 'px-2.5 py-1 text-[10px]'} ${
            category === pickerCategory ? 'bg-[#de6c2d] text-white shadow-sm' : 'text-slate-400 hover:text-slate-600'
          }`}
        >
          {PART_CATEGORY_LABELS[pickerCategory]}
        </button>
      ))}
    </div>
  );
}

function CandidateCombo({
  perfItems,
  activeComparison,
  onStartComparison,
  onClearComparison,
  compact = false,
  category: controlledCategory,
  onCategoryChange,
  hideToggle = false,
  keepOpenRef
}: {
  perfItems: PerfItem[];
  activeComparison: PerfCompareTarget | null;
  onStartComparison: (target: PerfCompareTarget) => void;
  onClearComparison?: () => void;
  compact?: boolean;
  // 토글을 밖(비교 헤더의 구분선 왼쪽)에 두는 분리 배치용 — 카테고리를 부모가 소유한다.
  category?: PerfCompareTarget['category'];
  onCategoryChange?: (category: PerfCompareTarget['category']) => void;
  hideToggle?: boolean;
  // 분리 배치된 토글 영역 — 여기 클릭은 팝오버를 닫지 않는다(한 몸이던 때와 같은 동작 유지).
  keepOpenRef?: MutableRefObject<HTMLDivElement | null>;
}) {
  const [internalCategory, setInternalCategory] = useState<PerfCompareTarget['category']>(activeComparison?.category ?? 'GPU');
  const category = controlledCategory ?? internalCategory;
  const setCategory = (next: PerfCompareTarget['category']) => {
    if (onCategoryChange) {
      onCategoryChange(next);
    } else {
      setInternalCategory(next);
    }
  };
  const [isPickerOpen, setIsPickerOpen] = useState(false);
  // 콤보 한 줄(토글+선택 버튼+팝오버)을 한 단위로 본다 — 팝오버가 열린 채 카테고리를 토글해도 닫히지 않는다.
  const comboRef = useRef<HTMLDivElement | null>(null);

  // 외부(AI 변경 미리보기 이벤트 등)에서 비교가 켜지면 선택기도 그 카테고리를 따라간다.
  const comparisonCategory = activeComparison?.category;
  useEffect(() => {
    if (comparisonCategory) {
      setCategory(comparisonCategory);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [comparisonCategory]);

  // 팝오버는 바깥 클릭·Escape로 닫힌다 — 후보를 고르면 즉시 닫히고 아래 예상 성능으로 시선이 이어진다.
  useEffect(() => {
    if (!isPickerOpen) return;
    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as Node;
      if (comboRef.current && !comboRef.current.contains(target) && !keepOpenRef?.current?.contains(target)) {
        setIsPickerOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setIsPickerOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [isPickerOpen, keepOpenRef]);

  const categoryCurrentPart = perfItems.find((item) => item.category === category);
  // 아코디언과 같은 GET /api/parts(QUOTE_DRAFT_CURRENT, 호환 정렬)를 재사용한다 — 팝오버가 열릴 때만 조회.
  // keepPreviousData를 쓰지 않는다 — 토글 직후 이전 카테고리 후보가 잠깐 남으면 엉뚱한 비교를 시작할 수 있다.
  const candidateQuery = useQuery({
    queryKey: ['parts', 'perf-compare-candidates', category],
    queryFn: () => listParts({
      category,
      page: 0,
      // 팝오버는 페이지네이션이 없으므로 서버 최대치(100)로 한 번에 받는다 —
      // size 20이면 호환 정렬의 가격 오름차순 탓에 저가 후보에서 잘려 5080/5090이 안 보였다.
      size: 100,
      sort: 'compatibility',
      compatibilitySource: 'QUOTE_DRAFT_CURRENT'
    }),
    enabled: isPickerOpen && Boolean(categoryCurrentPart),
    staleTime: 30_000
  });
  const candidates = candidateQuery.data?.items ?? [];
  const categoryComparison = activeComparison?.category === category ? activeComparison : null;

  return (
    // 헤더 오른쪽 끝에 붙는다 — 좁은 화면에서 줄바꿈되면 자기 줄에서 오른쪽 정렬을 유지한다.
    <div ref={comboRef} className={`flex min-w-0 items-center justify-end gap-1.5 ${hideToggle ? 'flex-1' : 'grow sm:grow-0'}`}>
      {hideToggle ? null : (
        <PerfCategoryToggle
          category={category}
          compact={compact}
          onSelect={(pickerCategory) => {
            setCategory(pickerCategory);
            if (activeComparison && activeComparison.category !== pickerCategory) {
              onClearComparison?.();
            }
          }}
        />
      )}
      <div className={`relative ${hideToggle ? 'min-w-0 flex-1' : compact ? 'w-44 sm:w-52' : 'w-44 sm:w-56'}`}>
        <button
          type="button"
          data-testid="perf-candidate-select"
          aria-expanded={isPickerOpen}
          aria-haspopup="true"
          onClick={() => setIsPickerOpen((open) => !open)}
          className={`flex w-full items-center justify-between gap-2 rounded-md border border-commerce-line bg-white text-left font-black text-commerce-ink transition hover:border-brand-blue ${
            compact ? 'px-2.5 py-1.5 text-xs' : 'px-2.5 py-1.5 text-[11px]'
          }`}
        >
          <span className={`truncate ${categoryComparison ? '' : 'text-slate-400'}`}>
            {categoryComparison ? categoryComparison.name : '교체 후보 선택'}
          </span>
          <ChevronDown className={`h-3.5 w-3.5 shrink-0 text-slate-400 transition-transform ${isPickerOpen ? 'rotate-180' : ''}`} aria-hidden="true" />
        </button>

        {isPickerOpen ? (
          // 팝오버는 버튼 오른쪽 끝에 정렬해 헤더 아래로 겹쳐 뜬다 — 본문 데이터 시각화를 밀어내지 않는다.
          <div
            data-testid="perf-candidate-popover"
            className="perf-popover-in absolute right-0 top-full z-30 mt-1 w-[min(20rem,calc(100vw-3rem))] rounded-lg border border-commerce-line bg-white p-2 shadow-xl"
          >
            {categoryCurrentPart ? (
              <div data-testid="perf-candidate-current" className="mb-1.5 truncate rounded-md bg-slate-50 px-2 py-1.5 text-[10px] font-bold text-slate-500">
                지금 담긴 부품 · <span className="font-black text-slate-600">{categoryCurrentPart.name ?? PART_CATEGORY_LABELS[category]}</span>
              </div>
            ) : null}
            {!categoryCurrentPart ? (
              <div data-testid="perf-candidate-picker-empty" className="rounded-md border border-dashed border-slate-300 bg-white px-2.5 py-3 text-center text-[11px] font-bold text-slate-500">
                {withObjectParticle(PART_CATEGORY_LABELS[category])} 먼저 담으면 교체 비교를 할 수 있어요.
              </div>
            ) : candidateQuery.isLoading ? (
              <div className="px-2 py-3 text-center text-[11px] font-bold text-slate-400">후보를 불러오는 중</div>
            ) : candidateQuery.isError ? (
              <div className="px-2 py-3 text-center text-[11px] font-bold text-red-500">후보를 불러오지 못했습니다</div>
            ) : candidates.length === 0 ? (
              <div className="px-2 py-3 text-center text-[11px] font-bold text-slate-400">표시할 후보가 없습니다</div>
            ) : (
              <div className="max-h-64 space-y-1 overflow-y-auto pr-1">
                {candidates.map((part, index) => {
                  const status = part.compatibility?.status;
                  const isFail = status === 'FAIL';
                  const isCurrent = part.id === categoryCurrentPart.partId;
                  const isSelected = categoryComparison?.partId === part.id;
                  const statusLabel = isCurrent
                    ? '지금 담긴 부품'
                    : isSelected
                      ? '비교 중'
                      : candidateStatusLabel(part.compatibility);
                  return (
                    <button
                      key={part.id}
                      type="button"
                      data-testid={`perf-candidate-option-${index}`}
                      disabled={isFail || isCurrent}
                      aria-pressed={isSelected}
                      onClick={() => {
                        onStartComparison({ category, partId: part.id, name: part.name, price: part.price });
                        setIsPickerOpen(false);
                      }}
                      className={`w-full rounded-md border bg-white px-2.5 py-2 text-left text-[11px] transition disabled:cursor-not-allowed ${
                        isFail
                          ? 'border-slate-200 opacity-55'
                          : isCurrent
                            ? 'border-emerald-200 bg-emerald-50/60'
                            : isSelected
                              ? 'border-brand-blue ring-2 ring-blue-100'
                              : 'border-commerce-line hover:border-brand-blue'
                      }`}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <span className="line-clamp-2 min-w-0 font-black text-commerce-ink">{part.name}</span>
                        <span className="shrink-0 font-black text-commerce-ink">{part.price.toLocaleString()}원</span>
                      </div>
                      <div className="mt-1 flex items-center justify-between gap-2 text-[10px]">
                        <span className="truncate text-slate-500">{part.manufacturer ?? '제조사 미상'}</span>
                        {statusLabel ? (
                          <span className={`shrink-0 font-black ${candidateStatusTone(status, isCurrent, isSelected)}`}>
                            {statusLabel}
                          </span>
                        ) : null}
                      </div>
                      {isFail ? (
                        <div className="mt-1 text-[10px] font-bold text-red-500">
                          {part.compatibility?.summary || '현재 조합에는 장착할 수 없어요.'}
                        </div>
                      ) : null}
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        ) : null}
      </div>
    </div>
  );
}

// 호환 상태 → 사용자 언어 배지(서버 라벨 우선). 원어(PASS/WARN/FAIL)는 노출하지 않는다.
function candidateStatusLabel(compatibility?: PartCompatibility | null): string {
  if (compatibility?.status === 'PASS') return '';
  if (compatibility?.statusLabel) return compatibility.statusLabel;
  switch (compatibility?.status) {
    case 'WARN':
      return '간섭 주의';
    case 'FAIL':
      return '장착 불가';
    default:
      return '확인 전';
  }
}

function candidateStatusTone(status: PartCompatibility['status'] | undefined, isCurrent: boolean, isSelected: boolean): string {
  if (isCurrent) return 'text-emerald-700';
  if (isSelected) return 'text-brand-blue';
  if (status === 'PASS') return 'text-emerald-700';
  if (status === 'WARN') return 'text-amber-600';
  if (status === 'FAIL') return 'text-red-600';
  return 'text-slate-400';
}

// 드래프트 카테고리 문자열이 resolve 계약의 PartCategory인지 확인한다 — 미지 카테고리는 고스트 조합에서 제외.
function isPartCategory(category: string): category is PartCategory {
  return category in PART_CATEGORY_LABELS;
}

// compact 고스트 비교용 — CompositeScoreGauge size="compact"의 넓은 아크와 같은 폭으로 맞춘다.
const COMPACT_GHOST_ARC_PATH = 'M 8 112 A 102 86 0 0 1 212 112';
const COMPACT_GHOST_INNER_ARC_PATH = 'M 20 112 A 90 74 0 0 1 200 112';

// 한 화면용 비교 게이지 — 기준 점수는 바깥 회색 아크, 변경 점수는 안쪽 파란 아크에 그려
// 작은 카드에서도 두 값이 겹치지 않는다. 상세 게이지와 같은 숫자·델타 구조는 유지한다.
function CompactCompositeGhostArc({
  baseScore,
  compareScore,
  maxScore,
  compareKey
}: {
  baseScore: number;
  compareScore: number;
  maxScore: number;
  compareKey: string;
}) {
  const safeMax = Math.max(1, maxScore);
  const displayCompare = useAnimatedNumber(compareScore, baseScore);
  const basePercent = Math.max(0, Math.min(100, (Math.max(0, baseScore) / safeMax) * 100));
  const comparePercent = Math.max(0, Math.min(100, (Math.max(0, displayCompare) / safeMax) * 100));
  const delta = Math.round(compareScore) - Math.round(baseScore);

  return (
    <div
      data-testid="quote-composite-ghost-gauge"
      className="mx-auto w-[168px] text-center"
      aria-label={`종합 점수 기존 ${Math.round(baseScore).toLocaleString('ko-KR')}점 → 변경 ${Math.round(compareScore).toLocaleString('ko-KR')}점`}
    >
      <div className="relative">
        <svg
          className="h-[78px] w-full overflow-visible"
          viewBox="0 12 220 108"
          preserveAspectRatio="none"
          role="img"
          aria-hidden="true"
        >
          <path
            d={COMPACT_GHOST_ARC_PATH}
            fill="none"
            className="stroke-slate-200"
            strokeWidth={14}
            strokeLinecap="butt"
            pathLength={100}
          />
          <path
            d={COMPACT_GHOST_ARC_PATH}
            fill="none"
            className="stroke-slate-400"
            strokeWidth={6}
            strokeLinecap="butt"
            pathLength={100}
            strokeDasharray={`${basePercent} 100`}
          />
          <path
            d={COMPACT_GHOST_INNER_ARC_PATH}
            fill="none"
            className="stroke-brand-blue"
            strokeWidth={9}
            strokeLinecap="butt"
            pathLength={100}
            strokeDasharray={`${comparePercent} 100`}
          />
        </svg>
        <div className="absolute inset-x-0 top-10 z-10 flex justify-center">
          <span
            key={compareKey}
            data-testid="quote-composite-compare-delta"
            className={`perf-pop-in rounded-full border px-1.5 py-px text-[9px] font-black leading-none ${deltaBadgeTone(delta)}`}
          >
            {delta > 0 ? '+' : ''}{delta}점
          </span>
        </div>
      </div>
      <div className="mt-1 flex items-baseline justify-center gap-1 font-black leading-none">
        <span data-testid="quote-composite-ghost-base" className="text-sm text-slate-400">
          {Math.round(baseScore).toLocaleString('ko-KR')}
        </span>
        <span aria-hidden="true" className="text-xs text-slate-400">→</span>
        <span data-testid="quote-composite-compare-score" className="text-xl text-brand-blue">
          {Math.round(displayCompare).toLocaleString('ko-KR')}
        </span>
      </div>
      <div className="mt-1 flex items-center justify-between px-1 text-[8px] font-bold text-slate-400" aria-hidden="true">
        <span>0</span>
        <span>{safeMax.toLocaleString('ko-KR')}</span>
      </div>
    </div>
  );
}

// FPS를 체감 경험으로 — 그라데이션 트랙 위 체감 색 채움 + 1% low 마커(원래 수평 막대 스타일).
function FpsGauge({ avg, low }: { avg: number; low?: number }) {
  const avgPct = Math.max(3, fpsPercent(avg));
  const lowPct = low !== undefined ? fpsPercent(low) : null;
  return (
    <div data-testid="fps-gauge" className="relative mt-2 h-2.5 overflow-hidden rounded-full bg-gradient-to-r from-red-200 via-amber-200 to-emerald-200">
      {/* 평균 FPS 채움 — 등장 시 0→목표 폭(perf-bar-grow), 값 변화는 transition으로 따라간다. */}
      <div className={`perf-bar-grow h-full rounded-full ${feelTone(avg).bar}`} style={{ width: `${avgPct}%` }} />
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

function fpsPercent(fps: number) {
  return Math.min(100, Math.max(0, (fps / FPS_CAP) * 100));
}

// 기존/변경 조합의 평균 FPS를 단일 모드 FpsGauge처럼 0→값 채움 게이지 바로 나란히 —
// 기존은 회색(슬레이트), 변경은 파랑 채움, 1% 최저는 채움 위 눈금으로 남긴다(165 상한 스케일 공유).
function FpsCompareGaugeBar({ label, avg, low, tone }: { label: string; avg: number; low?: number; tone: 'base' | 'changed' }) {
  const avgPct = Math.max(3, fpsPercent(avg));
  const lowPct = low !== undefined ? fpsPercent(Math.min(low, avg)) : null;
  const barClass = tone === 'changed' ? 'bg-brand-blue' : 'bg-slate-400';
  return (
    <div
      data-testid={tone === 'changed' ? 'fps-compare-gauge-changed' : 'fps-compare-gauge-base'}
      className="grid grid-cols-[30px_1fr_88px] items-center gap-2 text-[11px] font-bold"
    >
      <span className={tone === 'changed' ? 'font-black text-brand-blue' : 'text-slate-500'}>{label}</span>
      <div className="relative h-2.5 overflow-hidden rounded-full bg-slate-100">
        {/* 등장 시 0→목표 폭으로 자라고(기존 먼저, 변경 120ms 스태거), 값 변화는 transition으로 따라간다. reduced-motion이면 즉시. */}
        <div className={`perf-bar-grow h-full rounded-full ${barClass}${tone === 'changed' ? ' perf-bar-stagger' : ''}`} style={{ width: `${avgPct}%` }} />
        {/* 1% low 눈금 — 채움 위에 실제 체감 하한을 표시한다(단일 모드 마커와 동일한 결). */}
        {lowPct !== null ? (
          <span
            aria-hidden="true"
            className="absolute top-1/2 h-3.5 w-0.5 -translate-y-1/2 rounded-full bg-slate-600"
            style={{ left: `${lowPct}%` }}
          />
        ) : null}
      </div>
      <span className="text-right text-slate-600">약 {Math.round(avg)} FPS</span>
    </div>
  );
}

// 분기형 막대 대칭 스케일: 두 지표 절대값의 최대치 기준, 최소 ±25% 범위 보장.
// 극단값(예: +260%)은 반폭 끝까지만 시각적으로 캡하고 숫자 라벨은 정확값을 유지한다.
const EFFECT_SCALE_MIN = 25;
const EFFECT_SCALE_CAP = 100;

// 가격·성능 향상: 가격 변화 %와 성능(FPS 평균) 변화 %를 나란히 — "돈을 더 내면 얼마나 좋아지나"를 한눈에.
// 비교는 음수(절감·성능 하락)일 수 있어 기준점 0을 트랙 가운데 둔 분기형 막대로 그린다.
// perfComparable=false(두 근거의 측정 조건이 다름)면 성능 ±%를 확정하지 않고 빈 값(—)으로 둔다.
function CostEffectBars({
  currentPrice,
  targetPrice,
  baseAvg,
  compareAvg,
  perfComparable = true
}: {
  currentPrice?: number;
  targetPrice: number;
  baseAvg: number;
  compareAvg: number;
  perfComparable?: boolean;
}) {
  const hasPrice = typeof currentPrice === 'number' && currentPrice > 0 && targetPrice > 0;
  const pricePercent = hasPrice ? percentDelta(currentPrice as number, targetPrice) : null;
  const perfPercent = perfComparable ? percentDelta(baseAvg, compareAvg) : null;
  const scale = Math.min(EFFECT_SCALE_CAP, Math.max(EFFECT_SCALE_MIN, Math.abs(pricePercent ?? 0), Math.abs(perfPercent ?? 0)));

  return (
    <div className="space-y-1.5">
      <EffectBar
        testId="effect-bar-price"
        label="가격"
        percent={pricePercent}
        scale={scale}
        barClass="bg-slate-400"
        textClass="text-slate-600"
      />
      <EffectBar
        testId="effect-bar-perf"
        label="성능"
        percent={perfPercent}
        scale={scale}
        barClass={perfPercent !== null && perfPercent > 0 ? 'bg-emerald-500' : perfPercent !== null && perfPercent < 0 ? 'bg-red-500' : 'bg-slate-300'}
        textClass={perfPercent !== null && perfPercent > 0 ? 'text-emerald-600' : perfPercent !== null && perfPercent < 0 ? 'text-red-600' : 'text-slate-500'}
        stagger
      />
    </div>
  );
}

// 추가 비용 강조 표시값 — 증가는 빨강, 절감은 에메랄드, 차이 없음·정보 없음은 슬레이트.
function costEffectDisplay(currentPrice: number | undefined, targetPrice: number): { value: string; tone: string; note?: string } {
  const hasPrice = typeof currentPrice === 'number' && currentPrice > 0 && targetPrice > 0;
  if (!hasPrice) return { value: '—', tone: 'text-slate-300', note: '기존 부품 가격 정보 없음' };
  const formatter = new Intl.NumberFormat('ko-KR');
  const priceDiff = targetPrice - (currentPrice as number);
  if (priceDiff > 0) return { value: `+${formatter.format(priceDiff)}원`, tone: 'text-red-600' };
  if (priceDiff < 0) return { value: `-${formatter.format(Math.abs(priceDiff))}원`, tone: 'text-emerald-600', note: '(절감)' };
  return { value: '0원', tone: 'text-slate-500', note: '(가격 차이 없음)' };
}

// 추가 비용은 이 블록의 결론이라 블록 내 최대 급 텍스트로 강조한다 — 빈 상태에서도 "추가 비용 —" 자리를 고정한다.
function CostEffectEmphasis({ value, tone, note, compact = false }: { value: string; tone: string; note?: string; compact?: boolean }) {
  return (
    <div data-testid="cost-effect-extra" className="flex flex-wrap items-baseline gap-x-1.5 gap-y-0.5">
      <span className="text-[10px] font-black text-slate-500">추가 비용</span>{' '}
      <span className={`${compact ? 'text-base' : 'text-xl'} font-black leading-none ${tone}`}>{value}</span>
      {note ? <> <span className="text-[10px] font-bold text-slate-400">{note}</span></> : null}
    </div>
  );
}

// 분기형(diverging) 막대: 트랙 중앙의 얇은 0 기준선에서 양수는 오른쪽, 음수는 왼쪽으로 자란다.
// grow 모션도 중앙 기준(양수는 left:50%, 음수는 right:50% 앵커)에서 좌/우로 퍼진다.
function EffectBar({
  label,
  percent,
  scale,
  barClass,
  textClass,
  stagger = false,
  testId
}: {
  label: string;
  percent: number | null;
  scale: number;
  barClass: string;
  textClass: string;
  stagger?: boolean;
  testId?: string;
}) {
  // 절대값을 대칭 스케일로 반폭(50%) 안에 매핑 — 스케일을 넘는 극단값은 반폭 끝에서 캡된다.
  const magnitude = percent === null || percent === 0
    ? 0
    : Math.max(3, Math.min(50, (Math.min(Math.abs(percent), scale) / Math.max(1, scale)) * 50));
  const isNegative = (percent ?? 0) < 0;
  const direction = percent === null ? 'empty' : percent > 0 ? 'up' : percent < 0 ? 'down' : 'zero';
  return (
    <div data-testid={testId} data-effect-direction={direction} className="grid grid-cols-[30px_1fr_48px] items-center gap-2 text-[11px] font-bold text-slate-500">
      <span>{label}</span>
      <div className="relative h-2.5 overflow-hidden rounded-full bg-slate-100">
        <span aria-hidden="true" className="absolute left-1/2 top-0 h-full w-px -translate-x-1/2 bg-slate-300" />
        {magnitude > 0 ? (
          <div
            // 부호가 뒤집히면 반대쪽 앵커에서 다시 자라도록 재마운트한다.
            key={direction}
            className={`absolute top-0 h-full ${barClass} ${isNegative ? 'rounded-l-full' : 'rounded-r-full'} perf-bar-grow${stagger ? ' perf-bar-stagger' : ''}`}
            style={isNegative ? { right: '50%', width: `${magnitude}%` } : { left: '50%', width: `${magnitude}%` }}
          />
        ) : null}
      </div>
      <span className={`text-right font-black ${textClass}`}>{percent === null ? '—' : formatSignedPercent(percent)}</span>
    </div>
  );
}

function fpsRangeText(avg: number, low?: number) {
  return low !== undefined ? `${Math.round(low)}~${Math.round(avg)}` : `약 ${Math.round(avg)}`;
}

function percentDelta(from: number, to: number) {
  if (!(from > 0)) return 0;
  return Math.round(((to - from) / from) * 100);
}

function formatSignedPercent(percent: number) {
  return `${percent > 0 ? '+' : ''}${percent}%`;
}

function deltaBadgeTone(percent: number) {
  if (percent > 0) return 'border-emerald-200 bg-emerald-50 text-emerald-700';
  if (percent < 0) return 'border-red-200 bg-red-50 text-red-700';
  return 'border-slate-200 bg-slate-50 text-slate-500';
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
// 해상도 폴백(요청과 다른 해상도의 자료)은 동급 기준과 구분해 참고치임을 드러낸다.
function exactnessLabel(exactness?: string): string {
  switch (exactness) {
    case 'EXACT_PART_AND_RESOLUTION':
      return '이 부품 기준';
    case 'SAME_CLASS_AND_RESOLUTION':
      return '동급 부품 기준';
    case 'GPU_CLASS_REFERENCE':
      return '동급 그래픽카드 기준';
    case 'GPU_CLASS_RESOLUTION_FALLBACK':
      return '다른 해상도 참고치';
    default:
      return '공개 참고 자료';
  }
}

// 근거 행 선택 — [0] 고정 대신, 요청한 게임·해상도와 실제 일치(match.gameMatched && match.resolutionMatched)하는
// 첫 행을 우선한다. 일치 행이 없으면 서버 정렬 1순위([0])를 그대로 쓴다(기존·변경 조합 동일 규칙).
function pickEvidenceRow(rows?: GameFpsEvidence[]): GameFpsEvidence | undefined {
  if (!rows || rows.length === 0) return undefined;
  return rows.find((row) => row.match?.gameMatched === true && row.match?.resolutionMatched === true) ?? rows[0];
}

// 근거 행의 측정 조건을 사용자 언어 한 줄로 — 해상도 · 그래픽 옵션 · 출처.
function evidenceConditionText(row?: GameFpsEvidence): string {
  if (!row) return '조건 정보 없음';
  const parts: string[] = [];
  if (row.resolution) parts.push(row.resolution);
  const preset = presetLabel(row.graphicsPreset);
  if (preset) parts.push(preset);
  if (row.sourceName) parts.push(row.sourceName);
  return parts.length > 0 ? parts.join(' · ') : '조건 정보 없음';
}

// 측정 조건이 다른 두 근거를 확정 비교하지 않는다는 중립 고지 — 두 값은 그대로 두고,
// 각 조합이 어떤 조건에서 잰 참고치인지 양쪽 조건을 함께 보여준다.
function CompareConditionNotice({
  baseEvidence,
  compareEvidence
}: {
  baseEvidence?: GameFpsEvidence;
  compareEvidence?: GameFpsEvidence;
}) {
  return (
    <div
      data-testid="fps-compare-mismatch"
      className="mt-2 rounded-md border border-slate-200 bg-slate-50/70 px-2.5 py-1.5 text-[10px] font-bold text-slate-500"
    >
      <div className="font-black text-slate-600">측정 조건이 달라 직접 비교가 어려워요 — 각각의 참고치로만 봐 주세요.</div>
      <div className="mt-1 flex flex-col gap-0.5">
        <span data-testid="fps-compare-mismatch-base">기존 · {evidenceConditionText(baseEvidence)}</span>
        <span data-testid="fps-compare-mismatch-changed">변경 · {evidenceConditionText(compareEvidence)}</span>
      </div>
    </div>
  );
}

function scoreBadgeTone(score: number) {
  if (score >= 850) return 'border-emerald-200 bg-emerald-50 text-emerald-700';
  if (score >= 600) return 'border-amber-200 bg-amber-50 text-amber-700';
  return 'border-red-200 bg-red-50 text-red-700';
}

function requestFitLabel(requestFit: NonNullable<BuildGraphResolveResponse['compositeScore']>['requestFit']) {
  if (!requestFit) return '요청 예산 정보 없음';
  const formatter = new Intl.NumberFormat('ko-KR');
  if (requestFit.status === 'OVER_BUDGET') {
    return `요청 예산 초과 · 차액 ${formatter.format(Math.abs(requestFit.priceDiff ?? 0))}원`;
  }
  if (requestFit.status === 'PASS') return '요청 예산 적합';
  if (requestFit.status === 'WARN') return '요청 예산 근접';
  return requestFit.summary || '요청 예산 정보 없음';
}

function requestFitTone(status?: string) {
  if (status === 'OVER_BUDGET') return 'bg-red-50 text-red-700';
  if (status === 'WARN') return 'bg-amber-50 text-amber-700';
  if (status === 'PASS') return 'bg-emerald-50 text-emerald-700';
  return 'bg-slate-100 text-slate-500';
}
