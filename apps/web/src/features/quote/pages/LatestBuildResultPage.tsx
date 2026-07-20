import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  Background,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { X } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';
import { Panel, Screen, StateMessage, StatusBadge } from '../../../components/ui';
import { useHiddenPageScrollbar } from '../../../hooks/useHiddenPageScrollbar';
import { BENCHMARK_REFERENCE_NOTICE } from '../../../lib/disclaimers';
import { latestUserMessage, temporaryBuildToBuildSummary } from '../components/BuildDetailSections';
import {
  AI_ASSISTANT_BUILD_HISTORY_LIMIT,
  markAssistantBuildSaved,
  normalizeAiRecommendedBuild,
  readAssistantSession,
  saveSelectedAiBuild,
  type AiBuildTier,
  type AiRecommendedBuild,
  type BuildGraphResolveResponse,
  type BuildGraphStatus
} from '../aiSelection';
import { buildSaveErrorMessage, resolveBuildGraph, saveBuildFromChat } from '../quoteApi';

type RecommendationFilter = 'all' | AiBuildTier;
type CompactGraphNodeData = {
  label: string;
  category?: string;
  status: BuildGraphStatus;
};
type BuildHistoryEntry = {
  key: string;
  build: AiRecommendedBuild;
};
type SaveLatestBuildVariables = {
  key: string;
  build: AiRecommendedBuild;
};
type GraphPreviewAnchor = {
  left: number;
  top: number;
  width: number;
  placement: 'left' | 'right';
};
const compactGraphNodeTypes = { compactGraphNode: CompactGraphNode };
const COMPACT_GRAPH_STALE_TIME_MS = 5 * 60 * 1000;
const GRAPH_PREVIEW_PANEL_WIDTH = 760;
const GRAPH_PREVIEW_PANEL_HEIGHT = 540;
const GRAPH_PREVIEW_GAP = 12;
const GRAPH_PREVIEW_MARGIN = 16;
const compactCategoryPositions: Record<string, { x: number; y: number }> = {
  CPU: { x: 0, y: 120 },
  MOTHERBOARD: { x: 230, y: 18 },
  RAM: { x: 480, y: 20 },
  GPU: { x: 230, y: 154 },
  PSU: { x: 480, y: 150 },
  CASE: { x: 480, y: 278 },
  COOLER: { x: 230, y: 296 },
  STORAGE: { x: 0, y: 296 },
  PRICE: { x: 0, y: 420 }
};

export function LatestBuildResultPage() {
  useHiddenPageScrollbar();

  const navigate = useNavigate();
  const assistantSession = readAssistantSession();
  const builds = assistantSession.latestBuilds;
  const buildEntries = useMemo(() => createBuildHistoryEntries(builds), [builds]);
  const buildIdCounts = useMemo(() => countBuildIds(buildEntries), [buildEntries]);
  const [selectedBuildKey, setSelectedBuildKey] = useState<string | null>(null);
  const [previewBuildKey, setPreviewBuildKey] = useState<string | null>(null);
  const [tierFilter, setTierFilter] = useState<RecommendationFilter>('all');
  const [savedBuildIds, setSavedBuildIds] = useState(assistantSession.savedBuildIds);
  const [canShowGraphPreview, setCanShowGraphPreview] = useState(false);
  const [previewAnchor, setPreviewAnchor] = useState<GraphPreviewAnchor | null>(null);
  const previewOpenTimerRef = useRef<number | null>(null);
  const previewCloseTimerRef = useRef<number | null>(null);
  const visibleBuildEntries = useMemo(
    () => tierFilter === 'all' ? buildEntries : buildEntries.filter((entry) => entry.build.tier === tierFilter),
    [buildEntries, tierFilter]
  );
  const selectedEntry = selectedBuildKey
    ? visibleBuildEntries.find((entry) => entry.key === selectedBuildKey)
    : undefined;
  const selectedBuild = selectedEntry?.build;
  const previewEntry = !selectedBuild && previewAnchor && canShowGraphPreview && previewBuildKey
    ? visibleBuildEntries.find((entry) => entry.key === previewBuildKey)
    : undefined;
  const previewBuild = previewEntry?.build;
  const savedBuildIdForEntry = useCallback((entry: BuildHistoryEntry) => (
    savedBuildIds[entry.key]
    ?? (buildIdCounts.get(entry.build.id) === 1 ? savedBuildIds[entry.build.id] : undefined)
  ), [buildIdCounts, savedBuildIds]);
  const selectedSavedBuildId = selectedEntry ? savedBuildIdForEntry(selectedEntry) : undefined;
  const lastUserMessage = latestUserMessage(assistantSession);
  const closeDetail = useCallback(() => {
    setSelectedBuildKey(null);
  }, []);
  const clearPreviewOpenTimer = useCallback(() => {
    if (previewOpenTimerRef.current !== null) {
      window.clearTimeout(previewOpenTimerRef.current);
      previewOpenTimerRef.current = null;
    }
  }, []);
  const clearPreviewCloseTimer = useCallback(() => {
    if (previewCloseTimerRef.current !== null) {
      window.clearTimeout(previewCloseTimerRef.current);
      previewCloseTimerRef.current = null;
    }
  }, []);
  const openPreview = useCallback((buildKey: string, anchorElement: HTMLElement) => {
    if (!canShowGraphPreview) return;
    clearPreviewOpenTimer();
    clearPreviewCloseTimer();
    previewOpenTimerRef.current = window.setTimeout(() => {
      if (!anchorElement.isConnected) return;
      setPreviewAnchor(getGraphPreviewAnchor(anchorElement.getBoundingClientRect()));
      setPreviewBuildKey(buildKey);
      previewOpenTimerRef.current = null;
    }, 180);
  }, [canShowGraphPreview, clearPreviewCloseTimer, clearPreviewOpenTimer]);
  const schedulePreviewClose = useCallback(() => {
    clearPreviewOpenTimer();
    clearPreviewCloseTimer();
    previewCloseTimerRef.current = window.setTimeout(() => {
      setPreviewBuildKey(null);
      setPreviewAnchor(null);
      previewCloseTimerRef.current = null;
    }, 120);
  }, [clearPreviewCloseTimer, clearPreviewOpenTimer]);
  const selectBuild = useCallback((buildKey: string) => {
    clearPreviewOpenTimer();
    clearPreviewCloseTimer();
    setPreviewBuildKey(null);
    setPreviewAnchor(null);
    setSelectedBuildKey(buildKey);
  }, [clearPreviewCloseTimer, clearPreviewOpenTimer]);
  const saveMutation = useMutation({
    mutationFn: ({ build }: SaveLatestBuildVariables) => saveBuildFromChat({
      sourceBuildId: build.id,
      lastUserMessage,
      build
    }),
    onSuccess: (response, source) => {
      markAssistantBuildSaved(source.build.id, response.id);
      if (source.key !== source.build.id) {
        markAssistantBuildSaved(source.key, response.id);
      }
      setSavedBuildIds((current) => ({
        ...current,
        [source.build.id]: response.id,
        [source.key]: response.id
      }));
    }
  });
  const openSelfQuoteFromGraph = useCallback((build: AiRecommendedBuild) => {
    saveSelectedAiBuild(normalizeAiRecommendedBuild(build));
    navigate('/self-quote');
  }, [navigate]);

  useEffect(() => {
    const mediaQuery = window.matchMedia('(min-width: 768px)');
    const syncPreviewAvailability = () => setCanShowGraphPreview(mediaQuery.matches);
    syncPreviewAvailability();
    mediaQuery.addEventListener('change', syncPreviewAvailability);
    return () => mediaQuery.removeEventListener('change', syncPreviewAvailability);
  }, []);

  useEffect(() => {
    return () => {
      clearPreviewOpenTimer();
      clearPreviewCloseTimer();
    };
  }, [clearPreviewCloseTimer, clearPreviewOpenTimer]);

  useEffect(() => {
    if (selectedBuildKey && !visibleBuildEntries.some((entry) => entry.key === selectedBuildKey)) {
      setSelectedBuildKey(null);
    }
    if (previewBuildKey && !visibleBuildEntries.some((entry) => entry.key === previewBuildKey)) {
      setPreviewBuildKey(null);
      setPreviewAnchor(null);
    }
  }, [previewBuildKey, selectedBuildKey, visibleBuildEntries]);

  useEffect(() => {
    if (!canShowGraphPreview || selectedBuild) {
      setPreviewBuildKey(null);
      setPreviewAnchor(null);
    }
  }, [canShowGraphPreview, selectedBuild]);

  return (
    <Screen>
      <div
        data-testid="latest-build-results-layout"
        className="space-y-5"
      >
        <Panel
          title="추천 결과"
          subtitle="AI 챗봇이 방금 제안한 임시 추천 조합입니다. 저장 전까지 내 견적함에는 추가되지 않습니다."
        >
          {builds.length > 0 ? (
            <div className="space-y-4">
              <div className="flex flex-col gap-3 rounded-md border border-blue-100 bg-blue-50 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
                <p className="text-sm font-bold text-brand-blue">
                  최근 AI 추천 조합을 최대 {AI_ASSISTANT_BUILD_HISTORY_LIMIT}개까지 보관합니다. 현재 {builds.length}/{AI_ASSISTANT_BUILD_HISTORY_LIMIT}개
                </p>
                <span className="text-xs font-semibold text-slate-500">최신 추천이 앞에 표시됩니다.</span>
              </div>
              <RecommendationFilterTabs value={tierFilter} onChange={setTierFilter} />
              {visibleBuildEntries.length > 0 ? (
                <div
                  data-testid="latest-build-card-grid"
                  className={`grid gap-4 ${selectedBuild ? 'lg:grid-cols-2' : 'lg:grid-cols-3'}`}
                >
                  {visibleBuildEntries.map((entry) => (
                    <TemporaryBuildCard
                      key={entry.key}
                      build={entry.build}
                      selected={entry.key === selectedBuildKey}
                      savedBuildId={savedBuildIdForEntry(entry)}
                      onSelect={() => selectBuild(entry.key)}
                      onPreviewOpen={(anchorElement) => openPreview(entry.key, anchorElement)}
                      onPreviewClose={schedulePreviewClose}
                    />
                  ))}
                </div>
              ) : (
                <StateMessage
                  type="info"
                  title="선택한 필터에 해당하는 추천 조합이 없습니다."
                  body="전체 필터로 돌아가면 최근 AI 추천 조합을 모두 확인할 수 있습니다."
                />
              )}
            </div>
          ) : (
            <div className="space-y-4">
              <StateMessage
                type="info"
                title="AI 챗봇에게 먼저 추천을 받아보세요"
                body="홈 AI 챗봇에서 예산이나 용도를 말하면 추천 결과가 이곳에 임시로 표시됩니다."
              />
              <Link to="/?assistant=open" className="inline-flex min-h-10 items-center justify-center rounded-md bg-brand-blue px-4 text-sm font-bold text-white hover:bg-blue-700 focus:outline-none focus:ring-4 focus:ring-blue-100">
                홈에서 AI 챗봇 열기
              </Link>
            </div>
          )}
        </Panel>
        {selectedBuild ? (
          <LatestBuildDetailDrawer
            build={selectedBuild}
            savedBuildId={selectedSavedBuildId}
            isSaving={saveMutation.isPending && saveMutation.variables?.key === selectedEntry?.key}
            saveErrorMessage={saveMutation.isError && saveMutation.variables?.key === selectedEntry?.key
              ? buildSaveErrorMessage(saveMutation.error)
              : undefined}
            onSave={() => selectedEntry ? saveMutation.mutate(selectedEntry) : undefined}
            onGraphCardClick={() => openSelfQuoteFromGraph(selectedBuild)}
            onClose={closeDetail}
          />
        ) : null}
        {previewBuild && previewAnchor ? (
          <BuildGraphPreviewPanel
            build={previewBuild}
            anchor={previewAnchor}
            onMouseEnter={clearPreviewCloseTimer}
            onMouseLeave={schedulePreviewClose}
          />
        ) : null}
      </div>
    </Screen>
  );
}

function LatestBuildDetailDrawer({
  build,
  savedBuildId,
  isSaving,
  saveErrorMessage,
  onSave,
  onGraphCardClick,
  onClose
}: {
  build: AiRecommendedBuild;
  savedBuildId?: string;
  isSaving: boolean;
  saveErrorMessage?: string;
  onSave: () => void;
  onGraphCardClick: () => void;
  onClose: () => void;
}) {
  const desktopPanelRef = useRef<HTMLElement | null>(null);
  const displayBuild = temporaryBuildToBuildSummary(build);

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose();
      }
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  useEffect(() => {
    function handlePointerDown(event: PointerEvent) {
      const target = event.target;
      if (target instanceof Element && target.closest('[data-latest-build-card], [data-latest-build-filter]')) return;
      if (target instanceof Node && desktopPanelRef.current && !desktopPanelRef.current.contains(target)) {
        onClose();
      }
    }
    document.addEventListener('pointerdown', handlePointerDown);
    return () => document.removeEventListener('pointerdown', handlePointerDown);
  }, [onClose]);

  return (
    <>
      <section
        ref={desktopPanelRef}
        role="dialog"
        aria-modal="false"
        aria-label="추천 조합 상세"
        data-testid="latest-build-detail-drawer"
        className="fixed inset-y-0 right-0 z-[80] flex h-dvh w-full max-w-[520px] flex-col border-l border-commerce-line bg-white shadow-2xl"
      >
        <LatestBuildDetailPanelContent
          build={build}
          displayBuild={displayBuild}
          savedBuildId={savedBuildId}
          isSaving={isSaving}
          saveErrorMessage={saveErrorMessage}
          onSave={onSave}
          onGraphCardClick={onGraphCardClick}
          onClose={onClose}
        />
      </section>
    </>
  );
}

function LatestBuildDetailPanelContent({
  build,
  displayBuild,
  savedBuildId,
  isSaving,
  saveErrorMessage,
  onSave,
  onGraphCardClick,
  onClose
}: {
  build: AiRecommendedBuild;
  displayBuild: ReturnType<typeof temporaryBuildToBuildSummary>;
  savedBuildId?: string;
  isSaving: boolean;
  saveErrorMessage?: string;
  onSave: () => void;
  onGraphCardClick: () => void;
  onClose: () => void;
}) {
  const toolResults = displayBuild.toolResults ?? [];
  const passCount = toolResults.filter((row) => row.status === 'PASS').length;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="flex items-start justify-between gap-4 border-b border-commerce-line px-5 py-4">
        <div className="min-w-0">
          <div className="text-xs font-black text-brand-blue">추천 조합 상세</div>
          <h2 className="mt-1 break-keep text-lg font-black leading-7 text-commerce-ink">
            선택한 추천 조합 / {build.title}
          </h2>
          <p className="mt-1 text-xs font-semibold leading-5 text-slate-500">
            구성 부품, 검증 결과, 저장 액션을 같은 자리에서 확인합니다.
          </p>
        </div>
        <button
          type="button"
          aria-label="추천 조합 상세 닫기"
          onClick={onClose}
          className="grid h-9 w-9 shrink-0 place-items-center rounded-md border border-commerce-line bg-white text-slate-500 hover:border-slate-300 hover:text-commerce-ink focus:outline-none focus:ring-4 focus:ring-blue-100"
        >
          <X size={18} aria-hidden="true" />
        </button>
      </header>

      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-5 py-4">
        <div className="rounded-md border border-blue-100 bg-blue-50 px-4 py-3 text-sm font-bold text-brand-blue">
          저장 전 AI 챗봇 추천
        </div>

        <BuildGraphInlineSection
          build={build}
          onGraphCardClick={onGraphCardClick}
        />

        <section className="rounded-md border border-commerce-line bg-white">
          <div className="border-b border-commerce-line px-4 py-3">
            <h3 className="text-sm font-black text-commerce-ink">구성 부품</h3>
          </div>
          <div className="divide-y divide-commerce-line">
            {displayBuild.items.map((item) => (
              <div key={`${displayBuild.id}-${item.category}`} className="px-4 py-3">
                <div className="flex items-start justify-between gap-3">
                  <span className="text-xs font-black text-slate-500">{labelForCategory(item.category)}</span>
                  <span className="whitespace-nowrap text-sm font-black text-brand-blue">{item.price.toLocaleString()}원</span>
                </div>
                <div className="mt-1 text-sm font-black leading-5 text-commerce-ink">
                  {item.partId ? (
                    <Link to={`/parts/${item.partId}`} className="hover:text-brand-blue hover:underline" title={item.name}>{item.name}</Link>
                  ) : (
                    item.name
                  )}
                </div>
                <div className="mt-1 text-xs font-semibold text-slate-500">{item.manufacturer ?? '-'}</div>
              </div>
            ))}
          </div>
        </section>

        <section className="rounded-md border border-commerce-line bg-white">
          <div className="border-b border-commerce-line px-4 py-3">
            <h3 className="text-sm font-black text-commerce-ink">검증 결과</h3>
          </div>
          <div className="space-y-3 p-4">
            {toolResults.length > 0 ? (
              <>
                <div className={`rounded-md border px-3 py-2 text-xs font-black ${
                  passCount === toolResults.length
                    ? 'border-emerald-100 bg-emerald-50 text-emerald-700'
                    : 'border-amber-100 bg-amber-50 text-amber-700'
                }`}
                >
                  {passCount === toolResults.length
                    ? `${toolResults.length}개 검증 모두 통과`
                    : `${toolResults.length}개 검증 중 ${passCount}개 통과 · 경고 항목을 확인하세요`}
                </div>
                <div className="space-y-2">
                  {toolResults.map((row) => (
                    <div key={`${row.tool}-${row.summary}`} className="rounded-md border border-slate-100 bg-slate-50 px-3 py-2">
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-xs font-black text-slate-600">{toolDisplayLabel(row.tool)}</span>
                        <span className="flex items-center gap-1">
                          <StatusBadge status={row.status} />
                          <StatusBadge status={row.confidence} />
                        </span>
                      </div>
                      <p className="mt-2 text-xs leading-5 text-slate-600">{row.summary}</p>
                    </div>
                  ))}
                </div>
                <p className="break-keep text-[11px] font-bold leading-5 text-slate-500">{BENCHMARK_REFERENCE_NOTICE}</p>
              </>
            ) : (
              <StateMessage type="info" title="검증 결과 없음" body="저장 버튼을 누르면 서버에서 다시 검증한 뒤 견적으로 저장합니다." />
            )}
          </div>
        </section>

        <section className="rounded-md border border-commerce-line bg-white">
          <div className="border-b border-commerce-line px-4 py-3">
            <h3 className="text-sm font-black text-commerce-ink">견적 요약 / 액션</h3>
          </div>
          <div className="space-y-4 p-4">
            <div className="rounded-md border border-blue-100 bg-blue-50 px-4 py-3">
              <div className="text-xs font-black text-slate-500">총액</div>
              <div className="mt-1 text-2xl font-black text-brand-blue">{displayBuild.totalPrice.toLocaleString()}원</div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="rounded-md border border-commerce-line px-3 py-2">
                <div className="text-xs font-black text-slate-500">부품 수</div>
                <div className="mt-1 text-lg font-black text-commerce-ink">{displayBuild.items.length}개</div>
              </div>
              <div className="rounded-md border border-commerce-line px-3 py-2">
                <div className="text-xs font-black text-slate-500">경고</div>
                <div className="mt-1 text-lg font-black text-commerce-ink">{displayBuild.warnings.length > 0 ? `${displayBuild.warnings.length}건` : '없음'}</div>
              </div>
            </div>
            <StateMessage
              type={displayBuild.warnings.length > 0 ? 'warn' : 'success'}
              title={displayBuild.warnings.length > 0 ? '확인 필요' : '주요 조건 충족'}
              body={displayBuild.warnings[0]?.message ?? '저장 버튼을 누르면 서버에서 다시 검증한 뒤 견적으로 저장합니다.'}
            />
            {savedBuildId ? (
              <div className="space-y-2">
                <StateMessage type="success" title="내 견적함에 저장되었습니다." body="내 견적함에서 저장된 견적을 다시 확인할 수 있습니다." />
                <button
                  type="button"
                  disabled
                  className="block w-full rounded bg-emerald-50 px-4 py-3 text-center text-sm font-bold text-emerald-700"
                >
                  저장됨
                </button>
                <Link to="/my/quotes" className="block rounded bg-brand-blue px-4 py-3 text-center text-sm font-bold text-white hover:bg-blue-700">내 견적함 보기</Link>
              </div>
            ) : (
              <div className="space-y-2">
                <button
                  type="button"
                  onClick={onSave}
                  disabled={isSaving}
                  className="block w-full rounded bg-brand-blue px-4 py-3 text-center text-sm font-bold text-white hover:bg-blue-700 disabled:cursor-wait disabled:bg-slate-400"
                >
                  {isSaving ? '저장 중' : '견적 저장'}
                </button>
                {saveErrorMessage ? (
                  <StateMessage type="warn" title="견적 저장 실패" body={saveErrorMessage} />
                ) : null}
              </div>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}

function RecommendationFilterTabs({
  value,
  onChange
}: {
  value: RecommendationFilter;
  onChange: (value: RecommendationFilter) => void;
}) {
  const filters: Array<{ value: RecommendationFilter; label: string }> = [
    { value: 'all', label: '전체' },
    { value: 'budget', label: '실속형' },
    { value: 'balanced', label: '균형형' },
    { value: 'performance', label: '성능형' }
  ];

  return (
    <div role="group" aria-label="추천 조합 필터" data-latest-build-filter="true" className="flex flex-wrap gap-2">
      {filters.map((filter) => {
        const active = value === filter.value;
        return (
          <button
            key={filter.value}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(filter.value)}
            className={`min-h-9 rounded-md border px-3 text-sm font-bold transition focus:outline-none focus:ring-4 focus:ring-blue-100 ${
              active
                ? 'border-brand-blue bg-brand-blue text-white shadow-product'
                : 'border-slate-200 bg-white text-slate-600 hover:border-blue-200 hover:text-brand-blue'
            }`}
          >
            {filter.label}
          </button>
        );
      })}
    </div>
  );
}

function TemporaryBuildCard({
  build,
  savedBuildId,
  selected,
  onSelect,
  onPreviewOpen,
  onPreviewClose
}: {
  build: AiRecommendedBuild;
  savedBuildId?: string;
  selected: boolean;
  onSelect: () => void;
  onPreviewOpen: (anchorElement: HTMLElement) => void;
  onPreviewClose: () => void;
}) {
  const primaryWarning = build.warnings?.[0];
  const mainItems = build.items.slice(0, 5);

  return (
    <article
      data-latest-build-card="true"
      onMouseEnter={(event) => onPreviewOpen(event.currentTarget)}
      onMouseLeave={onPreviewClose}
      onFocus={(event) => onPreviewOpen(event.currentTarget)}
      onBlur={(event) => {
        const nextTarget = event.relatedTarget;
        if (!(nextTarget instanceof Node) || !event.currentTarget.contains(nextTarget)) {
          onPreviewClose();
        }
      }}
      className={`rounded-lg border bg-white p-4 shadow-product transition ${selected ? 'border-brand-blue ring-2 ring-blue-100' : 'border-slate-200 hover:border-blue-200'}`}
    >
      <button
        type="button"
        onClick={onSelect}
        aria-pressed={selected}
        className="block w-full text-left focus:outline-none focus:ring-4 focus:ring-blue-100"
      >
        <div className="rounded border border-blue-100 bg-blue-50 p-3">
          <div className="flex items-start justify-between gap-3">
            <div>
              <div className="text-xs font-black text-brand-blue">{build.tierLabel}</div>
              <h3 className="mt-1 text-lg font-black text-commerce-ink">{build.title}</h3>
            </div>
          </div>
          <p className="mt-2 min-h-10 text-xs leading-5 text-slate-600">{build.summary}</p>
        </div>
        <div className="mt-4 text-2xl font-black text-brand-blue">{build.totalPrice.toLocaleString()}원</div>
        <div className="mt-2 text-xs font-semibold text-slate-500">
          {primaryWarning ?? '저장 전 서버에서 다시 자동 검증합니다.'}
        </div>
        <div className="mt-4 space-y-2">
          {mainItems.map((item) => (
            <div key={`${build.id}-${item.category}`} className="flex items-center justify-between gap-2 text-xs">
              <span className="w-20 font-bold text-slate-500">{labelForCategory(item.category)}</span>
              <span className="flex-1 truncate text-slate-800" title={item.name}>{item.name}</span>
            </div>
          ))}
        </div>
      </button>
      <div className="mt-4 flex gap-2">
        <button type="button" onClick={onSelect} className="rounded bg-brand-blue px-3 py-2 text-xs font-bold text-white hover:bg-blue-700">
          상세 보기
        </button>
        {savedBuildId ? (
          <span className="rounded border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs font-bold text-emerald-700" title={savedBuildId}>
            저장됨
          </span>
        ) : null}
      </div>
    </article>
  );
}

function BuildGraphPreviewPanel({
  build,
  anchor,
  onMouseEnter,
  onMouseLeave
}: {
  build: AiRecommendedBuild;
  anchor: GraphPreviewAnchor;
  onMouseEnter: () => void;
  onMouseLeave: () => void;
}) {
  const graphQuery = useRecommendationBuildGraph(build);
  const issueCount = graphPreviewIssueCount(build);
  const statusTone: BuildGraphStatus = issueCount > 0 ? 'WARN' : 'PASS';
  const statusText = issueCount > 0 ? `주의 필요 ${issueCount}건` : '주요 관계 확인됨';

  return (
    <aside
      data-testid="latest-build-graph-preview"
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
      style={{ left: anchor.left, top: anchor.top, width: anchor.width }}
      className="pointer-events-none fixed z-[70] hidden h-[540px] overflow-hidden rounded-lg border border-commerce-line bg-white shadow-2xl md:block"
    >
      <div
        aria-hidden="true"
        className={`absolute top-8 h-3 w-3 rotate-45 border-commerce-line bg-white ${
          anchor.placement === 'right'
            ? '-left-1.5 border-b border-l'
            : '-right-1.5 border-r border-t'
        }`}
      />
      <div className="border-b border-commerce-line bg-white px-4 py-3">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="text-[11px] font-black text-brand-blue">{build.tierLabel}</div>
            <h2 className="mt-1 truncate text-sm font-black text-commerce-ink" title={build.title}>견적 관계도 · {build.title}</h2>
          </div>
          <span className={`shrink-0 rounded-full border px-2 py-1 text-[11px] font-black ${graphPreviewStatusClasses(statusTone)}`}>
            {statusText}
          </span>
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] font-black">
          <span className="rounded-full bg-blue-50 px-2.5 py-1 text-brand-blue">총액 {build.totalPrice.toLocaleString()}원</span>
          <span className="rounded-full bg-slate-100 px-2.5 py-1 text-slate-600">부품 {build.items.length}개</span>
          <span className={`rounded-full px-2.5 py-1 ${issueCount > 0 ? 'bg-amber-50 text-amber-700' : 'bg-emerald-50 text-emerald-700'}`}>
            경고 {issueCount}건
          </span>
        </div>
      </div>
      <div className="mx-4 mt-3 overflow-hidden rounded-md border border-slate-100">
        <CompactBuildGraph
          graph={graphQuery.data}
          isLoading={graphQuery.isLoading}
          isError={graphQuery.isError}
          heightClassName="h-[448px]"
          includePriceNode={false}
        />
      </div>
    </aside>
  );
}

function graphPreviewIssueCount(build: AiRecommendedBuild) {
  return build.warnings?.length ?? 0;
}

function graphPreviewStatusClasses(status: BuildGraphStatus) {
  switch (status) {
    case 'FAIL':
      return 'border-red-200 bg-red-50 text-red-700';
    case 'WARN':
      return 'border-amber-200 bg-amber-50 text-amber-700';
    default:
      return 'border-emerald-100 bg-emerald-50 text-emerald-700';
  }
}

function BuildGraphInlineSection({
  build,
  onGraphCardClick
}: {
  build: AiRecommendedBuild;
  onGraphCardClick?: () => void;
}) {
  const graphQuery = useRecommendationBuildGraph(build);

  const handleKeyDown = useCallback((event: ReactKeyboardEvent<HTMLElement>) => {
    if (!onGraphCardClick) return;
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onGraphCardClick();
    }
  }, [onGraphCardClick]);

  return (
    <section
      role={onGraphCardClick ? 'button' : undefined}
      tabIndex={onGraphCardClick ? 0 : undefined}
      aria-label={onGraphCardClick ? '견적 관계도에서 셀프 견적으로 이동' : undefined}
      onClick={onGraphCardClick}
      onKeyDown={handleKeyDown}
      className={`rounded-md border border-commerce-line bg-white ${
        onGraphCardClick
          ? 'cursor-pointer transition hover:border-brand-blue hover:shadow-product focus:outline-none focus:ring-4 focus:ring-blue-100'
          : ''
      }`}
      data-testid="latest-build-detail-graph"
    >
      <div className="border-b border-commerce-line px-4 py-3">
        <h3 className="text-sm font-black text-commerce-ink">견적 관계도</h3>
        <p className="mt-1 text-xs font-semibold text-slate-500">
          선택한 추천 조합의 부품 관계를 확인하고, 카드를 누르면 셀프 견적으로 이동합니다.
        </p>
      </div>
      <CompactBuildGraph
        graph={graphQuery.data}
        isLoading={graphQuery.isLoading}
        isError={graphQuery.isError}
        heightClassName="h-[280px]"
        includePriceNode
        isCardLink={Boolean(onGraphCardClick)}
      />
    </section>
  );
}

function CompactBuildGraph({
  graph,
  isLoading,
  isError,
  heightClassName,
  includePriceNode = true,
  isCardLink = false
}: {
  graph?: BuildGraphResolveResponse;
  isLoading: boolean;
  isError: boolean;
  heightClassName: string;
  includePriceNode?: boolean;
  isCardLink?: boolean;
}) {
  const flowElements = useMemo(
    () => graph ? toCompactFlowElements(graph, includePriceNode) : { nodes: [], edges: [] },
    [graph, includePriceNode]
  );

  if (isLoading && !graph) {
    return (
      <div className={`${heightClassName} grid place-items-center bg-[linear-gradient(180deg,#f8fafc_0%,#ffffff_100%)] text-sm font-bold text-slate-500`}>
        관계도를 불러오는 중입니다.
      </div>
    );
  }

  if (isError && !graph) {
    return (
      <div className={`${heightClassName} grid place-items-center bg-orange-50 p-4 text-center text-sm font-bold text-orange-700`}>
        관계도를 불러오지 못했습니다.
      </div>
    );
  }

  if (flowElements.nodes.length === 0) {
    return (
      <div className={`${heightClassName} grid place-items-center bg-slate-50 p-4 text-center text-sm font-bold text-slate-500`}>
        표시할 관계도 노드가 없습니다.
      </div>
    );
  }

  return (
    <div className={`${heightClassName} bg-[linear-gradient(180deg,#f8fafc_0%,#ffffff_100%)] ${isCardLink ? 'compact-build-graph--card-link' : ''}`}>
      <ReactFlow
        className="buildgraph-scroll-pass-through"
        nodes={flowElements.nodes}
        edges={flowElements.edges}
        nodeTypes={compactGraphNodeTypes}
        fitView
        fitViewOptions={{ padding: 0.12 }}
        minZoom={0.18}
        maxZoom={1.1}
        preventScrolling={false}
        zoomOnScroll={false}
        zoomOnPinch={false}
        panOnScroll={false}
        panOnDrag={false}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
        proOptions={{ hideAttribution: true }}
      >
        <Background color="#dbe4f0" gap={16} />
      </ReactFlow>
    </div>
  );
}

function CompactGraphNode({ data }: NodeProps) {
  const nodeData = data as CompactGraphNodeData;
  const nodeClassName = `relative flex h-[78px] w-44 flex-col justify-between rounded-lg border bg-white px-3 py-2 text-left shadow-sm ${compactNodeClasses(nodeData.status)}`;

  return (
    <div className={nodeClassName}>
      <Handle type="target" position={Position.Left} className="!h-1.5 !w-1.5 !border-white !bg-slate-300 !opacity-60" />
      <Handle type="source" position={Position.Right} className="!h-1.5 !w-1.5 !border-white !bg-slate-300 !opacity-60" />
      <div className="flex items-start justify-between gap-2">
        <div className="truncate text-[10px] font-black text-brand-blue">{nodeData.category ?? '부품'}</div>
        <div className={`shrink-0 rounded-full px-1.5 py-0.5 text-[9px] font-black ${compactNodeStatusClasses(nodeData.status)}`}>
          {compactStatusLabel(nodeData.status)}
        </div>
      </div>
      <div className="line-clamp-2 text-[12px] font-black leading-4 text-commerce-ink" title={nodeData.label}>
        {nodeData.label}
      </div>
    </div>
  );
}

function GraphToolSummary({ graph }: { graph?: BuildGraphResolveResponse }) {
  const toolResults = graph?.toolResults ?? [];
  if (toolResults.length === 0) return null;
  const passCount = toolResults.filter((row) => row.status === 'PASS').length;

  return (
    <section className="rounded-md border border-commerce-line bg-white p-4">
      <div className="flex items-center justify-between gap-3">
        <h3 className="text-sm font-black text-commerce-ink">검증 요약</h3>
        <span className="text-xs font-black text-slate-500">{passCount}/{toolResults.length} 통과</span>
      </div>
      <div className="mt-3 flex flex-wrap gap-2">
        {toolResults.map((row) => (
          <span key={`${row.tool}-${row.summary}`} className="inline-flex items-center gap-1 rounded-full border border-slate-200 px-2 py-1 text-[11px] font-black text-slate-600">
            {toolDisplayLabel(row.tool)}
            <StatusBadge status={row.status} />
          </span>
        ))}
      </div>
    </section>
  );
}

function useRecommendationBuildGraph(build: AiRecommendedBuild) {
  return useQuery({
    queryKey: ['build-graph-preview', build.id, buildGraphSignature(build)],
    queryFn: () => resolveBuildGraph({
      source: 'AI_BUILD',
      items: build.items.map((item) => ({
        partId: item.partId,
        category: item.category,
        quantity: item.quantity
      })),
      budgetWon: build.budgetWon
    }),
    staleTime: COMPACT_GRAPH_STALE_TIME_MS
  });
}

function buildGraphSignature(build: AiRecommendedBuild) {
  return build.items
    .map((item) => `${item.category}:${item.partId}:${item.quantity}`)
    .sort()
    .join('|');
}

function createBuildHistoryEntries(builds: AiRecommendedBuild[]): BuildHistoryEntry[] {
  const occurrenceCounts = new Map<string, number>();
  return builds.map((build) => {
    const baseKey = buildHistoryBaseKey(build);
    const occurrence = occurrenceCounts.get(baseKey) ?? 0;
    occurrenceCounts.set(baseKey, occurrence + 1);
    return {
      key: occurrence === 0 ? baseKey : `${baseKey}::${occurrence}`,
      build
    };
  });
}

function buildHistoryBaseKey(build: AiRecommendedBuild) {
  const exactSignature = build.items
    .map((item) => [
      item.category,
      item.partId,
      item.quantity,
      item.price
    ].join(':'))
    .sort()
    .join('|');
  return [
    build.id,
    build.tier,
    build.title,
    build.totalPrice,
    exactSignature
  ].join('::');
}

function countBuildIds(entries: BuildHistoryEntry[]) {
  const counts = new Map<string, number>();
  entries.forEach((entry) => {
    counts.set(entry.build.id, (counts.get(entry.build.id) ?? 0) + 1);
  });
  return counts;
}

function getGraphPreviewAnchor(rect: DOMRect): GraphPreviewAnchor {
  const viewportWidth = window.innerWidth;
  const viewportHeight = window.innerHeight;
  const width = Math.min(GRAPH_PREVIEW_PANEL_WIDTH, viewportWidth - GRAPH_PREVIEW_MARGIN * 2);
  const hasRightSpace = viewportWidth - rect.right >= width + GRAPH_PREVIEW_GAP + GRAPH_PREVIEW_MARGIN;
  const hasLeftSpace = rect.left >= width + GRAPH_PREVIEW_GAP + GRAPH_PREVIEW_MARGIN;
  const placement = hasRightSpace || !hasLeftSpace ? 'right' : 'left';
  const preferredLeft = placement === 'right'
    ? rect.right + GRAPH_PREVIEW_GAP
    : rect.left - width - GRAPH_PREVIEW_GAP;
  const preferredTop = rect.top - 48;
  const maxLeft = viewportWidth - width - GRAPH_PREVIEW_MARGIN;
  const maxTop = viewportHeight - GRAPH_PREVIEW_PANEL_HEIGHT - GRAPH_PREVIEW_MARGIN;

  return {
    left: clamp(preferredLeft, GRAPH_PREVIEW_MARGIN, Math.max(GRAPH_PREVIEW_MARGIN, maxLeft)),
    top: clamp(preferredTop, GRAPH_PREVIEW_MARGIN, Math.max(GRAPH_PREVIEW_MARGIN, maxTop)),
    width,
    placement
  };
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

function toCompactFlowElements(
  graph: BuildGraphResolveResponse,
  includePriceNode: boolean
): { nodes: Node[]; edges: Edge[] } {
  const graphNodes = graph.nodes.filter((node) => node.type === 'PART' || (includePriceNode && node.category === 'PRICE'));
  const nodeIds = new Set(graphNodes.map((node) => node.id));

  return {
    nodes: graphNodes.map((node, index) => ({
      id: node.id,
      type: 'compactGraphNode',
      position: compactNodePosition(node.category, index),
      data: {
        label: node.label,
        category: labelForCategory(String(node.category ?? '')),
        status: node.status
      } satisfies CompactGraphNodeData,
      sourcePosition: Position.Right,
      targetPosition: Position.Left
    })),
    edges: graph.edges
      .filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target))
      .map((edge) => {
        const color = compactEdgeColor(edge.status);
        return {
          id: edge.id,
          source: edge.source,
          target: edge.target,
          type: 'smoothstep',
          label: edge.label,
          style: { stroke: color, strokeWidth: 2.4 },
          markerEnd: { type: MarkerType.ArrowClosed, color },
          labelStyle: { fill: color, fontWeight: 900, fontSize: 11 },
          labelBgPadding: [6, 3],
          labelBgBorderRadius: 6,
          labelBgStyle: { fill: '#ffffff', fillOpacity: 0.96 }
        } satisfies Edge;
      })
  };
}

function compactNodePosition(category: string | undefined, index: number) {
  const key = String(category ?? '');
  const base = compactCategoryPositions[key];
  if (base) return base;
  return {
    x: (index % 3) * 210,
    y: Math.floor(index / 3) * 118
  };
}

function compactNodeClasses(status: BuildGraphStatus) {
  switch (status) {
    case 'FAIL':
      return 'border-red-300 bg-red-50 shadow-red-100';
    case 'WARN':
      return 'border-amber-300 bg-amber-50 shadow-amber-100';
    default:
      return 'border-blue-100 shadow-blue-50';
  }
}

function compactNodeStatusClasses(status: BuildGraphStatus) {
  switch (status) {
    case 'FAIL':
      return 'bg-red-100 text-red-700';
    case 'WARN':
      return 'bg-amber-100 text-amber-700';
    default:
      return 'bg-emerald-50 text-emerald-700';
  }
}

function compactStatusLabel(status: BuildGraphStatus) {
  switch (status) {
    case 'FAIL':
      return '불가';
    case 'WARN':
      return '주의';
    default:
      return '호환 가능';
  }
}

function compactEdgeColor(status: BuildGraphStatus) {
  switch (status) {
    case 'FAIL':
      return '#ef4444';
    case 'WARN':
      return '#f59e0b';
    default:
      return '#2563eb';
  }
}

function toolDisplayLabel(tool: string) {
  switch (tool) {
    case 'compatibility':
      return '호환성 검증';
    case 'power':
      return '전력 검증';
    case 'size':
      return '규격 검증';
    case 'performance':
      return '성능 범위';
    case 'price':
      return '가격 확인';
    default:
      return tool;
  }
}

function labelForCategory(category: string) {
  switch (category) {
    case 'MOTHERBOARD':
      return '메인보드';
    case 'STORAGE':
      return 'SSD';
    case 'PSU':
      return '파워';
    case 'CASE':
      return '케이스';
    case 'COOLER':
      return '쿨러';
    default:
      return category;
  }
}
