import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bell, X } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useHiddenPageScrollbar } from '../../../hooks/useHiddenPageScrollbar';
import { Screen } from '../../../components/ui';
import { AUTH_CHANGED_EVENT, getToken } from '../../../lib/api';
import { PERF_COMPARE_REQUEST_EVENT, openAiAssistant, type PerfCompareTarget } from '../../../lib/events';
import {
  AI_ASSISTANT_SESSION_CHANGED_EVENT,
  AI_SELECTED_BUILD_CHANGED_EVENT,
  PART_CATEGORY_LABELS,
  clearSelectedAiBuild,
  readAssistantSession,
  readSelectedAiBuild,
  recentBuildsForChatContext,
  saveSelectedAiBuild,
  type AiBuildItem,
  type AiBoardFocus,
  type AiRecommendedBuild,
  type BuildGraphFocus,
  type BuildGraphResolveResponse,
  type PartCategory,
  type AiSelectedBuild
} from '../../quote/aiSelection';
import { AiBuildAssistant } from '../../quote/components/AiBuildAssistant';
import { buildSaveErrorMessage, resolveBuildGraph, saveBuildFromChat } from '../../quote/quoteApi';
import { QuoteComparePanel } from '../components/slot-board/QuoteComparePanel';
import { QuotePerformancePanel } from '../components/slot-board/QuotePerformancePanel';
import { SlotBoard, type SlotBoardVisualMode } from '../components/slot-board/SlotBoard';
import { SlotCandidatePanel } from '../components/slot-board/SlotCandidatePanel';
import { QuoteCheckoutActions, SlotStatusBar } from '../components/slot-board/SlotStatusBar';
import { RECOMMENDED_SLOT_ORDER, SLOT_CONFIGS, SLOT_COUNT, isSlotCategory } from '../components/slot-board/slotBoardConfig';
import { handlePartImageError, partImageUrl } from '../partDisplay';
import {
  applyAiBuildToQuoteDraft,
  deleteQuoteDraftItem,
  getBuildGraphLayoutDefault,
  getCurrentQuoteDraft,
  patchQuoteDraftItem,
  putQuoteDraftItem
} from '../partsApi';
import { quoteDraftToRecommendedBuild, selfQuoteBuildId } from '../selfQuoteBuild';
import type { PartRow, QuoteDraft, QuoteDraftItem } from '../types';

export function SelfQuotePage() {
  useHiddenPageScrollbar();

  const [searchParams] = useSearchParams();

  if (searchParams.get('view') === 'list') {
    return <Navigate to={allPartsRedirectTarget(searchParams)} replace />;
  }

  return <SelfQuoteSlotBoardPage />;
}

function allPartsRedirectTarget(searchParams: URLSearchParams) {
  const nextParams = new URLSearchParams(searchParams);
  nextParams.delete('view');
  const query = nextParams.toString();
  return `/parts${query ? `?${query}` : ''}`;
}

function SelfQuoteSlotBoardPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const categoryParam = searchParams.get('category');
  const selectedCategory: PartCategory | null = isSlotCategory(categoryParam) ? categoryParam : null;
  const [aiBuild, setAiBuild] = useState<AiSelectedBuild | null>(() => readSelectedAiBuild());
  // R1 견적 비교: 챗봇이 마지막으로 내려준 추천 배치(최대 3안 — 가성비/균형/고성능).
  const [recentBuilds, setRecentBuilds] = useState<AiRecommendedBuild[]>(() => recentBuildsForChatContext(readAssistantSession()));
  const [compareOpen, setCompareOpen] = useState(false);
  const [applyingBuildId, setApplyingBuildId] = useState<string | null>(null);
  const [compareApplyError, setCompareApplyError] = useState<string | null>(null);
  const [aiFocusCategories, setAiFocusCategories] = useState<PartCategory[]>([]);
  // 성능 패널 교체 비교(기존 조합 vs 변경 조합) — 변경 후보 1개를 페이지가 소유하고 패널에 내려준다.
  const [perfComparison, setPerfComparison] = useState<PerfCompareTarget | null>(null);
  const perfPanelRef = useRef<HTMLDivElement | null>(null);
  const { effectiveVisualMode, setVisualMode } = useSlotBoardVisualMode();
  const hasToken = Boolean(getToken());
  const loginHref = `/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`;

  const { data: quoteDraft, isError: isQuoteDraftError, isLoading: isQuoteDraftLoading } = useQuery({
    queryKey: ['quote-draft', 'current'],
    queryFn: getCurrentQuoteDraft,
    enabled: hasToken
  });
  const invalidateQuoteDraft = () => {
    void queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] });
    // 담기(ADD) 평가는 현재 구성에 직접 의존하므로 드래프트가 바뀌면 후보 목록도 재평가한다.
    void queryClient.invalidateQueries({ queryKey: ['parts', 'slot-candidates'] });
  };
  const addMutation = useMutation({
    mutationFn: ({ partId, quantity }: { partId: string; quantity: number }) => putQuoteDraftItem(partId, quantity),
    onSuccess: invalidateQuoteDraft
  });
  const updateQuantityMutation = useMutation({
    mutationFn: ({ partId, quantity }: { partId: string; quantity: number }) => patchQuoteDraftItem(partId, quantity),
    onSuccess: invalidateQuoteDraft
  });
  const deleteMutation = useMutation({
    mutationFn: (partId: string) => deleteQuoteDraftItem(partId),
    onSuccess: invalidateQuoteDraft
  });
  const saveQuoteMutation = useMutation({
    mutationFn: (draft: QuoteDraft) => saveBuildFromChat({
      sourceBuildId: selfQuoteBuildId(draft),
      lastUserMessage: '셀프 견적에서 저장',
      build: quoteDraftToRecommendedBuild(draft)
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['build-history'] })
  });

  const draftItems = quoteDraft?.items ?? [];
  const selectedTotal = quoteDraft?.totalPrice ?? 0;

  // 관계선 라벨의 optional source. 실패해도 슬롯 보드와 기본 topology 관계선은 항상 렌더링된다.
  const graphFocus = quoteGraphFocus(selectedCategory);
  const graphQuery = useQuery({
    queryKey: ['build-graph', 'quote-draft-current', quoteGraphSignature(draftItems), graphFocus.mode, graphFocus.category],
    queryFn: () => resolveBuildGraph({
      source: 'QUOTE_DRAFT_CURRENT',
      view: 'FOCUSED',
      focus: graphFocus
    }),
    placeholderData: keepPreviousData,
    enabled: hasToken && !isQuoteDraftLoading,
    retry: false
  });

  // 관리자가 배치한 3D 커넥터 앵커. 실패/미저장이어도 IsoCardConnector는 자동 계산으로 폴백한다.
  const anchorQuery = useQuery({
    queryKey: ['build-graph-layout-default'],
    queryFn: getBuildGraphLayoutDefault,
    enabled: hasToken,
    staleTime: 5 * 60 * 1000,
    retry: false
  });

  useEffect(() => {
    const syncSelectedBuild = () => setAiBuild(readSelectedAiBuild());
    const syncRecentBuilds = () => setRecentBuilds(recentBuildsForChatContext(readAssistantSession()));
    const syncAll = () => {
      syncSelectedBuild();
      syncRecentBuilds();
    };
    window.addEventListener(AI_SELECTED_BUILD_CHANGED_EVENT, syncSelectedBuild);
    window.addEventListener(AI_ASSISTANT_SESSION_CHANGED_EVENT, syncRecentBuilds);
    window.addEventListener(AUTH_CHANGED_EVENT, syncAll);
    window.addEventListener('storage', syncAll);
    return () => {
      window.removeEventListener(AI_SELECTED_BUILD_CHANGED_EVENT, syncSelectedBuild);
      window.removeEventListener(AI_ASSISTANT_SESSION_CHANGED_EVENT, syncRecentBuilds);
      window.removeEventListener(AUTH_CHANGED_EVENT, syncAll);
      window.removeEventListener('storage', syncAll);
    };
  }, []);

  // 성능 비교 시작: 비교 대상을 저장하고 성능 패널로 부드럽게 이동한다(패널은 그리드 아래에 있어 안 보일 수 있다).
  const startPerfComparison = useCallback((target: PerfCompareTarget) => {
    setPerfComparison(target);
    requestAnimationFrame(() => {
      perfPanelRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }, []);

  // 비교 대상이 실제 견적과 어긋나면 자동 해제한다 — 후보를 실제로 담았거나(같은 부품), 기존 부품을 뺀 경우.
  useEffect(() => {
    if (!perfComparison) return;
    const inCategory = draftItems.filter((item) => item.category === perfComparison.category);
    if (inCategory.length === 0 || inCategory.some((item) => item.partId === perfComparison.partId)) {
      setPerfComparison(null);
    }
  }, [draftItems, perfComparison]);

  // AI 변경 미리보기(draft-edit) 연동 — 챗봇이 CPU/GPU 변경 미리보기를 그리면 같은 비교를 패널에도 켠다.
  useEffect(() => {
    const onPerfCompareRequest = (event: Event) => {
      const detail = (event as CustomEvent<PerfCompareTarget>).detail;
      if (!detail || (detail.category !== 'CPU' && detail.category !== 'GPU') || !detail.partId) return;
      startPerfComparison(detail);
    };
    window.addEventListener(PERF_COMPARE_REQUEST_EVENT, onPerfCompareRequest);
    return () => window.removeEventListener(PERF_COMPARE_REQUEST_EVENT, onPerfCompareRequest);
  }, [startPerfComparison]);

  // 비교 패널에서 안 선택 — 챗봇 selectBuild와 같은 레시피(일괄 적용 성공 후에만 선택 저장).
  const applyCompareBuild = async (build: AiRecommendedBuild) => {
    if (applyingBuildId) return;
    setCompareApplyError(null);
    setApplyingBuildId(build.id);
    try {
      const appliedDraft = await applyAiBuildToQuoteDraft({
        buildId: build.id,
        conflictPolicy: 'REPLACE',
        items: build.items.map((item) => ({
          partId: item.partId,
          category: item.category,
          quantity: item.quantity
        }))
      });
      saveSelectedAiBuild(build);
      setAiBuild(readSelectedAiBuild());
      queryClient.setQueryData(['quote-draft', 'current'], appliedDraft);
      invalidateQuoteDraft();
    } catch {
      setCompareApplyError('선택한 조합을 견적에 적용하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setApplyingBuildId(null);
    }
  };

  const closePanel = useCallback(() => {
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      nextParams.delete('category');
      return nextParams;
    });
  }, [setSearchParams]);

  const selectSlot = (category: PartCategory) => {
    if (!hasToken) {
      navigate(loginHref);
      return;
    }
    if (selectedCategory === category) {
      closePanel();
      return;
    }
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      nextParams.set('category', category);
      return nextParams;
    });
  };

  const selectBoardSlot = (category: PartCategory) => {
    setAiFocusCategories([]);
    selectSlot(category);
  };

  const handleBoardFocus = useCallback((focus: AiBoardFocus) => {
    setAiFocusCategories(focus.categories);
  }, []);

  const clearBoardFocus = useCallback(() => {
    setAiFocusCategories([]);
  }, []);

  const addPart = async (part: PartRow) => {
    if (!hasToken) {
      navigate(loginHref);
      throw new Error('로그인이 필요합니다.');
    }
    await addMutation.mutateAsync({ partId: part.id, quantity: 1 });
    closePanel();
  };

  const removeItem = (partId: string) => {
    if (!hasToken) {
      navigate(loginHref);
      return;
    }
    deleteMutation.mutate(partId);
  };

  const updateQuantity = (partId: string, quantity: number) => {
    if (!hasToken) {
      navigate(loginHref);
      return;
    }
    if (quantity <= 0) {
      deleteMutation.mutate(partId);
      return;
    }
    updateQuantityMutation.mutate({ partId, quantity });
  };

  const isMutating = addMutation.isPending || updateQuantityMutation.isPending || deleteMutation.isPending;
  const hasCompatibilityFail = quoteHasCompatibilityFail(graphQuery.data, draftItems);
  const statusByCategory = quoteStatusByCategory(graphQuery.data);
  const showSupplementaryQuotePanels = false;
  const checkoutActions = (
    <QuoteCheckoutActions
      hasItems={draftItems.length > 0}
      hasCompatibilityFail={hasCompatibilityFail}
      onSave={() => quoteDraft && saveQuoteMutation.mutate(quoteDraft)}
      isSavePending={saveQuoteMutation.isPending}
      compact
    />
  );

  const filledCount = SLOT_CONFIGS.filter((slot) => draftItems.some((item) => item.category === slot.category)).length;
  const selectedSlot = selectedCategory
    ? SLOT_CONFIGS.find((slot) => slot.category === selectedCategory) ?? null
    : null;
  // 순차 가이드: 권장 순서에서 아직 비어 있는 첫 카테고리를 "다음 선택"으로 안내한다(강제 아님).
  const nextCategory = RECOMMENDED_SLOT_ORDER.find((category) => !draftItems.some((item) => item.category === category)) ?? null;
  const warnCount = graphQuery.data
    ? graphQuery.data.nodes.filter((node) => node.type === 'PART' && node.status === 'WARN').length
    : 0;
  const failCount = graphQuery.data
    ? graphQuery.data.nodes.filter((node) => node.type === 'PART' && node.status === 'FAIL').length
    : 0;
  // 빨간 부품 노드는 없는데 툴 검사가 FAIL인 경우(예: GPU 없는 견적의 파워 부족) — '조건 미충족'으로 구분 표기.
  const unmetConditionCount = failCount === 0 ? blockingToolFailures(graphQuery.data, draftItems).length : 0;

  return (
    <Screen mainClassName="mx-auto w-full min-w-0 max-w-[1800px] overflow-x-clip px-4 py-2 sm:px-6 lg:h-[calc(100dvh-210px)] lg:overflow-hidden lg:px-[clamp(40px,4.5vw,88px)] xl:h-[calc(100dvh-167px)]">
      <div className="min-w-0 space-y-4 lg:flex lg:h-full lg:min-h-0 lg:flex-col lg:gap-2 lg:space-y-0">
        {showSupplementaryQuotePanels && aiBuild ? (
          <AiSelectedBuildPanel
            build={aiBuild}
            draftItems={draftItems}
            currentTotal={selectedTotal}
            onClear={() => {
              clearSelectedAiBuild();
              setAiBuild(null);
            }}
          />
        ) : null}

        {/* R1 견적 비교: 최근 AI 추천 배치가 2안 이상이면 나란히 비교를 제공한다 (멘토 피드백). */}
        {showSupplementaryQuotePanels && recentBuilds.length >= 2 && !compareOpen ? (
          <div className="panel flex flex-wrap items-center justify-between gap-2 border-blue-100 px-4 py-2.5">
            <span className="text-xs font-bold text-slate-600">
              AI가 추천한 {recentBuilds.length}안을 나란히 비교할 수 있어요
            </span>
            <button
              type="button"
              data-testid="quote-compare-open"
              onClick={() => setCompareOpen(true)}
              className="rounded-md border border-brand-blue/30 bg-white px-3 py-1.5 text-xs font-black text-brand-blue transition hover:bg-blue-50"
            >
              {recentBuilds.length}안 비교하기
            </button>
          </div>
        ) : null}
        {showSupplementaryQuotePanels && compareOpen && recentBuilds.length >= 2 ? (
          <QuoteComparePanel
            builds={recentBuilds}
            draftItems={draftItems}
            onApply={(build) => void applyCompareBuild(build)}
            applyingBuildId={applyingBuildId}
            applyError={compareApplyError}
            onClose={() => setCompareOpen(false)}
          />
        ) : null}

        {/* 본문: 체크리스트(품목 지도) + 보드(보조 그래프) + AI 상담 패널. */}
        <div className="grid min-w-0 gap-4 lg:min-h-0 lg:flex-1 lg:grid-cols-[clamp(256px,18vw,320px)_minmax(0,1fr)_clamp(336px,21vw,400px)] lg:grid-rows-[auto_minmax(0,1fr)_auto] lg:items-stretch lg:gap-2">
          {/* 핵심 의사결정 지표: 체크리스트+관계도 폭에 맞추고 AI 열은 이 행부터 사용한다. */}
          <div ref={perfPanelRef} className="min-h-0 min-w-0 lg:col-span-2 lg:row-start-1">
            {draftItems.length === 0 ? (
              <section
                data-testid="quote-start-banner"
                className="panel flex min-h-[82px] flex-col justify-center gap-2 border-blue-100 bg-blue-50/60 px-4 py-2 md:flex-row md:items-center md:justify-between"
              >
                <div className="min-w-0">
                  <div className="text-sm font-black text-commerce-ink">뭘 골라야 할지 모르겠다면, AI에게 예산과 용도만 알려주세요</div>
                  <div className="mt-0.5 truncate text-[11px] text-slate-500">
                    예: &quot;게이밍 200만원&quot; — AI 추천 조합을 적용하거나 CPU부터 직접 고를 수 있어요
                  </div>
                </div>
                <div className="flex shrink-0 flex-wrap gap-2">
                  <button
                    type="button"
                    data-testid="quote-ai-start"
                    onClick={() => openAiAssistant()}
                    className="rounded-md bg-[#de6c2d] px-4 py-2 text-xs font-black text-white transition hover:bg-[#c45c22]"
                  >
                    AI로 시작하기
                  </button>
                  <button
                    type="button"
                    data-testid="quote-manual-start"
                    onClick={() => selectSlot('CPU')}
                    className="rounded-md border border-commerce-line bg-white px-4 py-2 text-xs font-black text-slate-700 transition hover:border-commerce-ink"
                  >
                    직접 고르기 (CPU부터)
                  </button>
                </div>
              </section>
            ) : (
              <QuotePerformancePanel
                graph={graphQuery.data}
                items={draftItems}
                comparison={perfComparison}
                onClearComparison={() => setPerfComparison(null)}
                onStartComparison={startPerfComparison}
                onApplyComparison={(target) => addMutation.mutateAsync({ partId: target.partId, quantity: 1 })}
                isLoading={graphQuery.isLoading || graphQuery.isFetching}
                isError={graphQuery.isError}
                onRetry={() => void graphQuery.refetch()}
                compact
              />
            )}
          </div>
          <QuoteChecklist
            draftItems={draftItems}
            selectedCategory={selectedCategory}
            nextCategory={nextCategory}
            onSelect={selectSlot}
            onClearSelection={closePanel}
            onRemoveItem={removeItem}
            isRemovePending={deleteMutation.isPending}
            statusByCategory={statusByCategory}
          />
          <div className="min-h-0 min-w-0 max-w-full lg:h-full">
            <SlotBoard
              items={draftItems}
              selectedCategory={selectedCategory}
              aiFocusCategories={aiFocusCategories}
              nextCategory={nextCategory}
              visualMode={effectiveVisualMode}
              onVisualModeChange={setVisualMode}
              onClearSelection={closePanel}
              onClearAiFocus={clearBoardFocus}
              onSlotSelect={selectBoardSlot}
              onRemoveItem={removeItem}
              onUpdateQuantity={updateQuantity}
              isRemovePending={deleteMutation.isPending}
              isQuantityPending={updateQuantityMutation.isPending}
              graph={graphQuery.data}
              connectorAnchors={anchorQuery.data?.anchors}
              bodyOverlay={selectedSlot ? (
                <SlotCandidatePanel
                  slot={selectedSlot}
                  draftItems={draftItems.filter((item) => item.category === selectedSlot.category)}
                  onClose={closePanel}
                  onAddPart={addPart}
                  onRemoveItem={removeItem}
                  onUpdateQuantity={updateQuantity}
                  isMutating={isMutating}
                  placement="board-overlay"
                />
              ) : null}
              onBodyOverlayDismiss={closePanel}
            />
          </div>
          <div className="min-h-0 min-w-0 max-w-full lg:col-start-3 lg:row-span-3 lg:row-start-1 lg:h-full">
            <AiBuildAssistant surface="self-quote" variant="embedded" onBoardFocus={handleBoardFocus} />
          </div>

          {/* 보조 상태 지표: 작업 영역 아래에서 총액·장착 수·호환·SSD를 짧게 확인한다. */}
          <div className="space-y-1.5 lg:col-span-2 lg:row-start-3 lg:min-h-0">
            <QuoteSummaryBar
              totalPrice={selectedTotal}
              filledCount={filledCount}
              slotCount={SLOT_COUNT}
              warnCount={warnCount}
              failCount={failCount}
              unmetConditionCount={unmetConditionCount}
              graphLoading={graphQuery.isLoading}
              graphError={graphQuery.isError}
              checkoutActions={draftItems.length > 0 ? checkoutActions : null}
            />
            <SlotStatusBar
              quoteDraft={quoteDraft}
              hasToken={hasToken}
              loginHref={loginHref}
              isDraftLoading={hasToken && isQuoteDraftLoading}
              isDraftError={isQuoteDraftError}
              hasCompatibilityFail={hasCompatibilityFail}
              onSave={() => quoteDraft && saveQuoteMutation.mutate(quoteDraft)}
              isSavePending={saveQuoteMutation.isPending}
              isSaveSuccess={saveQuoteMutation.isSuccess}
              isSaveError={saveQuoteMutation.isError}
              saveErrorMessage={buildSaveErrorMessage(saveQuoteMutation.error)}
              showCheckoutActions={draftItems.length === 0}
              compact
            />
          </div>
        </div>
      </div>
    </Screen>
  );
}

const SLOT_BOARD_VISUAL_MODE_STORAGE_KEY = 'buildgraph.selfQuote.slotBoardVisualMode';

function useSlotBoardVisualMode() {
  const [visualMode, setVisualModeState] = useState<SlotBoardVisualMode>(readSlotBoardVisualMode);
  const [isDesktop, setIsDesktop] = useState(isDesktopSlotBoardViewport);

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return;
    }
    const media = window.matchMedia('(min-width: 1024px)');
    const sync = () => setIsDesktop(media.matches);
    sync();
    media.addEventListener('change', sync);
    return () => media.removeEventListener('change', sync);
  }, []);

  const setVisualMode = useCallback((mode: SlotBoardVisualMode) => {
    setVisualModeState(mode);
    writeSlotBoardVisualMode(mode);
  }, []);

  return {
    // 배치도(fused)·3D는 데스크톱 전용 아트라서 모바일은 실장도 카드 목록으로 폴백한다.
    effectiveVisualMode: isDesktop ? visualMode : 'motherboard',
    setVisualMode
  };
}

function isDesktopSlotBoardViewport() {
  return typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(min-width: 1024px)').matches;
}

function readSlotBoardVisualMode(): SlotBoardVisualMode {
  if (typeof window === 'undefined') {
    return 'fused';
  }
  try {
    const storedValue = window.localStorage.getItem(SLOT_BOARD_VISUAL_MODE_STORAGE_KEY);
    // 저장값이 있으면 사용자가 고른 보기(실장도/3D 포함)를 존중하고, 없으면 배치도가 기본.
    if (storedValue === 'isometric' || storedValue === 'motherboard' || storedValue === 'fused') {
      return storedValue;
    }
    return 'fused';
  } catch {
    return 'fused';
  }
}

function writeSlotBoardVisualMode(mode: SlotBoardVisualMode) {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage.setItem(SLOT_BOARD_VISUAL_MODE_STORAGE_KEY, mode);
  } catch {
    // localStorage가 차단된 환경에서는 현재 세션 상태만 유지한다.
  }
}

function QuoteChecklist({
  draftItems,
  selectedCategory,
  nextCategory,
  onSelect,
  onClearSelection,
  onRemoveItem,
  isRemovePending,
  statusByCategory
}: {
  draftItems: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  nextCategory: PartCategory | null;
  onSelect: (category: PartCategory) => void;
  onClearSelection: () => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
  statusByCategory: Map<PartCategory, 'PASS' | 'WARN' | 'FAIL'>;
}) {
  const filledCount = RECOMMENDED_SLOT_ORDER.filter((category) => draftItems.some((item) => item.category === category)).length;

  const toggleCategory = (category: PartCategory) => {
    if (selectedCategory === category) {
      onClearSelection();
      return;
    }
    onSelect(category);
  };

  const toggleCategoryFromKeyboard = (event: KeyboardEvent<HTMLDivElement>, category: PartCategory) => {
    if (event.key !== 'Enter' && event.key !== ' ') {
      return;
    }
    event.preventDefault();
    toggleCategory(category);
  };

  const removeCategoryItems = (items: QuoteDraftItem[]) => {
    items.forEach((item) => onRemoveItem(item.partId));
  };

  const removeAllItems = () => {
    draftItems.forEach((item) => onRemoveItem(item.partId));
  };

  return (
    // 데스크톱에서는 보드 높이를 따라가고, 슬롯·후보 목록만 패널 안에서 스크롤한다.
    <aside data-testid="quote-checklist" className="panel flex h-fit min-w-0 max-w-full flex-col p-4 lg:h-full lg:overflow-hidden">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-black text-commerce-ink">견적 체크리스트</h2>
        <span className="flex items-center gap-2">
          <span data-testid="quote-checklist-progress" className="text-[11px] font-black text-slate-500">
            {filledCount}/{RECOMMENDED_SLOT_ORDER.length} 완료
          </span>
          {draftItems.length > 0 ? (
            <button
              type="button"
              disabled={isRemovePending}
              onClick={removeAllItems}
              className="rounded px-1 text-[10px] font-black text-slate-500 transition hover:bg-slate-100 hover:text-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              전부 제거
            </button>
          ) : null}
        </span>
      </div>
      <ol className="min-h-0 flex-1 space-y-1.5 overflow-y-auto pr-1">
        {RECOMMENDED_SLOT_ORDER.map((category, index) => {
          const items = draftItems.filter((item) => item.category === category);
          const filled = items.length > 0;
          const isNext = category === nextCategory;
          const isSelected = category === selectedCategory;
          const lineTotal = items.reduce((sum, item) => sum + item.lineTotal, 0);
          const label = PART_CATEGORY_LABELS[category] ?? category;
          const slotStatus = statusByCategory.get(category);
          const hasFail = filled && slotStatus === 'FAIL';
          const hasWarn = filled && slotStatus === 'WARN';
          return (
            <li key={category}>
              <div
                role="button"
                tabIndex={0}
                aria-label={`${label} 후보 목록 ${isSelected ? '닫기' : '열기'}`}
                aria-expanded={isSelected}
                data-testid={`checklist-${category}`}
                data-filled={filled ? 'true' : 'false'}
                data-next={isNext ? 'true' : 'false'}
                onClick={() => toggleCategory(category)}
                onKeyDown={(event) => toggleCategoryFromKeyboard(event, category)}
                className={`w-full rounded-md border px-2.5 py-2 text-left text-xs transition ${
                  hasFail
                    ? 'checklist-problem-fill-pulse'
                    : hasWarn
                      ? 'checklist-warning-fill-pulse'
                      : ''
                } ${
                  isSelected
                    ? hasFail
                      ? 'border-red-300 bg-red-50/60 ring-2 ring-red-100'
                      : hasWarn
                        ? 'border-amber-300 bg-amber-50/60 ring-2 ring-amber-100'
                        : 'border-brand-blue bg-white ring-2 ring-blue-100'
                    : filled
                      ? hasFail
                        ? 'border-red-200 bg-red-50/50 hover:border-red-400'
                        : hasWarn
                          ? 'border-amber-200 bg-amber-50/50 hover:border-amber-400'
                          : 'border-emerald-100 bg-white hover:border-emerald-300'
                      : isNext
                        ? 'slot-empty-pulse slot-hint-shimmer border-brand-blue bg-blue-50/50'
                        : 'border-dashed border-slate-300 bg-white hover:border-slate-400'
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-black text-slate-700">
                    {index + 1}. {label}
                  </span>
                  {filled ? (
                    <span className="flex shrink-0 items-center gap-1.5">
                      <span className={`text-[10px] font-black ${hasFail ? 'text-red-700' : hasWarn ? 'text-amber-700' : 'text-emerald-700'}`}>
                        {hasFail ? '경고' : hasWarn ? '주의' : '✓ 완료'}
                      </span>
                      <button
                        type="button"
                        disabled={isRemovePending}
                        onClick={(event) => {
                          event.stopPropagation();
                          removeCategoryItems(items);
                        }}
                        className="rounded px-1 text-[10px] font-black text-slate-500 transition hover:bg-slate-100 hover:text-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        제거
                      </button>
                    </span>
                  ) : isNext ? (
                    <span className="shrink-0 text-[10px] font-black text-brand-blue">다음 선택</span>
                  ) : (
                    <span className="shrink-0 text-[10px] font-bold text-slate-400">비어 있음</span>
                  )}
                </div>
                {filled ? (
                  <div className="mt-1.5 flex min-w-0 items-center gap-2">
                    <img
                      data-testid={`checklist-part-image-${category}`}
                      src={partImageUrl(items[0])}
                      alt=""
                      loading="lazy"
                      decoding="async"
                      onError={(event) => handlePartImageError(event, category)}
                      className="h-10 w-10 shrink-0 rounded-md border border-commerce-line bg-white object-contain p-0.5"
                    />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[11px] font-bold text-slate-600">
                        {items[0].name}
                        {items.length > 1 ? ` 외 ${items.length - 1}개` : ''}
                      </span>
                      <span className="mt-0.5 block text-[11px] font-black text-commerce-ink">{lineTotal.toLocaleString()}원</span>
                    </span>
                  </div>
                ) : (
                  <div className="mt-1 text-[11px] text-slate-400">+ 부품 선택</div>
                )}
              </div>
            </li>
          );
        })}
      </ol>
    </aside>
  );
}

// 멘토 피드백: 지금까지 고른 부품을 엑셀 견적서처럼 — 그리드 아래 전폭 테이블 박스.
// 합계는 하단 상태바(견적 합계)가 담당하므로 여기선 품목 행만 보여준다.
// RAM/SSD처럼 한 슬롯에 여러 상품이면 상품별 행으로 푼다.
function QuoteItemsTable({ draftItems }: { draftItems: QuoteDraftItem[] }) {
  const rows = RECOMMENDED_SLOT_ORDER.flatMap((category) =>
    draftItems.filter((item) => item.category === category).map((item) => ({ category, item }))
  );
  if (rows.length === 0) {
    return null;
  }
  return (
    <section data-testid="quote-items-table" className="panel p-4">
      <h2 className="mb-2 text-sm font-black text-commerce-ink">담은 부품 {rows.length}개</h2>
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-xs">
          <thead>
            <tr className="border-b border-commerce-line text-left text-slate-400">
              <th scope="col" className="w-24 py-1.5 pr-2 font-bold">부품</th>
              <th scope="col" className="py-1.5 pr-2 font-bold">상품명</th>
              <th scope="col" className="w-12 py-1.5 pr-2 text-right font-bold">수량</th>
              <th scope="col" className="w-28 py-1.5 text-right font-bold">금액</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ category, item }) => (
              <tr key={item.id} data-testid={`quote-items-row-${category}`} className="border-b border-slate-100">
                <td className="py-1.5 pr-2 font-black text-slate-600">{PART_CATEGORY_LABELS[category] ?? category}</td>
                <td className="max-w-0 truncate py-1.5 pr-2 text-slate-600" title={item.name}>{item.name}</td>
                <td className="py-1.5 pr-2 text-right text-slate-600">{item.quantity}</td>
                <td className="py-1.5 text-right font-black text-commerce-ink">{item.lineTotal.toLocaleString()}원</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function AiSelectedBuildPanel({
  build,
  draftItems,
  currentTotal,
  onClear
}: {
  build: AiSelectedBuild;
  draftItems: QuoteDraftItem[];
  currentTotal: number;
  onClear: () => void;
}) {
  const displayItems = build.items.map((item) => createAiBuildDisplayItem(item, draftItems));
  const reflectedCount = displayItems.filter((item) => item.status !== '미반영').length;
  const appliedPartCategories = build.appliedPartCategories ?? [];
  const initialTotalDiffers = build.totalPrice > 0 && currentTotal !== build.totalPrice;

  return (
    <section data-testid="ai-selected-build-panel" className="panel overflow-hidden border-blue-100 bg-blue-50/60">
      <div className="flex flex-col gap-4 p-5 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <span className="rounded bg-commerce-ink px-2 py-1 text-[11px] font-black text-white">AI 선택</span>
            <span className="rounded bg-white px-2 py-1 text-[11px] font-black text-brand-blue">실제 장바구니 적용 기록</span>
            {appliedPartCategories.map((category) => (
              <span key={category} className="rounded bg-blue-50 px-2 py-1 text-[11px] font-black text-brand-blue">
                {PART_CATEGORY_LABELS[category]} 반영됨
              </span>
            ))}
            {reflectedCount > 0 ? <span className="rounded bg-emerald-50 px-2 py-1 text-[11px] font-black text-emerald-700">현재 견적 {reflectedCount}개 반영</span> : null}
          </div>
          <h2 className="text-xl font-black text-commerce-ink">AI 선택 조합</h2>
          <p className="mt-2 max-w-3xl break-keep text-sm leading-6 text-slate-600">
            {build.title} · {build.summary} AI로 시작한 조합을 현재 견적 장바구니 기준으로 보여줍니다.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="rounded-md bg-white px-4 py-3 text-right">
            <div className="text-xs font-bold text-slate-500">현재 견적 합계</div>
            <div data-testid="ai-selected-build-current-total" className="text-lg font-black text-commerce-sale">{currentTotal.toLocaleString()}원</div>
            {initialTotalDiffers ? (
              <div className="mt-1 text-[11px] font-bold text-slate-400">최초 AI 조합: {build.totalPrice.toLocaleString()}원</div>
            ) : null}
          </div>
          <button
            type="button"
            aria-label="AI 선택 조합 표시 닫기"
            onClick={onClear}
            className="grid h-10 w-10 place-items-center rounded-md border border-commerce-line bg-white text-slate-600 hover:border-commerce-sale hover:text-commerce-sale"
          >
            <X size={17} />
          </button>
        </div>
      </div>

      <div className="grid gap-3 border-t border-blue-100 bg-white/75 p-5 md:grid-cols-2 xl:grid-cols-4">
        {displayItems.map((item) => {
          const categoryLabel = PART_CATEGORY_LABELS[item.category] ?? item.category;
          return (
            <div key={item.key} className="rounded-lg border border-commerce-line bg-white p-3 text-xs">
              <div className="mb-2 flex items-center justify-between gap-2">
                <span className="rounded bg-slate-100 px-2 py-1 font-black text-slate-700">{categoryLabel}</span>
                <span className={`rounded px-2 py-1 font-black ${aiStatusClass(item.status)}`}>
                  {item.status}
                </span>
              </div>
              <div className="min-h-10 font-black leading-5 text-commerce-ink">{item.name}</div>
              <div className="mt-1 text-slate-500">{item.manufacturer} · 수량 {item.quantity}</div>
              <div className="mt-2 break-keep text-slate-500">{item.note}</div>
              <div className="mt-3 font-black text-brand-blue">{item.lineTotal.toLocaleString()}원</div>
            </div>
          );
        })}
      </div>

      <div className="flex flex-col gap-2 border-t border-blue-100 bg-white px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="break-keep text-xs font-bold leading-5 text-slate-500">
          AI 조합 이후 챗봇으로 바꾼 부품까지 현재 견적 장바구니 기준으로 표시합니다.
        </div>
        <Link to="/my/quotes" className="inline-flex min-h-10 items-center justify-center gap-2 rounded-md border border-commerce-line bg-white px-4 text-sm font-black text-commerce-ink hover:border-commerce-ink">
          <Bell size={16} />
          목표가 알림 설정
        </Link>
      </div>
    </section>
  );
}

type AiBuildDisplayItem = {
  key: string;
  category: AiBuildItem['category'];
  name: string;
  manufacturer: string;
  quantity: number;
  lineTotal: number;
  note: string;
  status: '담김' | '교체됨' | '미반영';
};

function createAiBuildDisplayItem(item: AiBuildItem, draftItems: QuoteDraftItem[]): AiBuildDisplayItem {
  const matchingDraftItems = draftItems.filter((draftItem) => draftItem.category === item.category);
  const samePart = matchingDraftItems.find((draftItem) => draftItem.partId === item.partId);
  const currentItem = samePart ?? matchingDraftItems[0];

  if (!currentItem) {
    return {
      key: item.partId,
      category: item.category,
      name: item.name,
      manufacturer: item.manufacturer,
      quantity: item.quantity,
      lineTotal: item.price * item.quantity,
      note: item.note,
      status: '미반영'
    };
  }

  const categoryLineTotal = matchingDraftItems.reduce((sum, draftItem) => sum + draftItem.lineTotal, 0);
  const hasMultiple = matchingDraftItems.length > 1;
  const sameQuantity = currentItem.quantity === item.quantity;
  const unchanged = currentItem.partId === item.partId && sameQuantity && !hasMultiple;

  return {
    key: `${item.category}-${currentItem.partId}`,
    category: item.category,
    name: hasMultiple ? `${currentItem.name} 외 ${matchingDraftItems.length - 1}개` : currentItem.name,
    manufacturer: currentItem.manufacturer ?? item.manufacturer,
    quantity: hasMultiple ? matchingDraftItems.reduce((sum, draftItem) => sum + draftItem.quantity, 0) : currentItem.quantity,
    lineTotal: hasMultiple ? categoryLineTotal : currentItem.lineTotal,
    note: unchanged ? item.note : 'AI 이후 챗봇 변경 반영',
    status: unchanged ? '담김' : '교체됨'
  };
}

function aiStatusClass(status: AiBuildDisplayItem['status']) {
  if (status === '담김') return 'bg-emerald-50 text-emerald-700';
  if (status === '교체됨') return 'bg-amber-50 text-amber-700';
  return 'bg-slate-100 text-slate-500';
}

function quoteGraphFocus(category: PartCategory | null): BuildGraphFocus {
  if (category) {
    return {
      mode: 'PART_IMPACT',
      category,
      tool: graphToolForCategory(category)
    };
  }
  return {
    mode: 'ISSUE_PATH'
  };
}

function quoteGraphSignature(items: QuoteDraftItem[]) {
  if (items.length === 0) {
    return 'empty';
  }
  return items
    .map((item) => `${item.partId}:${item.quantity}:${item.lineTotal}`)
    .sort()
    .join('|');
}

// 툴 FAIL이 구매를 차단해야 하는 장착 카테고리 — 해당 부품이 담겨 있을 때만 본다.
// (예: PSU 없이 power FAIL은 '파워 미장착'이지 비호환이 아니다 — 최소구성 정책은 P2-3.)
const TOOL_RELATED_CATEGORIES: Record<string, PartCategory[]> = {
  compatibility: ['CPU', 'MOTHERBOARD', 'RAM', 'COOLER'],
  power: ['PSU'],
  size: ['CASE']
};

// 부품 노드/엣지에 실리지 않는 툴 FAIL(예: GPU 없는 견적의 파워 용량 부족)을 잡는다 —
// 엣지는 양쪽 부품이 있어야 생기므로 상대 카테고리가 비면 툴 FAIL이 화면 어디에도 안 뜨는 사각이 있었다.
function blockingToolFailures(graph: BuildGraphResolveResponse | undefined, items: QuoteDraftItem[]) {
  if (!graph || items.length === 0) {
    return [];
  }
  const filledCategories = new Set(items.map((item) => item.category));
  return (graph.toolResults ?? []).filter((result) => {
    if (result.status !== 'FAIL') {
      return false;
    }
    const related = TOOL_RELATED_CATEGORIES[result.tool] ?? [];
    return related.some((category) => filledCategories.has(category));
  });
}

// FAIL이 있으면 구매만 차단한다. 장착된 카테고리와 관련된 검증 결과만 본다.
function quoteHasCompatibilityFail(graph: BuildGraphResolveResponse | undefined, items: QuoteDraftItem[]) {
  if (!graph || items.length === 0) {
    return false;
  }
  const filledCategories = new Set(items.map((item) => item.category));
  const categoryByNodeId = new Map(graph.nodes.map((node) => [node.id, node.category]));
  const nodeFail = graph.nodes.some((node) =>
    node.type === 'PART' && node.status === 'FAIL' && typeof node.category === 'string' && filledCategories.has(node.category)
  );
  const edgeFail = graph.edges.some((edge) => {
    if (edge.status !== 'FAIL') {
      return false;
    }
    const sourceCategory = categoryByNodeId.get(edge.source);
    const targetCategory = categoryByNodeId.get(edge.target);
    return typeof sourceCategory === 'string' && typeof targetCategory === 'string'
      && filledCategories.has(sourceCategory) && filledCategories.has(targetCategory);
  });
  return nodeFail || edgeFail || blockingToolFailures(graph, items).length > 0;
}

function quoteStatusByCategory(graph: BuildGraphResolveResponse | undefined) {
  const statusMap = new Map<PartCategory, 'PASS' | 'WARN' | 'FAIL'>();
  if (!graph) {
    return statusMap;
  }
  const categoryByNodeId = new Map<string, PartCategory>();
  const promote = (category: PartCategory | undefined, status: string) => {
    if (!category || (status !== 'PASS' && status !== 'WARN' && status !== 'FAIL')) {
      return;
    }
    const current = statusMap.get(category);
    if (!current || statusRank(status) > statusRank(current)) {
      statusMap.set(category, status);
    }
  };

  graph.nodes.forEach((node) => {
    const category = typeof node.category === 'string' && isSlotCategory(node.category) ? node.category : undefined;
    if (category) {
      categoryByNodeId.set(node.id, category);
    }
    if (node.type === 'PART') {
      promote(category, node.status);
    }
  });

  graph.edges.forEach((edge) => {
    if (edge.status !== 'WARN' && edge.status !== 'FAIL') {
      return;
    }
    promote(categoryByNodeId.get(edge.source), edge.status);
    promote(categoryByNodeId.get(edge.target), edge.status);
  });

  graph.insights.forEach((insight) => {
    if (insight.status !== 'WARN' && insight.status !== 'FAIL') {
      return;
    }
    insight.relatedNodeIds.forEach((nodeId) => promote(categoryByNodeId.get(nodeId), insight.status));
  });

  return statusMap;
}

function statusRank(status: 'PASS' | 'WARN' | 'FAIL') {
  if (status === 'FAIL') return 3;
  if (status === 'WARN') return 2;
  return 1;
}

function graphToolForCategory(category: PartCategory): BuildGraphFocus['tool'] {
  if (category === 'GPU' || category === 'PSU') return 'power';
  if (category === 'CASE' || category === 'COOLER') return 'size';
  if (category === 'CPU' || category === 'MOTHERBOARD' || category === 'RAM') return 'compatibility';
  return undefined;
}

function QuoteSummaryBar({
  totalPrice,
  filledCount,
  slotCount,
  warnCount,
  failCount,
  unmetConditionCount,
  graphLoading,
  graphError,
  checkoutActions
}: {
  totalPrice: number;
  filledCount: number;
  slotCount: number;
  warnCount: number;
  failCount: number;
  unmetConditionCount: number;
  graphLoading: boolean;
  graphError: boolean;
  checkoutActions?: ReactNode;
}) {
  // '조건 미충족' = 빨간 부품 노드는 없지만 검사(예: 파워 용량)가 FAIL — 구매 차단과 표기를 일치시킨다.
  // 검증 자체가 실패하면(미검증) 정상으로 오인하지 않도록 회색 '검증 확인 불가'로 구분한다.
  const isEmpty = filledCount === 0;
  const hasRedState = !isEmpty && !graphError && (failCount > 0 || unmetConditionCount > 0);
  const compatibilityText = isEmpty
    ? '부품 없음'
    : graphLoading
      ? '확인 중'
      : graphError
        ? '검증 확인 불가'
        : failCount > 0
          ? `장착 불가 ${failCount}개`
          : unmetConditionCount > 0
            ? '조건 미충족'
            : warnCount > 0
              ? `주의 ${warnCount}개`
              : '이상 없음';
  const compatibilityColor = isEmpty
    ? 'text-slate-400'
    : hasRedState
      ? 'text-red-600'
      : graphError
        ? 'text-slate-400'
        : warnCount > 0
          ? 'text-amber-600'
          : 'text-emerald-600';
  return (
    <div data-testid="quote-summary-bar" className="grid grid-cols-2 gap-2 sm:grid-cols-[repeat(3,minmax(0,1fr))_auto] lg:grid-cols-[clamp(256px,18vw,320px)_minmax(0,1fr)_minmax(0,1fr)_auto] lg:gap-2">
      <div className="panel flex min-h-12 items-center gap-3 px-4 py-3 lg:gap-2.5 lg:px-2.5 lg:py-1.5">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-blue-50 text-xl font-black text-brand-blue lg:h-7 lg:w-7 lg:text-base">₩</span>
        <div className="min-w-0">
          <div className="text-[11px] font-bold text-slate-500">총액</div>
          <div className="truncate text-sm font-black text-commerce-ink">{totalPrice > 0 ? `${totalPrice.toLocaleString()}원` : '—'}</div>
        </div>
      </div>
      <div className="panel flex min-h-12 items-center gap-3 px-4 py-3 lg:gap-2.5 lg:px-2.5 lg:py-1.5">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-600 lg:h-7 lg:w-7">
          <svg viewBox="0 0 20 20" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="3" y="3" width="6" height="6" rx="1" /><rect x="11" y="3" width="6" height="6" rx="1" />
            <rect x="3" y="11" width="6" height="6" rx="1" /><rect x="11" y="11" width="6" height="6" rx="1" />
          </svg>
        </span>
        <div className="min-w-0">
          <div className="text-[11px] font-bold text-slate-500">장착 슬롯</div>
          <div className="text-sm font-black text-commerce-ink">{filledCount} / {slotCount}</div>
        </div>
      </div>
      <div className="panel flex min-h-12 items-center gap-3 px-4 py-3 lg:gap-2.5 lg:px-2.5 lg:py-1.5">
        <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg lg:h-7 lg:w-7 ${isEmpty ? 'bg-slate-100' : hasRedState ? 'bg-red-50' : graphError ? 'bg-slate-100' : warnCount > 0 ? 'bg-amber-50' : 'bg-emerald-50'}`}>
          <svg viewBox="0 0 20 20" className={`h-5 w-5 ${isEmpty ? 'text-slate-400' : hasRedState ? 'text-red-500' : graphError ? 'text-slate-400' : warnCount > 0 ? 'text-amber-500' : 'text-emerald-500'}`} fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M10 2a8 8 0 100 16A8 8 0 0010 2zm0 5v4m0 2.5v.5" strokeLinecap="round" />
          </svg>
        </span>
        <div className="min-w-0">
          <div className="text-[11px] font-bold text-slate-500">호환 상태</div>
          <div className={`text-sm font-black ${compatibilityColor}`}>{compatibilityText}</div>
        </div>
      </div>
      {checkoutActions ? (
        <div data-testid="quote-checkout-actions" className="panel col-span-2 flex min-h-12 items-center justify-end gap-1.5 px-2 py-1.5 sm:col-span-1">
          {checkoutActions}
        </div>
      ) : null}
    </div>
  );
}
