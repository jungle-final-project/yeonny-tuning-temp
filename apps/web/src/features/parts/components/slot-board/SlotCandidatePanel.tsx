import { useInfiniteQuery } from '@tanstack/react-query';
import { Eye, Heart, Search, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
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
  const [manufacturer, setManufacturer] = useState('');
  const [manufacturerOptions, setManufacturerOptions] = useState<string[]>([]);
  const [minPriceInput, setMinPriceInput] = useState('');
  const [maxPriceInput, setMaxPriceInput] = useState('');
  const [minPrice, setMinPrice] = useState<number | undefined>(undefined);
  const [maxPrice, setMaxPrice] = useState<number | undefined>(undefined);
  const [hideFail, setHideFail] = useState(false);
  const [onlyWishlist, setOnlyWishlist] = useState(false);
  const [wishlist, setWishlist] = useState<Set<string>>(() => readWishlist());
  const [quickViewPart, setQuickViewPart] = useState<PartRow | null>(null);
  const [replaceTargetId, setReplaceTargetId] = useState<string | null>(null);

  function toggleWishlist(partId: string) {
    setWishlist((prev) => {
      const next = new Set(prev);
      if (next.has(partId)) {
        next.delete(partId);
      } else {
        next.add(partId);
      }
      writeWishlist(next);
      return next;
    });
  }

  // 검색어 디바운스(입력마다 요청하지 않는다) — 300ms 후 확정 검색어(q)를 갱신한다.
  useEffect(() => {
    const timer = setTimeout(() => setQ(searchInput.trim()), 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  // 가격 범위 디바운스 — 숫자만 추출해 300ms 후 확정한다.
  useEffect(() => {
    const timer = setTimeout(() => {
      const parse = (value: string) => {
        const digits = value.replace(/[^0-9]/g, '');
        return digits ? Number(digits) : undefined;
      };
      setMinPrice(parse(minPriceInput));
      setMaxPrice(parse(maxPriceInput));
    }, 300);
    return () => clearTimeout(timer);
  }, [minPriceInput, maxPriceInput]);

  // 다른 슬롯(카테고리)으로 넘어가면 검색·필터를 초기화한다.
  useEffect(() => {
    setSearchInput('');
    setQ('');
    setManufacturer('');
    setManufacturerOptions([]);
    setMinPriceInput('');
    setMaxPriceInput('');
    setMinPrice(undefined);
    setMaxPrice(undefined);
    setHideFail(false);
    setOnlyWishlist(false);
    setQuickViewPart(null);
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
    queryKey: ['parts', 'slot-candidates', slot.category, sort, q, manufacturer, minPrice, maxPrice, compatibilityMode ?? 'REPLACE', replaceTargetId],
    queryFn: ({ pageParam }) => listParts({
      category: slot.category,
      page: pageParam,
      size: CANDIDATE_PAGE_SIZE,
      sort,
      q: q || undefined,
      manufacturer: manufacturer || undefined,
      minPrice,
      maxPrice,
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

  // 제조사 필터 옵션: 공개 목록 API가 없어 로드된 후보에서 누적 수집한다(필터로 좁혀도 줄지 않게 누적).
  useEffect(() => {
    setManufacturerOptions((prev) => {
      const next = new Set(prev);
      let grew = false;
      for (const part of visibleParts) {
        const name = part.manufacturer;
        if (name && !next.has(name)) {
          next.add(name);
          grew = true;
        }
      }
      return grew ? [...next].sort((a, b) => a.localeCompare(b, 'ko')) : prev;
    });
  }, [visibleParts]);

  // 표시 필터(client-side): '장착 불가 숨기기'(FAIL 제외)와 '찜만 보기'(찜한 부품만).
  // 기본은 전부 표시(멘토 룰). 서버 검색·정렬·필터는 그대로 두고 렌더 단계에서만 좁힌다.
  const renderedParts = useMemo(() => {
    let list = hideFail ? visibleParts.filter((part) => part.compatibility?.status !== 'FAIL') : visibleParts;
    if (onlyWishlist) {
      list = list.filter((part) => wishlist.has(part.id));
    }
    return list;
  }, [visibleParts, hideFail, onlyWishlist, wishlist]);

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

      {/* 필터: 제조사·가격대(기존 GET /api/parts 파라미터 재사용) + 장착 불가 숨기기(client-side, 기본 꺼짐). */}
      <div className="shrink-0 border-b border-commerce-line px-4 py-2.5">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-2">
          <select
            aria-label="제조사 필터"
            data-testid="candidate-manufacturer"
            value={manufacturer}
            onChange={(event) => setManufacturer(event.target.value)}
            className="h-8 max-w-[150px] rounded-md border border-commerce-line bg-white px-2 text-xs font-bold text-slate-700 outline-none focus:border-brand-blue"
          >
            <option value="">전체 제조사</option>
            {manufacturerOptions.map((name) => (
              <option key={name} value={name}>{name}</option>
            ))}
          </select>
          <div className="flex items-center gap-1">
            <input
              inputMode="numeric"
              aria-label="최소 가격"
              data-testid="candidate-min-price"
              value={minPriceInput}
              onChange={(event) => setMinPriceInput(event.target.value)}
              placeholder="최소"
              className="h-8 w-[68px] rounded-md border border-commerce-line bg-white px-2 text-right text-xs font-bold text-commerce-ink outline-none placeholder:font-semibold placeholder:text-slate-400 focus:border-brand-blue"
            />
            <span className="text-xs font-bold text-slate-400">~</span>
            <input
              inputMode="numeric"
              aria-label="최대 가격"
              data-testid="candidate-max-price"
              value={maxPriceInput}
              onChange={(event) => setMaxPriceInput(event.target.value)}
              placeholder="최대"
              className="h-8 w-[68px] rounded-md border border-commerce-line bg-white px-2 text-right text-xs font-bold text-commerce-ink outline-none placeholder:font-semibold placeholder:text-slate-400 focus:border-brand-blue"
            />
            <span className="text-xs font-bold text-slate-400">원</span>
          </div>
          <label className="ml-auto flex cursor-pointer select-none items-center gap-1.5 text-xs font-bold text-slate-600">
            <input
              type="checkbox"
              data-testid="candidate-only-wishlist"
              checked={onlyWishlist}
              onChange={(event) => setOnlyWishlist(event.target.checked)}
              className="h-3.5 w-3.5 accent-rose-500"
            />
            찜만
          </label>
          <label className="flex cursor-pointer select-none items-center gap-1.5 text-xs font-bold text-slate-600">
            <input
              type="checkbox"
              data-testid="candidate-hide-fail"
              checked={hideFail}
              onChange={(event) => setHideFail(event.target.checked)}
              className="h-3.5 w-3.5 accent-brand-blue"
            />
            장착 불가 숨기기
          </label>
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
          {renderedParts.map((part) => {
            const isSelected = selectedPartIds.has(part.id);
            const isWishlisted = wishlist.has(part.id);
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
                {/* 제품 사진·이름을 누르면 상세 페이지로 이동한다(담기와 구분되는 진입점 — 살아있던 상세 페이지 재연결). */}
                <Link to={`/parts/${part.id}`} aria-label={`${part.name} 상세 페이지 보기`} className="shrink-0">
                  <img
                    src={partImageUrl(part)}
                    alt={`${part.name} 제품 사진`}
                    className="h-12 w-12 rounded-md border border-commerce-line bg-slate-100 object-cover transition hover:opacity-90"
                  />
                </Link>
                <div className="min-w-0 flex-1 text-xs">
                  <Link to={`/parts/${part.id}`} className="line-clamp-2 font-black leading-4 text-commerce-ink hover:text-brand-blue hover:underline">
                    {part.name}
                  </Link>
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
                  {/* 빠른보기(상세 스펙 모달) + 찜(로컬 저장) */}
                  <div className="flex items-center justify-end gap-1">
                    <button
                      type="button"
                      aria-label={`${part.name} 빠른보기`}
                      data-testid="candidate-quick-view"
                      onClick={() => setQuickViewPart(part)}
                      className="grid h-7 w-7 place-items-center rounded-md border border-commerce-line bg-white text-slate-500 hover:border-commerce-ink hover:text-commerce-ink"
                    >
                      <Eye size={14} />
                    </button>
                    <button
                      type="button"
                      aria-label={isWishlisted ? `${part.name} 찜 해제` : `${part.name} 찜하기`}
                      aria-pressed={isWishlisted}
                      data-testid="candidate-wishlist"
                      onClick={() => toggleWishlist(part.id)}
                      className={`grid h-7 w-7 place-items-center rounded-md border bg-white transition ${
                        isWishlisted ? 'border-rose-200 text-rose-500' : 'border-commerce-line text-slate-400 hover:text-rose-500'
                      }`}
                    >
                      <Heart size={14} className={isWishlisted ? 'fill-rose-500' : ''} />
                    </button>
                  </div>
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

        {!isLoading && renderedParts.length === 0 && !hasNextPage ? (
          <div className="mt-2 rounded-md border border-dashed border-slate-300 p-4 text-center text-xs font-bold text-slate-500">
            {q
              ? `'${q}' 검색 결과가 없습니다.`
              : onlyWishlist
                ? '찜한 부품이 없습니다. 하트를 눌러 찜해 보세요.'
                : hideFail && visibleParts.length > 0
                  ? '장착 가능한 후보가 없습니다. 필터를 조정해 보세요.'
                  : '표시할 후보가 없습니다.'}
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
      {quickViewPart ? (
        <PartQuickView
          part={quickViewPart}
          isWishlisted={wishlist.has(quickViewPart.id)}
          onToggleWishlist={() => toggleWishlist(quickViewPart.id)}
          onClose={() => setQuickViewPart(null)}
        />
      ) : null}
    </>
  );
}

const WISHLIST_KEY = 'buildgraph.wishlist';

function readWishlist(): Set<string> {
  try {
    const raw = localStorage.getItem(WISHLIST_KEY);
    const parsed = raw ? (JSON.parse(raw) as unknown) : [];
    return new Set(Array.isArray(parsed) ? parsed.filter((id): id is string => typeof id === 'string') : []);
  } catch {
    return new Set();
  }
}

function writeWishlist(set: Set<string>) {
  try {
    localStorage.setItem(WISHLIST_KEY, JSON.stringify([...set]));
  } catch {
    // localStorage 접근 불가(프라이빗 모드 등)면 무시한다.
  }
}

// 부품 빠른보기: 로드된 후보 데이터로 상세 스펙·가격·구매처를 모달로 즉시 보여준다(추가 요청 없음).
function PartQuickView({
  part,
  isWishlisted,
  onToggleWishlist,
  onClose
}: {
  part: PartRow;
  isWishlisted: boolean;
  onToggleWishlist: () => void;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const rows = specRows(part);
  const status = part.compatibility?.status;

  return (
    <div
      className="fixed inset-0 z-[60] flex items-end justify-center bg-slate-900/50 sm:items-center sm:p-4"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-label={`${part.name} 상세`}
        data-testid="part-quick-view"
        onClick={(event) => event.stopPropagation()}
        className="max-h-[85vh] w-full max-w-md overflow-y-auto rounded-t-xl bg-white p-4 shadow-2xl sm:rounded-xl"
      >
        <div className="flex items-start justify-between gap-3">
          <h3 className="text-base font-black leading-5 text-commerce-ink">{part.name}</h3>
          <button type="button" aria-label="빠른보기 닫기" onClick={onClose} className="grid h-8 w-8 shrink-0 place-items-center rounded-md border border-commerce-line bg-white text-slate-600 hover:border-commerce-ink">
            <X size={15} />
          </button>
        </div>
        <div className="mt-3 flex gap-3">
          <img src={partImageUrl(part)} alt={`${part.name} 제품 사진`} className="h-20 w-20 shrink-0 rounded-md border border-commerce-line bg-slate-100 object-cover" />
          <div className="min-w-0 flex-1 text-xs">
            <div className="text-[11px] font-bold text-slate-500">{part.manufacturer ?? '-'}</div>
            <div className="mt-1 text-lg font-black text-commerce-ink">{part.price.toLocaleString()}원</div>
            <div className="mt-1 flex flex-wrap items-center gap-1.5">
              {status === 'PASS' ? <span className="rounded border border-emerald-100 bg-emerald-50 px-1.5 py-0.5 text-[10px] font-black text-emerald-700">호환 가능</span> : null}
              {status === 'WARN' ? <span className="rounded border border-amber-100 bg-amber-50 px-1.5 py-0.5 text-[10px] font-black text-amber-700">간섭 주의</span> : null}
              {status === 'FAIL' ? <span className="rounded border border-red-200 bg-red-50 px-1.5 py-0.5 text-[10px] font-black text-red-700">장착 불가</span> : null}
            </div>
            {part.compatibility?.summary ? (
              <div className="mt-1 text-[11px] leading-4 text-slate-500">{part.compatibility.summary}</div>
            ) : null}
          </div>
        </div>
        {rows.length > 0 ? (
          <dl className="mt-3 grid grid-cols-2 gap-1.5">
            {rows.map((row) => (
              <div key={row.label} className="rounded-md border border-commerce-line bg-slate-50 px-2 py-1.5">
                <dt className="text-[10px] font-bold text-slate-400">{row.label}</dt>
                <dd className="text-[11px] font-black text-commerce-ink">{row.value}</dd>
              </div>
            ))}
          </dl>
        ) : (
          <p className="mt-3 rounded-md border border-dashed border-slate-200 p-3 text-center text-[11px] font-bold text-slate-400">등록된 상세 스펙이 없습니다.</p>
        )}
        <div className="mt-4 flex items-center gap-2">
          <button
            type="button"
            data-testid="quick-view-wishlist"
            aria-pressed={isWishlisted}
            onClick={onToggleWishlist}
            className={`inline-flex min-h-9 flex-1 items-center justify-center gap-1.5 rounded-md border px-3 text-xs font-black transition ${
              isWishlisted ? 'border-rose-200 bg-rose-50 text-rose-600' : 'border-commerce-line bg-white text-slate-700 hover:text-rose-500'
            }`}
          >
            <Heart size={14} className={isWishlisted ? 'fill-rose-500 text-rose-500' : ''} /> {isWishlisted ? '찜 해제' : '찜하기'}
          </button>
          {part.externalOffer?.offerUrl ? (
            <a
              href={part.externalOffer.offerUrl}
              target="_blank"
              rel="noreferrer noopener"
              className="inline-flex min-h-9 flex-1 items-center justify-center gap-1.5 rounded-md bg-commerce-ink px-3 text-xs font-black text-white hover:bg-slate-700"
            >
              구매처에서 보기
            </a>
          ) : null}
        </div>
      </div>
    </div>
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
