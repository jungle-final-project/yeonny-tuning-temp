import { useInfiniteQuery } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { partImageUrl, partShortSpec, specRows } from '../../partDisplay';
import { listParts } from '../../partsApi';
import type { PartRow, PartSearchParams, QuoteDraftItem } from '../../types';
import { PriceTrendBadge } from './PriceTrendBadge';
import { isMultiItemCategory, type SlotConfig } from './slotBoardConfig';

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
  const [replaceTargetId, setReplaceTargetId] = useState<string | null>(null);
  const isMulti = isMultiItemCategory(slot.category);
  const selectedPartIds = new Set(draftItems.map((item) => item.partId));

  const { data, isLoading, isError, isFetchingNextPage, hasNextPage, fetchNextPage } = useInfiniteQuery({
    queryKey: ['parts', 'slot-candidates', slot.category, sort],
    queryFn: ({ pageParam }) => listParts({
      category: slot.category,
      page: pageParam,
      size: CANDIDATE_PAGE_SIZE,
      sort,
      compatibilitySource: 'QUOTE_DRAFT_CURRENT'
    }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.page + 1) * lastPage.size < lastPage.total ? lastPage.page + 1 : undefined
  });

  const pages = useMemo(() => data?.pages ?? [], [data]);
  const visibleParts = useMemo(
    () => pages.flatMap((page) => page.items).filter((part) => part.compatibility?.status !== 'FAIL'),
    [pages]
  );

  // 마지막으로 받은 페이지가 전부 FAIL로 숨겨졌다면 다음 페이지를 이어서 불러온다.
  useEffect(() => {
    if (pages.length === 0 || isFetchingNextPage || !hasNextPage) {
      return;
    }
    const lastPage = pages[pages.length - 1];
    const lastVisible = lastPage.items.filter((part) => part.compatibility?.status !== 'FAIL');
    if (lastVisible.length === 0) {
      void fetchNextPage();
    }
  }, [pages, isFetchingNextPage, hasNextPage, fetchNextPage]);

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
      className="panel slot-panel-in fixed inset-x-0 bottom-0 z-40 flex max-h-[72vh] flex-col overflow-hidden rounded-t-xl border-t border-commerce-line shadow-2xl lg:static lg:z-auto lg:max-h-none lg:rounded-lg lg:border lg:shadow-none"
    >
      <div className="flex items-start justify-between gap-3 border-b border-commerce-line px-4 py-3">
        <div className="min-w-0">
          <h2 className="text-base font-black text-commerce-ink">{slot.label} 부품 목록</h2>
          <p className="mt-0.5 text-[11px] font-bold text-slate-500">안 맞는 후보는 숨김 · 현재 견적 기준 호환 후보</p>
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
      <div data-testid="slot-candidate-list" className="min-h-0 flex-1 overflow-y-auto p-4 lg:max-h-[608px]">
        {isLoading ? (
          <div className="rounded-md border border-commerce-line p-4 text-sm text-slate-500">후보 목록을 불러오는 중입니다.</div>
        ) : null}
        {isError && pages.length === 0 ? (
          <div className="rounded-md border border-orange-200 bg-orange-50 p-4 text-sm text-orange-700">후보 목록 API를 불러오지 못했습니다.</div>
        ) : null}

        <div className="space-y-2">
          {visibleParts.map((part) => {
            const isSelected = selectedPartIds.has(part.id);
            const actionLabel = replaceTarget
              ? `${part.name}(으)로 교체`
              : isMulti || draftItems.length === 0
                ? `${part.name} 담기`
                : `${part.name} 교체`;
            const actionText = replaceTarget ? '이걸로 교체' : isMulti || draftItems.length === 0 ? '담기' : '교체';
            return (
              <article key={part.id} className="flex items-center gap-3 rounded-md border border-commerce-line bg-white p-2.5">
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
                  </div>
                  {part.compatibility?.status === 'WARN' && part.compatibility.summary ? (
                    <div className="mt-0.5 line-clamp-1 text-[10px] text-slate-500">{part.compatibility.summary}</div>
                  ) : null}
                </div>
                <button
                  type="button"
                  aria-label={actionLabel}
                  disabled={isMutating || isSelected}
                  onClick={() => replaceTarget ? onReplacePart(replaceTarget.partId, part) : onAddPart(part)}
                  className={`shrink-0 rounded-md px-2.5 py-2 text-xs font-black transition disabled:cursor-not-allowed disabled:opacity-60 ${
                    isSelected ? 'border border-commerce-line bg-slate-50 text-slate-400' : 'bg-commerce-ink text-white hover:bg-slate-700'
                  }`}
                >
                  {isSelected ? '장착됨' : actionText}
                </button>
              </article>
            );
          })}
        </div>

        {!isLoading && visibleParts.length === 0 && !hasNextPage ? (
          <div className="mt-2 rounded-md border border-dashed border-slate-300 p-4 text-center text-xs font-bold text-slate-500">
            표시할 후보가 없습니다. 안 맞는 후보는 숨김 처리됩니다.
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
