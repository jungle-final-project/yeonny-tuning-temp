import { useInfiniteQuery } from '@tanstack/react-query';
import { Search, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { partImageUrl, partShortSpec, specRows } from '../../partDisplay';
import { listParts } from '../../partsApi';
import type { PartRow, PartSearchParams, QuoteDraftItem } from '../../types';
import { openAiAssistant } from '../../../../lib/events';
import { PriceTrendBadge } from './PriceTrendBadge';
import { isMultiItemCategory, type SlotConfig } from './slotBoardConfig';

// CPU·GPU만 벤치마크 점수가 있어 교체 성능 비교가 의미 있다 — 그 외 카테고리는 버튼을 숨긴다.
const PERF_COMPARABLE = new Set(['CPU', 'GPU']);

const CANDIDATE_PAGE_SIZE = 20;

type SlotCandidatePanelProps = {
  slot: SlotConfig;
  draftItems: QuoteDraftItem[];
  onClose: () => void;
  onAddPart: (part: PartRow) => void;
  onReplacePart: (removePartId: string, part: PartRow) => void;
  onRemoveItem: (partId: string) => void;
  onUpdateQuantity: (partId: string, quantity: number) => void;
  isMutating: boolean;
};

export function SlotCandidatePanel({
  slot,
  draftItems,
  onClose,
  onAddPart,
  onReplacePart,
  onRemoveItem,
  onUpdateQuantity,
  isMutating
}: SlotCandidatePanelProps) {
  const [sort, setSort] = useState<PartSearchParams['sort']>('price_asc');
  const [searchInput, setSearchInput] = useState('');
  const [q, setQ] = useState('');
  const [replaceTargetId, setReplaceTargetId] = useState<string | null>(null);

  // 검색어 디바운스(입력마다 요청하지 않는다) — 300ms 후 확정 검색어(q)를 갱신한다.
  useEffect(() => {
    const timer = setTimeout(() => setQ(searchInput.trim()), 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  // 다른 슬롯(카테고리)으로 넘어가면 검색어를 초기화한다.
  useEffect(() => {
    setSearchInput('');
    setQ('');
  }, [slot.category]);
  const isMulti = isMultiItemCategory(slot.category);
  const selectedPartIds = new Set(draftItems.map((item) => item.partId));
  // 교체 성능 비교: CPU·GPU 슬롯에 현재 부품이 있으면, 후보로 바꿨을 때 성능 변화를 챗봇에 물어본다.
  const currentPart = draftItems[0];
  const canComparePerf = PERF_COMPARABLE.has(slot.category) && Boolean(currentPart);

  // 평가 의미론: 교체 대상을 지정하면 그 행만 빼고(REPLACE+target), RAM/SSD처럼 여러 개 담는
  // 카테고리는 담기 기준(ADD — 기존 구성 유지+후보 합산)으로 평가한다. 단일 슬롯은 서버 기본
  // (REPLACE = 교체-전체)이 담기/교체 실행과 의미가 같아 파라미터를 생략한다.
  const compatibilityMode = replaceTargetId ? undefined : isMulti ? 'ADD' as const : undefined;
  const { data, isLoading, isError, isFetchingNextPage, hasNextPage, fetchNextPage } = useInfiniteQuery({
    queryKey: ['parts', 'slot-candidates', slot.category, sort, q, compatibilityMode ?? 'REPLACE', replaceTargetId],
    queryFn: ({ pageParam }) => listParts({
      category: slot.category,
      page: pageParam,
      size: CANDIDATE_PAGE_SIZE,
      sort,
      q: q || undefined,
      compatibilitySource: 'QUOTE_DRAFT_CURRENT',
      compatibilityMode,
      replaceTargetPartId: replaceTargetId ?? undefined
    }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.page + 1) * lastPage.size < lastPage.total ? lastPage.page + 1 : undefined
  });

  const pages = useMemo(() => data?.pages ?? [], [data]);
  // 멘토 피드백: 비호환 후보를 숨기지 않는다 — 전부 보여주되 FAIL은 회색 비활성 + 사유를 표시해
  // 사용자가 "왜 안 되는지"를 알 수 있게 한다.
  const visibleParts = useMemo(() => pages.flatMap((page) => page.items), [pages]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  // 교체 대상은 현재 항목 목록이 바뀌면 초기화한다.
  useEffect(() => {
    if (replaceTargetId && !draftItems.some((item) => item.partId === replaceTargetId)) {
      setReplaceTargetId(null);
    }
  }, [draftItems, replaceTargetId]);

  const sentinelRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel || !hasNextPage) {
      return;
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting) && !isFetchingNextPage) {
        void fetchNextPage();
      }
    });
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  const replaceTarget = draftItems.find((item) => item.partId === replaceTargetId) ?? null;

  return (
    <>
      <div aria-hidden="true" onClick={onClose} className="fixed inset-0 z-30 bg-slate-900/40 lg:hidden" />
      <section
      data-testid="slot-candidate-panel"
      role="dialog"
      aria-label={`${slot.label} 부품 목록`}
      className="panel slot-panel-in fixed inset-x-0 bottom-0 z-40 flex max-h-[72vh] flex-col overflow-hidden rounded-t-xl border-t border-commerce-line shadow-2xl lg:static lg:z-auto lg:h-0 lg:max-h-none lg:min-h-full lg:rounded-lg lg:border lg:shadow-none"
    >
      <div className="flex items-start justify-between gap-3 border-b border-commerce-line px-4 py-3">
        <div className="min-w-0">
          <h2 className="text-base font-black text-commerce-ink">{slot.label} 부품 목록</h2>
          <p className="mt-0.5 text-[11px] font-bold text-slate-500">현재 견적 기준 호환 검사 · 안 맞는 후보도 담아서 사유를 확인할 수 있어요</p>
        </div>
        <div className="flex items-center gap-2">
          <label className="flex items-center rounded-md border border-commerce-line bg-white px-2 py-1">
            <span className="sr-only">후보 정렬 기준</span>
            <select
              aria-label="후보 정렬 기준"
              value={sort}
              onChange={(event) => setSort(event.target.value as PartSearchParams['sort'])}
              className="bg-transparent text-xs font-bold text-slate-700 outline-none"
            >
              <option value="compatibility">호환 가능 우선</option>
              <option value="price_asc">가격 낮은순</option>
              <option value="price_desc">가격 높은순</option>
              <option value="name">이름순</option>
            </select>
          </label>
          <button
            type="button"
            aria-label="후보 패널 닫기"
            onClick={onClose}
            className="grid h-8 w-8 place-items-center rounded-md border border-commerce-line bg-white text-slate-600 hover:border-commerce-ink hover:text-commerce-ink"
          >
            <X size={15} />
          </button>
        </div>
      </div>

      {/* 검색: 이름·제조사로 후보를 좁힌다(디바운스 300ms, 호환 검사·정렬은 그대로 유지). */}
      <div className="shrink-0 border-b border-commerce-line px-4 py-2.5">
        <div className="relative">
          <Search size={14} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            type="text"
            inputMode="search"
            data-testid="candidate-search"
            aria-label={`${slot.label} 부품 검색`}
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            placeholder={`${slot.label} 이름·제조사 검색`}
            className="h-9 w-full rounded-md border border-commerce-line bg-white pl-8 pr-8 text-xs font-bold text-commerce-ink placeholder:font-semibold placeholder:text-slate-400 focus:border-brand-blue focus:outline-none focus:ring-2 focus:ring-blue-100"
          />
          {searchInput ? (
            <button
              type="button"
              aria-label="검색어 지우기"
              onClick={() => setSearchInput('')}
              className="absolute right-1.5 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center rounded text-slate-400 hover:bg-slate-100 hover:text-slate-600"
            >
              <X size={13} />
            </button>
          ) : null}
        </div>
      </div>

      {draftItems.length > 0 ? (
        <div className="shrink-0 px-4 pt-4">
          <div className="space-y-2 rounded-md border border-blue-100 bg-blue-50/50 p-3">
            <div className="text-[11px] font-black text-slate-600">현재 장착 {slot.label} {draftItems.length}개</div>
            {draftItems.map((item) => (
              <div key={item.partId} className="rounded-md border border-commerce-line bg-white px-2.5 py-2 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <div className="min-w-0">
                    <div className="truncate font-black text-commerce-ink">{item.name}</div>
                    <div className="text-[11px] text-slate-500">{partShortSpec(item)}</div>
                    <div className="text-[11px] text-slate-500">수량 {item.quantity} · {item.lineTotal.toLocaleString()}원</div>
                    <PriceTrendBadge partId={item.partId} />
                  </div>
                  <div className="flex shrink-0 items-center gap-1.5">
                    {isMulti ? (
                      <>
                        <QuantityStepper item={item} disabled={isMutating} onChange={onUpdateQuantity} />
                        <button
                          type="button"
                          aria-label={`${item.name} 교체 대상 선택`}
                          aria-pressed={replaceTargetId === item.partId}
                          onClick={() => setReplaceTargetId((current) => current === item.partId ? null : item.partId)}
                          className={`rounded-md border px-2 py-1 font-black ${
                            replaceTargetId === item.partId
                              ? 'border-brand-blue bg-blue-50 text-brand-blue'
                              : 'border-commerce-line bg-white text-slate-600 hover:border-commerce-ink'
                          }`}
                        >
                          교체
                        </button>
                      </>
                    ) : null}
                    <button
                      type="button"
                      aria-label={`${item.name} 견적에서 제거`}
                      disabled={isMutating}
                      onClick={() => onRemoveItem(item.partId)}
                      className="rounded-md border border-commerce-line bg-white px-2 py-1 font-black text-slate-600 hover:border-commerce-sale hover:text-commerce-sale disabled:cursor-wait"
                    >
                      제거
                    </button>
                  </div>
                </div>
                {specRows(item).length > 0 ? (
                  <div className="mt-1.5 flex flex-wrap gap-1">
                    {specRows(item).slice(0, 4).map((row) => (
                      <span key={row.label} className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-bold text-slate-600">
                        {row.label} {row.value}
                      </span>
                    ))}
                  </div>
                ) : null}
              </div>
            ))}
            {replaceTarget ? (
              <div className="text-[11px] font-bold text-brand-blue">아래 후보를 선택하면 {replaceTarget.name}와(과) 교체합니다.</div>
            ) : null}
          </div>
        </div>
      ) : null}

      {/* 후보 목록만 스크롤 영역: 데스크톱은 카드 약 6개 분량 높이로 제한한다. 데이터는 20개 페이지 로드를 유지. */}
      {/* lg: 패널이 h-0+min-h-full로 보드(구성 관계도) 높이를 따라가므로 목록도 그 안에서 스크롤된다. */}
      <div data-testid="slot-candidate-list" className="min-h-0 flex-1 overflow-y-auto p-4">
        {isLoading ? (
          <div className="rounded-md border border-commerce-line p-4 text-sm text-slate-500">후보 목록을 불러오는 중입니다.</div>
        ) : null}
        {isError && pages.length === 0 ? (
          <div className="rounded-md border border-orange-200 bg-orange-50 p-4 text-sm text-orange-700">후보 목록 API를 불러오지 못했습니다.</div>
        ) : null}

        <div className="space-y-2">
          {visibleParts.map((part) => {
            const isSelected = selectedPartIds.has(part.id);
            const isFail = part.compatibility?.status === 'FAIL';
            const failReason = isFail
              ? part.compatibility?.summary || part.compatibility?.statusLabel || '현재 견적과 호환되지 않습니다'
              : null;
            const actionLabel = replaceTarget
              ? `${part.name}(으)로 교체`
              : isMulti || draftItems.length === 0
                ? `${part.name} 담기`
                : `${part.name} 교체`;
            const actionText = replaceTarget ? '이걸로 교체' : isMulti || draftItems.length === 0 ? '담기' : '교체';
            return (
              <article
                key={part.id}
                data-compat={part.compatibility?.status ?? 'NONE'}
                className={`flex items-center gap-3 rounded-md border p-2.5 ${
                  isFail ? 'border-red-200 bg-red-50/40' : 'border-commerce-line bg-white'
                }`}
              >
                <img
                  src={partImageUrl(part)}
                  alt={`${part.name} 제품 사진`}
                  className="h-12 w-12 shrink-0 rounded-md border border-commerce-line bg-slate-100 object-cover"
                />
                <div className="min-w-0 flex-1 text-xs">
                  <div className="line-clamp-2 font-black leading-4 text-commerce-ink">{part.name}</div>
                  <div className="mt-0.5 text-[11px] text-slate-500">
                    {part.manufacturer ?? '-'}
                    {part.externalOffer?.supplierName ? ` · ${part.externalOffer.supplierName}` : ''}
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-1.5">
                    <span className="font-black text-commerce-ink">{part.price.toLocaleString()}원</span>
                    {part.compatibility?.status === 'WARN' ? (
                      <span className="rounded border border-amber-100 bg-amber-50 px-1.5 py-0.5 text-[10px] font-black text-amber-700">간섭 주의</span>
                    ) : null}
                    {isFail ? (
                      <span className="rounded border border-red-200 bg-red-50 px-1.5 py-0.5 text-[10px] font-black text-red-700">장착 불가</span>
                    ) : null}
                  </div>
                  {part.compatibility?.status === 'WARN' && part.compatibility.summary ? (
                    <div className="mt-0.5 line-clamp-1 text-[10px] text-slate-500">{part.compatibility.summary}</div>
                  ) : null}
                  {failReason ? (
                    <div className="mt-0.5 line-clamp-2 text-[10px] font-bold text-red-600">{failReason}</div>
                  ) : null}
                </div>
                <div className="flex shrink-0 flex-col items-stretch gap-1.5">
                  {/* 비호환(FAIL)도 담을 수 있다 — 왜 안 되는지 보드에서 빨강으로 보고 교체하는 UX(구매는 여전히 차단). */}
                  <button
                    type="button"
                    aria-label={isFail ? `${actionLabel} (장착 불가 — 담아서 확인)` : actionLabel}
                    disabled={isMutating || isSelected}
                    onClick={() => replaceTarget ? onReplacePart(replaceTarget.partId, part) : onAddPart(part)}
                    className={`rounded-md px-2.5 py-2 text-xs font-black transition disabled:cursor-not-allowed ${
                      isSelected
                        ? 'border border-commerce-line bg-slate-50 text-slate-400 disabled:opacity-60'
                        : isFail
                          ? 'border border-red-300 bg-white text-red-600 hover:bg-red-50 disabled:opacity-60'
                          : 'bg-commerce-ink text-white hover:bg-slate-700 disabled:opacity-60'
                    }`}
                  >
                    {isSelected ? '장착됨' : actionText}
                  </button>
                  {/* 교체 성능 비교: 현재 부품 → 후보 시뮬레이션을 챗봇에 프리필(읽기 전용, 드래프트 무변경). */}
                  {canComparePerf && !isSelected ? (
                    <button
                      type="button"
                      data-testid="candidate-perf-compare"
                      aria-label={`현재 ${currentPart.name}을(를) ${part.name}(으)로 바꾸면 성능 비교`}
                      onClick={() => openAiAssistant({
                        prefill: `현재 ${currentPart.name}을(를) ${part.name}(으)로 바꾸면 성능이 어떻게 달라져?`,
                        autoSubmit: true
                      })}
                      className="rounded-md border border-brand-blue/30 bg-blue-50 px-2.5 py-1 text-[10px] font-black text-brand-blue transition hover:bg-blue-100"
                    >
                      성능 비교
                    </button>
                  ) : null}
                </div>
              </article>
            );
          })}
        </div>

        {!isLoading && visibleParts.length === 0 && !hasNextPage ? (
          <div className="mt-2 rounded-md border border-dashed border-slate-300 p-4 text-center text-xs font-bold text-slate-500">
            {q ? `'${q}' 검색 결과가 없습니다.` : '표시할 후보가 없습니다.'}
          </div>
        ) : null}

        <div ref={sentinelRef} aria-hidden="true" className="h-1" />
        {hasNextPage ? (
          <button
            type="button"
            disabled={isFetchingNextPage}
            onClick={() => fetchNextPage()}
            className="mt-3 w-full rounded-md border border-commerce-line bg-white px-3 py-2 text-xs font-black text-slate-700 hover:border-commerce-ink disabled:cursor-wait disabled:text-slate-400"
          >
            {isFetchingNextPage ? '후보 불러오는 중' : '후보 더 보기'}
          </button>
        ) : null}
      </div>
      </section>
    </>
  );
}

function QuantityStepper({ item, disabled, onChange }: { item: QuoteDraftItem; disabled: boolean; onChange: (partId: string, quantity: number) => void }) {
  return (
    <div className="flex h-7 overflow-hidden rounded border border-slate-300" aria-label={`${item.name} 수량 선택`}>
      <button
        type="button"
        aria-label={`${item.name} 수량 감소`}
        disabled={disabled || item.quantity <= 1}
        onClick={() => onChange(item.partId, item.quantity - 1)}
        className="w-7 bg-slate-50 text-sm font-bold text-slate-600 disabled:text-slate-300"
      >
        -
      </button>
      <div className="flex w-8 items-center justify-center border-x border-slate-300 text-[11px] font-bold text-slate-900">{item.quantity}</div>
      <button
        type="button"
        aria-label={`${item.name} 수량 증가`}
        disabled={disabled || item.quantity >= 9}
        onClick={() => onChange(item.partId, item.quantity + 1)}
        className="w-7 bg-slate-50 text-sm font-bold text-slate-600 disabled:text-slate-300"
      >
        +
      </button>
    </div>
  );
}
