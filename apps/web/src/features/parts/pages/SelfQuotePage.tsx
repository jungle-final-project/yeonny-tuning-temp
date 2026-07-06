import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bell, LayoutGrid, X } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { Screen } from '../../../components/ui';
import { AUTH_CHANGED_EVENT, getToken } from '../../../lib/api';
import { openAiAssistant } from '../../../lib/events';
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
  type AiRecommendedBuild,
  type BuildGraphFocus,
  type BuildGraphResolveResponse,
  type PartCategory,
  type AiSelectedBuild
} from '../../quote/aiSelection';
import { resolveBuildGraph, saveBuildFromChat } from '../../quote/quoteApi';
import { QuoteComparePanel } from '../components/slot-board/QuoteComparePanel';
import { QuotePerformancePanel } from '../components/slot-board/QuotePerformancePanel';
import { UpgradeAdvisorPanel } from '../components/slot-board/UpgradeAdvisorPanel';
import { SlotBoard } from '../components/slot-board/SlotBoard';
import { SlotCandidatePanel } from '../components/slot-board/SlotCandidatePanel';
import { SlotStatusBar } from '../components/slot-board/SlotStatusBar';
import { SLOT_CONFIGS, SLOT_COUNT, isSlotCategory, slotConfigFor } from '../components/slot-board/slotBoardConfig';
import { applyAiBuildToQuoteDraft, deleteQuoteDraftItem, getCurrentQuoteDraft, patchQuoteDraftItem, putQuoteDraftItem } from '../partsApi';
import type { PartRow, QuoteDraft, QuoteDraftItem } from '../types';

export function SelfQuotePage() {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const categoryParam = searchParams.get('category');
  const selectedCategory: PartCategory | null = isSlotCategory(categoryParam) ? categoryParam : null;
  const selectedSlot = selectedCategory ? slotConfigFor(selectedCategory) ?? null : null;
  const [aiBuild, setAiBuild] = useState<AiSelectedBuild | null>(() => readSelectedAiBuild());
  // R1 견적 비교: 챗봇이 마지막으로 내려준 추천 배치(최대 3안 — 가성비/균형/고성능).
  const [recentBuilds, setRecentBuilds] = useState<AiRecommendedBuild[]>(() => recentBuildsForChatContext(readAssistantSession()));
  const [compareOpen, setCompareOpen] = useState(false);
  const [applyingBuildId, setApplyingBuildId] = useState<string | null>(null);
  const [compareApplyError, setCompareApplyError] = useState<string | null>(null);
  const [upgradeOpen, setUpgradeOpen] = useState(false);
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
  const deleteMutation = useMutation({
    mutationFn: (partId: string) => deleteQuoteDraftItem(partId),
    onSuccess: invalidateQuoteDraft
  });
  const quantityMutation = useMutation({
    mutationFn: ({ partId, quantity }: { partId: string; quantity: number }) => patchQuoteDraftItem(partId, quantity),
    onSuccess: invalidateQuoteDraft
  });
  const replaceMutation = useMutation({
    mutationFn: async ({ removePartId, partId }: { removePartId: string; partId: string }) => {
      await putQuoteDraftItem(partId, 1);
      return deleteQuoteDraftItem(removePartId);
    },
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
    enabled: hasToken && !isQuoteDraftLoading
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

  const selectSlot = (category: PartCategory) => {
    if (!hasToken) {
      navigate(loginHref);
      return;
    }
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      nextParams.set('category', category);
      return nextParams;
    });
  };

  const closePanel = useCallback(() => {
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      nextParams.delete('category');
      return nextParams;
    });
  }, [setSearchParams]);

  const addPart = (part: PartRow) => {
    if (!hasToken) {
      navigate(loginHref);
      return;
    }
    addMutation.mutate({ partId: part.id, quantity: 1 });
  };

  const replacePart = (removePartId: string, part: PartRow) => {
    if (!hasToken) {
      navigate(loginHref);
      return;
    }
    replaceMutation.mutate({ removePartId, partId: part.id });
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
    quantityMutation.mutate({ partId, quantity });
  };

  const isMutating = addMutation.isPending || deleteMutation.isPending || replaceMutation.isPending || quantityMutation.isPending;
  const hasCompatibilityFail = quoteHasCompatibilityFail(graphQuery.data, draftItems);

  const filledCount = SLOT_CONFIGS.filter((slot) => draftItems.some((item) => item.category === slot.category)).length;
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
    <Screen>
      <div className="space-y-4">
        {/* 페이지 헤더 */}
        <div className="flex flex-wrap items-center gap-2">
          <LayoutGrid size={15} className="text-brand-blue" />
          <h1 className="text-base font-black tracking-tight text-commerce-ink">셀프 견적 · 구성 관계도</h1>
          <span className="text-xs text-slate-400">슬롯을 눌러 후보를 확인하고 교체하세요</span>
        </div>

        {/* 상단 요약 지표 바 */}
        <QuoteSummaryBar
          totalPrice={selectedTotal}
          filledCount={filledCount}
          slotCount={SLOT_COUNT}
          warnCount={warnCount}
          failCount={failCount}
          unmetConditionCount={unmetConditionCount}
          storageItems={draftItems.filter((item) => item.category === 'STORAGE')}
          graphLoading={graphQuery.isLoading}
        />

        {/* 시작 안내: 빈 견적이면 AI/직접 시작을 명시하고, 진행 중이면 다음 선택을 안내한다 */}
        {draftItems.length === 0 ? (
          <section
            data-testid="quote-start-banner"
            className="panel flex flex-col gap-3 border-blue-100 bg-blue-50/60 p-4 md:flex-row md:items-center md:justify-between"
          >
            <div className="min-w-0">
              <div className="text-sm font-black text-commerce-ink">뭘 골라야 할지 모르겠다면, AI에게 예산과 용도만 알려주세요</div>
              <div className="mt-1 text-xs text-slate-500">
                예: &quot;게이밍 200만원&quot; — AI가 완성된 조합을 추천하고, 선택하면 이 화면에 그대로 채워집니다
              </div>
            </div>
            <div className="flex shrink-0 flex-wrap gap-2">
              <button
                type="button"
                data-testid="quote-ai-start"
                onClick={() => openAiAssistant()}
                className="rounded-md bg-brand-blue px-4 py-2.5 text-xs font-black text-white transition hover:bg-blue-700"
              >
                AI로 시작하기
              </button>
              <button
                type="button"
                data-testid="quote-manual-start"
                onClick={() => selectSlot('CPU')}
                className="rounded-md border border-commerce-line bg-white px-4 py-2.5 text-xs font-black text-slate-700 transition hover:border-commerce-ink"
              >
                직접 고르기 (CPU부터)
              </button>
            </div>
          </section>
        ) : nextCategory ? (
          <div
            data-testid="quote-next-guide"
            className="flex flex-wrap items-center gap-2 rounded-md border border-blue-100 bg-blue-50/50 px-3 py-2 text-xs font-black text-brand-blue"
          >
            <span>
              다음: {RECOMMENDED_SLOT_ORDER.indexOf(nextCategory) + 1}. {PART_CATEGORY_LABELS[nextCategory]}를 선택해 주세요
            </span>
            <button
              type="button"
              onClick={() => selectSlot(nextCategory)}
              className="rounded border border-brand-blue/30 bg-white px-2 py-1 text-[11px] font-black text-brand-blue hover:bg-blue-50"
            >
              바로 열기
            </button>
          </div>
        ) : (
          <div
            data-testid="quote-complete-guide"
            className="rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs font-black text-emerald-700"
          >
            8개 품목이 모두 채워졌어요 — 호환 상태를 확인하고 저장하거나 구매하세요
          </div>
        )}

        {aiBuild ? (
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
        {recentBuilds.length >= 2 && !compareOpen ? (
          <div className="panel flex flex-wrap items-center justify-between gap-2 border-blue-100 px-4 py-2.5">
            <span className="text-xs font-bold text-slate-600">
              AI가 추천한 {recentBuilds.length}안(가성비·균형·고성능)을 나란히 비교할 수 있어요
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
        {compareOpen && recentBuilds.length >= 2 ? (
          <QuoteComparePanel
            builds={recentBuilds}
            draftItems={draftItems}
            onApply={(build) => void applyCompareBuild(build)}
            applyingBuildId={applyingBuildId}
            applyError={compareApplyError}
            onClose={() => setCompareOpen(false)}
          />
        ) : null}

        {/* R1 업그레이드 진단: 부품이 담긴 견적(=기존 PC 구성)에서 증상 기반 교체 제안으로 진입한다. */}
        {draftItems.length > 0 && !upgradeOpen ? (
          <div className="panel flex flex-wrap items-center justify-between gap-2 border-commerce-line px-4 py-2.5">
            <span className="text-xs font-bold text-slate-600">PC가 예전 같지 않다면 — 증상만 고르면 병목 부품 교체를 제안해 드려요</span>
            <button
              type="button"
              data-testid="upgrade-advisor-open"
              onClick={() => setUpgradeOpen(true)}
              className="rounded-md border border-commerce-line bg-white px-3 py-1.5 text-xs font-black text-slate-700 transition hover:border-commerce-ink"
            >
              업그레이드 진단
            </button>
          </div>
        ) : null}
        {upgradeOpen && draftItems.length > 0 ? (
          <UpgradeAdvisorPanel
            draftItems={draftItems}
            onOpenSlot={selectSlot}
            onClose={() => setUpgradeOpen(false)}
          />
        ) : null}

        {/* 본문: 체크리스트(품목 지도) + 보드(보조 그래프) + 우측 상세 패널.
            세 열의 세로 길이는 보드에 맞춘다 — 체크리스트는 스트레치, 우측 패널은 내부 스크롤. */}
        <div className="grid gap-4 lg:grid-cols-[230px_minmax(0,1fr)_380px] lg:items-start">
          <QuoteChecklist
            draftItems={draftItems}
            selectedCategory={selectedCategory}
            nextCategory={nextCategory}
            onSelect={selectSlot}
          />
          <SlotBoard
            items={draftItems}
            selectedCategory={selectedCategory}
            nextCategory={nextCategory}
            onSlotSelect={selectSlot}
            onRemoveItem={removeItem}
            isRemovePending={deleteMutation.isPending}
            graph={graphQuery.data}
          />
          {selectedSlot ? (
            <SlotCandidatePanel
              key={selectedSlot.category}
              slot={selectedSlot}
              draftItems={draftItems.filter((item) => item.category === selectedSlot.category)}
              onClose={closePanel}
              onAddPart={addPart}
              onReplacePart={replacePart}
              onRemoveItem={removeItem}
              onUpdateQuantity={updateQuantity}
              isMutating={isMutating}
            />
          ) : (
            <SlotDetailPlaceholder onPick={selectSlot} />
          )}
        </div>

        {/* 담긴 견적으로 성능 비교(R1): resolveBuildGraph가 이미 내려주는 performance 툴 결과를 표시한다. */}
        <QuotePerformancePanel graph={graphQuery.data} items={draftItems} />

        {/* 멘토 피드백: 지금까지 고른 부품 한눈에 — 그리드 아래 비어 있던 띠를 전폭 견적 테이블로 채운다. */}
        <QuoteItemsTable draftItems={draftItems} />

        {/* 하단 상태바 */}
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
        />
      </div>
    </Screen>
  );
}

// 멘토 피드백: "무엇을 사야 하는지, 어디까지 했는지"의 품목 지도. 권장 선택 순서이며 강제가 아니다.
const RECOMMENDED_SLOT_ORDER: PartCategory[] = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER'];

function QuoteChecklist({
  draftItems,
  selectedCategory,
  nextCategory,
  onSelect
}: {
  draftItems: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  nextCategory: PartCategory | null;
  onSelect: (category: PartCategory) => void;
}) {
  const filledCount = RECOMMENDED_SLOT_ORDER.filter((category) => draftItems.some((item) => item.category === category)).length;

  return (
    // lg: 보드(구성 관계도) 높이에 맞춰 늘어난다 — 좌·중 열의 아래 끝이 나란해진다.
    <aside data-testid="quote-checklist" className="panel h-fit p-4 lg:h-auto lg:self-stretch">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-black text-commerce-ink">견적 체크리스트</h2>
        <span data-testid="quote-checklist-progress" className="text-[11px] font-black text-slate-500">
          {filledCount}/{RECOMMENDED_SLOT_ORDER.length} 완료
        </span>
      </div>
      <ol className="space-y-1.5">
        {RECOMMENDED_SLOT_ORDER.map((category, index) => {
          const items = draftItems.filter((item) => item.category === category);
          const filled = items.length > 0;
          const isNext = category === nextCategory;
          const isSelected = category === selectedCategory;
          const lineTotal = items.reduce((sum, item) => sum + item.lineTotal, 0);
          const label = PART_CATEGORY_LABELS[category] ?? category;
          return (
            <li key={category}>
              <button
                type="button"
                aria-label={`${label} 후보 목록 열기`}
                data-testid={`checklist-${category}`}
                data-filled={filled ? 'true' : 'false'}
                data-next={isNext ? 'true' : 'false'}
                onClick={() => onSelect(category)}
                className={`w-full rounded-md border px-2.5 py-2 text-left text-xs transition ${
                  isSelected
                    ? 'border-brand-blue bg-white ring-2 ring-blue-100'
                    : filled
                      ? 'border-emerald-200 bg-emerald-50/40 hover:border-emerald-400'
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
                    <span className="shrink-0 text-[10px] font-black text-emerald-700">✓ 완료</span>
                  ) : isNext ? (
                    <span className="shrink-0 text-[10px] font-black text-brand-blue">다음 선택</span>
                  ) : (
                    <span className="shrink-0 text-[10px] font-bold text-slate-400">비어 있음</span>
                  )}
                </div>
                {filled ? (
                  <div className="mt-1 flex items-center justify-between gap-2">
                    <span className="min-w-0 truncate text-[11px] text-slate-500">
                      {items[0].name}
                      {items.length > 1 ? ` 외 ${items.length - 1}개` : ''}
                    </span>
                    <span className="shrink-0 text-[11px] font-black text-commerce-ink">{lineTotal.toLocaleString()}원</span>
                  </div>
                ) : (
                  <div className="mt-1 text-[11px] text-slate-400">+ 부품 선택</div>
                )}
              </button>
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
            aria-label="AI 선택 조합 비우기"
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

export function selfQuoteBuildId(draft: QuoteDraft) {
  return `self-quote-${draft.id ?? 'current'}`;
}

export function quoteDraftToRecommendedBuild(draft: QuoteDraft): AiRecommendedBuild {
  const items = draft.items
    .filter((item): item is QuoteDraftItem & { category: PartCategory } => isPartCategory(item.category))
    .map((item) => ({
      partId: item.partId,
      category: item.category,
      name: item.name,
      manufacturer: item.manufacturer ?? 'BuildGraph',
      quantity: item.quantity,
      price: item.currentPrice,
      note: '셀프 견적 장바구니에서 저장'
    }));
  const categories = Array.from(new Set(items.map((item) => item.category)));

  return {
    id: selfQuoteBuildId(draft),
    tier: 'balanced',
    label: '셀프',
    title: '셀프 견적 저장 조합',
    summary: '셀프 견적 페이지에서 선택한 부품을 내 견적함에 저장했습니다.',
    totalPrice: draft.totalPrice,
    badges: ['셀프 견적', `${items.length}개 부품`],
    budgetWon: draft.totalPrice,
    budgetLabel: '셀프 견적',
    tierLabel: '셀프 견적',
    appliedPartCategories: categories,
    items,
    toolResults: [],
    warnings: [],
    confidence: 'HIGH'
  };
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

function isPartCategory(category: string): category is PartCategory {
  return Object.keys(PART_CATEGORY_LABELS).includes(category);
}

function graphToolForCategory(category: PartCategory): BuildGraphFocus['tool'] {
  if (category === 'GPU' || category === 'PSU') return 'power';
  if (category === 'CASE' || category === 'COOLER') return 'size';
  if (category === 'CPU' || category === 'MOTHERBOARD' || category === 'RAM') return 'compatibility';
  return undefined;
}

// 슬롯 미선택 시 우측 컬럼을 채우는 상세 placeholder. 슬롯을 고르면 실제 후보 패널로 전환된다.
function SlotDetailPlaceholder({ onPick }: { onPick: (category: PartCategory) => void }) {
  return (
    <section
      data-testid="slot-detail-placeholder"
      className="hidden rounded-lg border border-commerce-line bg-white lg:block"
    >
      <div className="border-b border-commerce-line px-4 py-3">
        <h2 className="text-base font-black text-commerce-ink">구성 상세 · 교체 후보</h2>
        <p className="mt-0.5 text-[11px] font-bold text-slate-500">슬롯을 선택하면 현재 견적 기준 교체 후보를 보여줍니다.</p>
      </div>
      <div className="space-y-3 p-4">
        <div className="rounded-md border border-dashed border-slate-300 bg-slate-50/60 p-4 text-center">
          <div className="mx-auto mb-2 flex h-10 w-10 items-center justify-center rounded-full bg-blue-50 text-brand-blue">
            <LayoutGrid size={18} />
          </div>
          <p className="text-xs font-black text-slate-600">왼쪽 보드에서 슬롯을 눌러보세요</p>
          <p className="mt-1 text-[11px] font-bold text-slate-400">부품 관계를 확인하고 교체 후보를 비교할 수 있습니다.</p>
        </div>
        <div className="flex flex-wrap gap-1.5">
          {SLOT_CONFIGS.map((slot) => (
            <button
              key={slot.category}
              type="button"
              onClick={() => onPick(slot.category)}
              className="inline-flex items-center gap-1 rounded-full border border-commerce-line bg-white px-2.5 py-1 text-[11px] font-black text-slate-600 hover:border-brand-blue hover:text-brand-blue"
            >
              <img src={slot.glyph} alt="" aria-hidden="true" className="h-3.5 w-auto max-w-10" />
              {slot.label}
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}

function QuoteSummaryBar({
  totalPrice,
  filledCount,
  slotCount,
  warnCount,
  failCount,
  unmetConditionCount,
  storageItems,
  graphLoading
}: {
  totalPrice: number;
  filledCount: number;
  slotCount: number;
  warnCount: number;
  failCount: number;
  unmetConditionCount: number;
  storageItems: QuoteDraftItem[];
  graphLoading: boolean;
}) {
  // '조건 미충족' = 빨간 부품 노드는 없지만 검사(예: 파워 용량)가 FAIL — 구매 차단과 표기를 일치시킨다.
  const hasRedState = failCount > 0 || unmetConditionCount > 0;
  const compatibilityText = graphLoading
    ? '확인 중'
    : failCount > 0
      ? `안 맞음 ${failCount}개`
      : unmetConditionCount > 0
        ? '조건 미충족'
        : warnCount > 0
          ? `주의 ${warnCount}개`
          : filledCount === 0
            ? '부품 없음'
            : '이상 없음';
  const compatibilityColor = hasRedState
    ? 'text-red-600'
    : warnCount > 0
      ? 'text-amber-600'
      : filledCount === 0
        ? 'text-slate-400'
        : 'text-emerald-600';
  const storageCount = storageItems.reduce((sum, item) => sum + item.quantity, 0);

  return (
    <div data-testid="quote-summary-bar" className="grid grid-cols-2 gap-2 sm:grid-cols-4">
      <div className="panel flex items-center gap-3 px-4 py-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-blue-50 text-xl font-black text-brand-blue">₩</span>
        <div className="min-w-0">
          <div className="text-[11px] font-bold text-slate-500">총액</div>
          <div className="truncate text-sm font-black text-commerce-ink">{totalPrice > 0 ? `${totalPrice.toLocaleString()}원` : '—'}</div>
        </div>
      </div>
      <div className="panel flex items-center gap-3 px-4 py-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-600">
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
      <div className="panel flex items-center gap-3 px-4 py-3">
        <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${hasRedState ? 'bg-red-50' : warnCount > 0 ? 'bg-amber-50' : 'bg-emerald-50'}`}>
          <svg viewBox="0 0 20 20" className={`h-5 w-5 ${hasRedState ? 'text-red-500' : warnCount > 0 ? 'text-amber-500' : 'text-emerald-500'}`} fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M10 2a8 8 0 100 16A8 8 0 0010 2zm0 5v4m0 2.5v.5" strokeLinecap="round" />
          </svg>
        </span>
        <div className="min-w-0">
          <div className="text-[11px] font-bold text-slate-500">호환 상태</div>
          <div className={`text-sm font-black ${compatibilityColor}`}>{compatibilityText}</div>
        </div>
      </div>
      <div className="panel flex items-center gap-3 px-4 py-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-500">
          <svg viewBox="0 0 20 20" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="3" y="6" width="14" height="8" rx="1" /><path d="M7 10h6M10 8v4" strokeLinecap="round" />
          </svg>
        </span>
        <div className="min-w-0">
          <div className="text-[11px] font-bold text-slate-500">스토리지</div>
          <div className="text-sm font-black text-commerce-ink">{storageCount > 0 ? `SSD ${storageCount}개` : 'SSD 없음'}</div>
        </div>
      </div>
    </div>
  );
}
