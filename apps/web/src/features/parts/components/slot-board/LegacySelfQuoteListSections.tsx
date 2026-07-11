import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, FolderPlus, PackageCheck, Search, ShoppingCart, SlidersHorizontal } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { CategorySidebar, DataTable, Panel, StateMessage } from '../../../../components/ui';
import { getToken } from '../../../../lib/api';
import { saveBuildFromChat } from '../../../quote/quoteApi';
import { handlePartImageError, partImageUrl, partShortSpec } from '../../partDisplay';
import { deleteQuoteDraftItem, getCurrentQuoteDraft, getPartPriceHistory, listParts, patchQuoteDraftItem, putQuoteDraftItem } from '../../partsApi';
import { quoteDraftToRecommendedBuild, selfQuoteBuildId } from '../../selfQuoteBuild';
import type { PartRow, PartSearchParams, QuoteDraft, QuoteDraftItem } from '../../types';

// 슬롯 보드 전환 이전의 PC 카테고리 / 전체 부품 목록 / 견적 장바구니 3영역 UI 보존본.
// /self-quote에서는 렌더링하지 않는다.

const selfQuoteCategories = [
  { label: '셀프 견적', value: '' },
  { label: 'CPU', value: 'CPU' },
  { label: '메인보드', value: 'MOTHERBOARD' },
  { label: 'RAM', value: 'RAM' },
  { label: 'GPU', value: 'GPU' },
  { label: 'SSD', value: 'STORAGE' },
  { label: '파워', value: 'PSU' },
  { label: '케이스', value: 'CASE' },
  { label: '쿨러', value: 'COOLER' }
];

const PAGE_SIZE = 20;

export function LegacySelfQuoteListSections() {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const initialCategory = normalizeCategory(searchParams.get('category'));
  const initialQuery = searchParams.get('q') ?? '';
  const [category, setCategory] = useState<string>(() => initialCategory);
  const [query, setQuery] = useState(() => initialQuery);
  const [sort, setSort] = useState<PartSearchParams['sort']>(() => defaultSortForCategory(initialCategory));
  const [page, setPage] = useState(() => normalizePage(searchParams.get('page')));
  const [pendingPartActionId, setPendingPartActionId] = useState<string | null>(null);
  const hasToken = Boolean(getToken());
  const compatibilitySource = category ? 'QUOTE_DRAFT_CURRENT' : undefined;
  const { data, isError, isFetching, isLoading, isPlaceholderData } = useQuery({
    queryKey: ['parts', 'self-quote', category, query, sort, compatibilitySource, page],
    queryFn: () => listParts({ category, q: query, page, size: PAGE_SIZE, sort, compatibilitySource }),
    placeholderData: keepPreviousData,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true
  });
  const { data: quoteDraft, isError: isQuoteDraftError, isLoading: isQuoteDraftLoading } = useQuery({
    queryKey: ['quote-draft', 'current'],
    queryFn: getCurrentQuoteDraft,
    enabled: hasToken
  });
  // 드래프트가 바뀌면 현재 드래프트 기준 호환 배지를 그리는 부품 목록도 함께 재평가한다.
  // (SelfQuotePage의 invalidateQuoteDraft와 같은 결정 — 목록 쿼리 키 prefix만 이 화면 것으로)
  const invalidateQuoteDraft = () => {
    void queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] });
    void queryClient.invalidateQueries({ queryKey: ['parts', 'self-quote'] });
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
  const saveQuoteMutation = useMutation({
    mutationFn: (draft: QuoteDraft) => saveBuildFromChat({
      sourceBuildId: selfQuoteBuildId(draft),
      lastUserMessage: '셀프 견적에서 저장',
      build: quoteDraftToRecommendedBuild(draft)
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['build-history'] })
  });
  const parts = data?.items ?? [];
  const total = data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const fromIndex = total === 0 ? 0 : safePage * PAGE_SIZE + 1;
  const toIndex = total === 0 ? 0 : Math.min((safePage + 1) * PAGE_SIZE, total);
  const draftItems = quoteDraft?.items ?? [];
  const selectedTotal = quoteDraft?.totalPrice ?? 0;
  const selectedPartIds = new Set(draftItems.map((part) => part.partId));
  const filledSingleSlotCategories = new Set(draftItems.filter((part) => !allowsQuantity(part.category)).map((part) => part.category));
  const showPartsSkeleton = isLoading && !data;
  const showPartsRefreshing = isFetching && Boolean(data);

  useEffect(() => {
    const nextCategory = normalizeCategory(searchParams.get('category'));
    setCategory((current) => {
      if (current === nextCategory) {
        return current;
      }
      setSort(defaultSortForCategory(nextCategory));
      return nextCategory;
    });
    const nextPage = normalizePage(searchParams.get('page'));
    setPage((current) => current === nextPage ? current : nextPage);
    const nextQuery = searchParams.get('q') ?? '';
    setQuery((current) => current === nextQuery ? current : nextQuery);
  }, [searchParams]);

  const selectCategory = (nextCategory: string) => {
    const normalizedCategory = normalizeCategory(nextCategory);
    setCategory(normalizedCategory);
    setSort(defaultSortForCategory(normalizedCategory));
    setPage(0);
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      if (normalizedCategory) {
        nextParams.set('category', normalizedCategory);
      } else {
        nextParams.delete('category');
      }
      nextParams.delete('page');
      return nextParams;
    });
  };

  const updateQuery = (nextQuery: string) => {
    setQuery(nextQuery);
    setPage(0);
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      const trimmedQuery = nextQuery.trim();
      if (trimmedQuery) {
        nextParams.set('q', trimmedQuery);
      } else {
        nextParams.delete('q');
      }
      nextParams.delete('page');
      return nextParams;
    });
  };

  const updateSort = (nextSort: PartSearchParams['sort']) => {
    setSort(nextSort);
    setPage(0);
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      nextParams.delete('page');
      return nextParams;
    });
  };

  const movePage = useCallback((nextPage: number) => {
    const normalizedPage = Math.min(Math.max(nextPage, 0), totalPages - 1);
    setPage(normalizedPage);
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      if (normalizedPage === 0) {
        nextParams.delete('page');
      } else {
        nextParams.set('page', String(normalizedPage));
      }
      return nextParams;
    });
  }, [setSearchParams, totalPages]);

  useEffect(() => {
    if (!data || isPlaceholderData || page === safePage) {
      return;
    }
    movePage(safePage);
  }, [data, isPlaceholderData, movePage, page, safePage]);

  const addPart = (part: PartRow) => {
    if (!hasToken) {
      navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
      return;
    }
    setPendingPartActionId(part.id);
    addMutation.mutate(
      { partId: part.id, quantity: 1 },
      { onSettled: () => setPendingPartActionId(null) }
    );
  };

  const removePart = (partId: string) => {
    if (!hasToken) {
      navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
      return;
    }
    setPendingPartActionId(partId);
    deleteMutation.mutate(partId, {
      onSettled: () => setPendingPartActionId(null)
    });
  };

  const updateQuantity = (partId: string, quantity: number) => {
    if (!hasToken) {
      navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
      return;
    }
    quantityMutation.mutate({ partId, quantity });
  };

  return (
    <div className="grid gap-5 xl:grid-cols-[216px_minmax(0,1fr)_320px]">
      <CategorySidebar items={selfQuoteCategories} activeValue={category} onSelect={selectCategory} />

      <section className="min-w-0">
        <Panel title={categoryLabel(category)} subtitle="CPU/GPU/메인보드/파워/케이스/쿨러는 교체 저장, RAM/SSD는 여러 상품 추가가 가능합니다.">
          <div className="mb-4 grid gap-3 lg:grid-cols-[minmax(0,1fr)_160px_128px]">
            <label className="flex min-h-11 items-center gap-2 rounded-md border border-commerce-line bg-white px-3 focus-within:border-commerce-ink focus-within:ring-4 focus-within:ring-blue-100">
              <Search size={17} className="text-slate-400" />
              <span className="sr-only">부품 검색</span>
              <input value={query} onChange={(event) => updateQuery(event.target.value)} placeholder="부품명, 제조사, 사양 검색" className="min-w-0 flex-1 bg-transparent text-sm font-medium outline-none placeholder:text-slate-400" />
            </label>
            <label className="flex min-h-11 items-center gap-2 rounded-md border border-commerce-line bg-white px-3">
              <SlidersHorizontal size={17} className="text-slate-400" />
              <span className="sr-only">정렬 기준</span>
              <select aria-label="정렬 기준" value={sort} onChange={(event) => updateSort(event.target.value as PartSearchParams['sort'])} className="min-w-0 flex-1 bg-transparent text-sm font-bold text-slate-700 outline-none">
                {category ? <option value="compatibility">호환성순</option> : <option value="category">카테고리순</option>}
                <option value="price_asc">가격 낮은순</option>
                <option value="price_desc">가격 높은순</option>
                <option value="name">이름순</option>
              </select>
            </label>
            <button type="button" onClick={() => { selectCategory(''); updateQuery(''); }} className="min-h-11 rounded-md border border-commerce-line bg-white px-3 py-2 text-sm font-black text-slate-700 hover:border-commerce-ink hover:text-commerce-ink">
              전체 보기
            </button>
          </div>
          {showPartsSkeleton ? <PartsTableSkeleton showCompatibility={Boolean(category)} /> : null}
          {isError && !data ? <div className="rounded-md border border-orange-200 bg-orange-50 p-5 text-sm text-orange-700">부품 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.</div> : null}
          {!showPartsSkeleton && data ? (
            <div className="relative">
              {isError ? <div className="mb-3 rounded-md border border-orange-200 bg-orange-50 p-3 text-xs font-bold text-orange-700">새 부품 목록을 불러오지 못해 이전 목록을 유지합니다.</div> : null}
              <div className={showPartsRefreshing ? 'pointer-events-none opacity-45 transition-opacity duration-150' : 'transition-opacity duration-150'}>
                <div className="mb-3 flex flex-col gap-2 text-xs font-bold text-slate-500 sm:flex-row sm:items-center sm:justify-between">
                  <span>{total.toLocaleString()}개 중 {fromIndex.toLocaleString()}-{toIndex.toLocaleString()}개 표시</span>
                  <span>페이지 {safePage + 1} / {totalPages}</span>
                </div>
                <DataTable
                  columns={partTableColumns(Boolean(category))}
                  rows={partRows(parts, selectedPartIds, filledSingleSlotCategories, addPart, removePart, pendingPartActionId, Boolean(category))}
                />
                <div className="mt-4 flex items-center justify-end gap-2">
                  <button
                    type="button"
                    onClick={() => movePage(safePage - 1)}
                    disabled={safePage === 0 || showPartsRefreshing}
                    className="rounded-md border border-commerce-line bg-white px-3 py-2 text-sm font-black text-slate-700 disabled:cursor-not-allowed disabled:border-slate-200 disabled:text-slate-300"
                  >
                    이전
                  </button>
                  <button
                    type="button"
                    onClick={() => movePage(safePage + 1)}
                    disabled={safePage >= totalPages - 1 || showPartsRefreshing}
                    className="rounded-md border border-commerce-line bg-white px-3 py-2 text-sm font-black text-slate-700 disabled:cursor-not-allowed disabled:border-slate-200 disabled:text-slate-300"
                  >
                    다음
                  </button>
                </div>
              </div>
              {showPartsRefreshing ? (
                <div className="absolute inset-x-0 top-11 z-10 flex justify-center">
                  <div className="rounded-full border border-blue-100 bg-white/95 px-3 py-1.5 text-xs font-black text-brand-blue shadow-product">
                    목록 업데이트 중
                  </div>
                </div>
              ) : null}
            </div>
          ) : null}
        </Panel>
      </section>

      <aside className="min-w-0 xl:sticky xl:top-5 xl:self-start">
        <Panel
          title="견적 장바구니"
          subtitle="선택한 부품 총액과 검증 진입점을 확인합니다."
          action={quoteDraft && draftItems.length > 0 ? (
            <button
              type="button"
              onClick={() => saveQuoteMutation.mutate(quoteDraft)}
              disabled={saveQuoteMutation.isPending}
              className="inline-flex min-h-9 items-center gap-1.5 rounded-md bg-brand-blue px-3 text-xs font-black text-white hover:bg-blue-700 disabled:cursor-wait disabled:bg-slate-400"
            >
              <FolderPlus size={14} />
              {saveQuoteMutation.isPending ? '추가 중' : '내 견적함에 추가'}
            </button>
          ) : null}
        >
          <QuoteTotalCard totalPrice={selectedTotal} />
          <div className="mt-4 space-y-2">
            {!hasToken ? (
              <div className="rounded-md border border-dashed border-slate-300 p-4 text-sm text-slate-500">
                로그인하면 제품 상세와 목록에서 담은 부품이 서버 견적초안에 저장됩니다.
                <Link to={`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`} className="mt-3 block rounded-md bg-commerce-ink px-3 py-2 text-center text-xs font-black text-white">
                  로그인하고 견적 담기
                </Link>
              </div>
            ) : isQuoteDraftLoading ? (
              <div className="rounded-md border border-commerce-line p-4 text-sm text-slate-500">내 견적 장바구니를 불러오는 중입니다.</div>
            ) : isQuoteDraftError ? (
              <div className="rounded-md border border-orange-200 bg-orange-50 p-4 text-sm text-orange-700">견적 장바구니를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.</div>
            ) : draftItems.length === 0 ? (
              <div className="rounded-md border border-dashed border-slate-300 p-4 text-sm text-slate-500">
                왼쪽 목록에서 부품을 담으면 이곳에 내 견적이 쌓입니다.
              </div>
            ) : draftItems.map((part) => (
              <div key={part.partId} className="rounded-md border border-commerce-line bg-white p-3 text-xs">
                <div className="mb-2 flex items-center justify-between gap-2">
                  <span className="rounded bg-slate-100 px-2 py-1 font-black text-slate-700">{part.category}</span>
                  <span className="flex items-center gap-1 font-bold text-commerce-green"><CheckCircle2 size={13} /> 선택됨</span>
                </div>
                <div className="font-bold leading-5 text-commerce-ink">{part.name}</div>
                <div className="mt-1 text-slate-500">수량 {part.quantity}개</div>
                <PriceTrendBadge partId={part.partId} />
                <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
                  <span className="font-black text-brand-blue">{part.lineTotal.toLocaleString()}원</span>
                  <div className="flex items-center gap-2">
                    {allowsQuantity(part.category) ? <DraftQuantityStepper item={part} onChange={updateQuantity} disabled={quantityMutation.isPending} /> : null}
                    <button type="button" aria-label={`${part.name} 견적에서 제거`} onClick={() => removePart(part.partId)} className="rounded-md border border-commerce-line px-2 py-1 font-black text-slate-600 hover:border-commerce-sale hover:text-commerce-sale">
                      빼기
                    </button>
                  </div>
                </div>
              </div>
            ))}
            {saveQuoteMutation.isSuccess ? (
              <div className="space-y-2">
                <StateMessage type="success" title="내 견적함에 추가했습니다." body="저장된 견적은 내 견적함 / 목표가 알림 화면에서 다시 확인할 수 있습니다." />
                <Link to="/my/quotes" className="flex min-h-10 items-center justify-center rounded-md border border-emerald-200 bg-emerald-50 px-3 text-xs font-black text-emerald-700 hover:border-emerald-300">
                  내 견적함 보기
                </Link>
              </div>
            ) : null}
            {saveQuoteMutation.isError ? (
              <StateMessage type="warn" title="내 견적함 추가 실패" body="현재 견적을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요." />
            ) : null}
          </div>
          <div className="mt-4 space-y-3">
            <div className="flex w-full min-h-11 items-center justify-center gap-2 rounded-md border border-commerce-line bg-slate-50 px-4 py-3 text-center text-sm font-bold text-slate-600">
              <PackageCheck size={17} />
              부품 호환 검증은 셀프 견적 보드에서 확인할 수 있습니다.
            </div>
            {draftItems.length > 0 ? (
              <Link to="/checkout" className="flex min-h-11 items-center justify-center gap-2 rounded-md border border-commerce-line bg-white px-4 py-3 text-center text-sm font-black text-commerce-ink hover:border-commerce-ink">
                <ShoppingCart size={17} className="text-commerce-amber" />
                구매하기
              </Link>
            ) : (
              <button type="button" disabled className="flex w-full min-h-11 cursor-not-allowed items-center justify-center gap-2 rounded-md border border-slate-200 bg-slate-50 px-4 py-3 text-center text-sm font-black text-slate-300">
                <ShoppingCart size={17} />
                구매하기
              </button>
            )}
          </div>
        </Panel>
      </aside>
    </div>
  );
}

function QuoteTotalCard({ totalPrice }: { totalPrice: number }) {
  return (
    <div className="rounded-md border border-commerce-line bg-white p-4 shadow-sm">
      <div className="text-xs font-bold text-slate-500">견적 합계</div>
      <div className="mt-2 text-2xl font-black tracking-tight text-brand-blue">{totalPrice.toLocaleString()}원</div>
    </div>
  );
}

function compatibilityBadgeClassName(status: NonNullable<PartRow['compatibility']>['status']) {
  if (status === 'PASS') {
    return 'border-emerald-100 bg-emerald-50 text-emerald-700';
  }
  if (status === 'WARN') {
    return 'border-amber-100 bg-amber-50 text-amber-700';
  }
  return 'border-red-100 bg-red-50 text-red-700';
}

function compatibilityStatusLabel(status: NonNullable<PartRow['compatibility']>['status']) {
  if (status === 'PASS') {
    return '호환 가능';
  }
  if (status === 'WARN') {
    return '간섭 주의';
  }
  return '장착 불가';
}

function PartsTableSkeleton({ showCompatibility }: { showCompatibility: boolean }) {
  const columns = partTableColumns(showCompatibility);
  return (
    <div>
      <div className="mb-3 flex flex-col gap-2 text-xs font-bold text-slate-400 sm:flex-row sm:items-center sm:justify-between">
        <span>부품 목록 준비 중</span>
        <span>페이지 계산 중</span>
      </div>
      <div className="overflow-x-auto rounded-md border border-commerce-line bg-white">
        <table className="w-full min-w-[760px] border-collapse bg-white text-left text-xs">
          <thead className="bg-slate-50 text-slate-600">
            <tr>
              {columns.map((column) => <th key={column} className="border-b border-commerce-line px-3 py-3 font-black uppercase tracking-wide">{column}</th>)}
            </tr>
          </thead>
          <tbody>
            {Array.from({ length: 5 }).map((_, rowIndex) => (
              <tr key={rowIndex} className="border-b border-slate-100 last:border-0">
                {columns.map((column, columnIndex) => (
                  <td key={column} className="px-3 py-3 align-middle">
                    <div className={`h-4 rounded bg-slate-100 ${columnIndex === 0 ? 'w-48' : columnIndex === 4 ? 'w-16' : 'w-24'}`} />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="mt-4 flex justify-end gap-2">
        <div className="h-9 w-14 rounded-md border border-slate-200 bg-slate-50" />
        <div className="h-9 w-14 rounded-md border border-slate-200 bg-slate-50" />
      </div>
    </div>
  );
}

function partTableColumns(showCompatibility: boolean) {
  return showCompatibility
    ? ['상품', '제조사', '판매처', '가격', '호환 상태', '담기']
    : ['상품', '제조사', '판매처', '가격', '담기'];
}

function partRows(
  parts: PartRow[],
  selectedPartIds: Set<string>,
  filledSingleSlotCategories: Set<string>,
  onAddPart: (part: PartRow) => void,
  onRemovePart: (partId: string) => void,
  pendingPartActionId: string | null,
  showCompatibility: boolean
) {
  return parts.map((part) => {
    const isSelected = selectedPartIds.has(part.id);
    const isPending = pendingPartActionId === part.id;
    // 단일 슬롯 카테고리에 이미 다른 부품이 담겨 있으면 담기가 아니라 교체로 저장된다(패널 부제 참고).
    const isReplace = !isSelected && !allowsQuantity(part.category) && filledSingleSlotCategories.has(part.category);
    const row = {
      상품: <PartProductCell part={part} />,
      제조사: part.manufacturer ?? '-',
      판매처: <SupplierCell part={part} />,
      가격: <span className="whitespace-nowrap text-sm font-black text-commerce-ink">{part.price.toLocaleString()}원</span>,
      담기: (
        <button
          type="button"
          aria-label={isSelected ? `${part.name} 견적에서 제거` : isReplace ? `${part.name} 견적 교체` : `${part.name} 견적 담기`}
          disabled={isPending}
          onClick={() => isSelected ? onRemovePart(part.id) : onAddPart(part)}
          className={`rounded-md px-3 py-2 text-xs font-black transition focus:outline-none focus:ring-2 focus:ring-brand-blue disabled:cursor-wait disabled:opacity-60 ${
            isSelected
              ? 'border border-red-200 bg-red-50 text-red-700 hover:border-red-300 hover:bg-red-100'
              : 'bg-commerce-ink text-white hover:bg-slate-700'
          }`}
        >
          {isPending ? (isSelected ? '빼는 중' : isReplace ? '교체 중' : '담는 중') : isSelected ? '빼기' : isReplace ? '교체' : '담기'}
        </button>
      )
    };
    return showCompatibility
      ? {
          ...row,
          '호환 상태': <CompatibilityStatusCell part={part} />
        }
      : row;
  });
}

function CompatibilityStatusCell({ part }: { part: PartRow }) {
  const compatibility = part.compatibility;
  if (!compatibility) {
    return (
      <div className="min-w-[112px]">
        <span className="rounded-md border border-slate-200 bg-slate-50 px-2 py-1 text-[11px] font-black text-slate-500">평가 중</span>
      </div>
    );
  }
  return (
    <div className="min-w-[132px]">
      <span className={`inline-flex rounded-md border px-2 py-1 text-[11px] font-black ${compatibilityBadgeClassName(compatibility.status)}`}>
        {compatibility.statusLabel || compatibilityStatusLabel(compatibility.status)}
      </span>
      <div className="mt-1 line-clamp-2 max-w-[180px] break-keep text-[11px] leading-4 text-slate-500">{compatibility.summary}</div>
    </div>
  );
}

function PriceTrendBadge({ partId }: { partId: string }) {
  const { data } = useQuery({
    queryKey: ['parts', partId, 'price-history', 'all-sources'],
    queryFn: () => getPartPriceHistory(partId, { days: 3650, limit: 60 }),
    staleTime: 60_000
  });
  const points = [...(data?.items ?? [])]
    .filter((point) => Number.isFinite(point.price))
    .sort((first, second) => Date.parse(first.collectedAt) - Date.parse(second.collectedAt));

  if (points.length < 2) {
    return null;
  }

  const previousPrice = points[points.length - 2]?.price ?? 0;
  const latestPrice = points[points.length - 1]?.price ?? 0;
  if (previousPrice <= 0 || latestPrice <= 0) {
    return null;
  }

  const change = latestPrice - previousPrice;
  if (change === 0) {
    return null;
  }

  const changeRatePercent = (change / previousPrice) * 100;
  const tone = change > 0 ? 'text-orange-700' : 'text-emerald-700';
  const sign = change > 0 ? '+' : '';
  return (
    <div className={`mt-2 text-[11px] font-bold ${tone}`}>
      직전 기록 대비 {sign}{change.toLocaleString()}원 ({sign}{changeRatePercent.toFixed(2)}%)
    </div>
  );
}

function DraftQuantityStepper({ item, onChange, disabled }: { item: QuoteDraftItem; onChange: (partId: string, quantity: number) => void; disabled: boolean }) {
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

function allowsQuantity(category: string) {
  return category === 'RAM' || category === 'STORAGE';
}

function PartProductCell({ part }: { part: PartRow }) {
  return (
    <div className="flex min-w-[260px] items-center gap-3">
      <Link to={`/parts/${part.id}`} aria-label={`${part.name} 상세 보기`}>
        <img
          src={partImageUrl(part)}
          alt={`${part.name} 제품 사진`}
          onError={(event) => handlePartImageError(event, part.category)}
          className="h-16 w-16 rounded-md border border-commerce-line bg-slate-100 object-cover hover:border-commerce-ink"
        />
      </Link>
      <div>
        <Link to={`/parts/${part.id}`} className="font-black leading-5 text-commerce-ink hover:text-brand-blue hover:underline">{part.name}</Link>
        <div className="mt-1 text-[11px] font-medium text-slate-500">{partShortSpec(part)}</div>
      </div>
    </div>
  );
}

function SupplierCell({ part }: { part: PartRow }) {
  const supplierName = part.externalOffer?.supplierName;
  const offerUrl = part.externalOffer?.offerUrl;
  if (!supplierName) {
    return '-';
  }
  if (!offerUrl) {
    return supplierName;
  }
  return (
    <a href={offerUrl} target="_blank" rel="noreferrer" className="font-black text-brand-blue hover:underline">
      {supplierName}
    </a>
  );
}

function categoryLabel(category: string) {
  if (!category) {
    return '셀프 견적 / 전체 부품 목록';
  }
  const item = selfQuoteCategories.find((entry) => entry.value === category);
  return item ? `${item.label} 부품 목록` : '셀프 견적 / 전체 부품 목록';
}

function normalizeCategory(category: string | null) {
  return selfQuoteCategories.some((entry) => entry.value === category) ? category ?? '' : '';
}

function defaultSortForCategory(category: string): PartSearchParams['sort'] {
  return category ? 'compatibility' : 'category';
}

function normalizePage(page: string | null) {
  if (!page) {
    return 0;
  }
  const parsed = Number.parseInt(page, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}
