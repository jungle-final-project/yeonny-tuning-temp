import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, Bell, CheckCircle2, PackageCheck, Search, ShoppingCart, SlidersHorizontal, X } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { CategorySidebar, DataTable, MetricCard, Panel, Screen } from '../../../components/ui';
import { AUTH_CHANGED_EVENT, getToken } from '../../../lib/api';
import { AiBuildAssistant } from '../../quote/components/AiBuildAssistant';
import {
  AI_SELECTED_BUILD_CHANGED_EVENT,
  PART_CATEGORY_LABELS,
  clearSelectedAiBuild,
  readSelectedAiBuild,
  type AiSelectedBuild
} from '../../quote/aiSelection';
import { partImageUrl, partShortSpec } from '../partDisplay';
import { deleteQuoteDraftItem, getCurrentQuoteDraft, getPartPriceHistory, listParts, patchQuoteDraftItem, putQuoteDraftItem } from '../partsApi';
import type { PartRow, PartSearchParams, QuoteDraftItem } from '../types';

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

export function SelfQuotePage() {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const [category, setCategory] = useState<string>(() => normalizeCategory(searchParams.get('category')));
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<PartSearchParams['sort']>('category');
  const [page, setPage] = useState(() => normalizePage(searchParams.get('page')));
  const [aiBuild, setAiBuild] = useState<AiSelectedBuild | null>(() => readSelectedAiBuild());
  const hasToken = Boolean(getToken());
  const { data, isError, isLoading } = useQuery({
    queryKey: ['parts', 'self-quote', category, query, sort, page],
    queryFn: () => listParts({ category, q: query, page, size: PAGE_SIZE, sort }),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true
  });
  const { data: quoteDraft, isError: isQuoteDraftError, isLoading: isQuoteDraftLoading } = useQuery({
    queryKey: ['quote-draft', 'current'],
    queryFn: getCurrentQuoteDraft,
    enabled: hasToken
  });
  const addMutation = useMutation({
    mutationFn: ({ partId, quantity }: { partId: string; quantity: number }) => putQuoteDraftItem(partId, quantity),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] })
  });
  const deleteMutation = useMutation({
    mutationFn: (partId: string) => deleteQuoteDraftItem(partId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] })
  });
  const quantityMutation = useMutation({
    mutationFn: ({ partId, quantity }: { partId: string; quantity: number }) => patchQuoteDraftItem(partId, quantity),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] })
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

  useEffect(() => {
    const nextCategory = normalizeCategory(searchParams.get('category'));
    setCategory((current) => current === nextCategory ? current : nextCategory);
    const nextPage = normalizePage(searchParams.get('page'));
    setPage((current) => current === nextPage ? current : nextPage);
  }, [searchParams]);

  const selectCategory = (nextCategory: string) => {
    const normalizedCategory = normalizeCategory(nextCategory);
    setCategory(normalizedCategory);
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
    if (!data || page === safePage) {
      return;
    }
    movePage(safePage);
  }, [data, movePage, page, safePage]);

  useEffect(() => {
    const syncSelectedBuild = () => setAiBuild(readSelectedAiBuild());
    window.addEventListener(AI_SELECTED_BUILD_CHANGED_EVENT, syncSelectedBuild);
    window.addEventListener(AUTH_CHANGED_EVENT, syncSelectedBuild);
    window.addEventListener('storage', syncSelectedBuild);
    return () => {
      window.removeEventListener(AI_SELECTED_BUILD_CHANGED_EVENT, syncSelectedBuild);
      window.removeEventListener(AUTH_CHANGED_EVENT, syncSelectedBuild);
      window.removeEventListener('storage', syncSelectedBuild);
    };
  }, []);

  const addPart = (part: PartRow) => {
    if (!hasToken) {
      navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
      return;
    }
    addMutation.mutate({ partId: part.id, quantity: 1 });
  };

  const removePart = (partId: string) => {
    if (!hasToken) {
      navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
      return;
    }
    deleteMutation.mutate(partId);
  };

  const updateQuantity = (partId: string, quantity: number) => {
    if (!hasToken) {
      navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
      return;
    }
    quantityMutation.mutate({ partId, quantity });
  };

  return (
    <Screen>
      <div className="space-y-5">
        <section className="panel overflow-hidden">
          <div className="border-b border-commerce-line bg-white px-5 py-3">
            <div className="flex flex-wrap items-center gap-2 text-xs font-black">
              <span className="rounded bg-commerce-sale px-2 py-1 text-white">SALE</span>
              <span className="text-commerce-ink">셀프 견적 쇼핑</span>
              <span className="text-slate-400">카테고리별 내부 자산 · 저장된 현재가 기준</span>
            </div>
          </div>
          <div className="grid gap-4 p-5 lg:grid-cols-[minmax(0,1fr)_320px] lg:items-end">
            <div>
              <div className="flex items-center gap-2 text-xs font-black text-brand-blue">
                <ShoppingCart size={16} />
                Build cart workspace
              </div>
              <h1 className="mt-2 text-2xl font-black tracking-tight text-commerce-ink sm:text-3xl">부품을 고르고 견적 장바구니에 담으세요</h1>
              <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-600">
                다나와식 카테고리 탐색과 쇼핑몰형 상품 목록을 유지하면서, 오른쪽에서 총액과 검증 진입점을 바로 확인합니다.
              </p>
            </div>
            <div className="grid grid-cols-3 gap-2 text-center text-xs">
              <div className="rounded-md border border-commerce-line bg-slate-50 p-3">
                <div className="font-black text-commerce-ink">{total.toLocaleString()}</div>
                <div className="mt-1 text-slate-500">상품</div>
              </div>
              <div className="rounded-md border border-commerce-line bg-slate-50 p-3">
                <div className="font-black text-commerce-ink">{draftItems.length}</div>
                <div className="mt-1 text-slate-500">선택</div>
              </div>
              <div className="rounded-md border border-commerce-line bg-slate-50 p-3">
                <div className="font-black text-commerce-green">PASS</div>
                <div className="mt-1 text-slate-500">검증</div>
              </div>
            </div>
          </div>
        </section>

        {aiBuild ? (
          <AiSelectedBuildPanel
            build={aiBuild}
            selectedPartIds={selectedPartIds}
            onClear={() => {
              clearSelectedAiBuild();
              setAiBuild(null);
            }}
          />
        ) : null}

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
                    <option value="category">카테고리순</option>
                    <option value="price_asc">가격 낮은순</option>
                    <option value="price_desc">가격 높은순</option>
                    <option value="name">이름순</option>
                  </select>
                </label>
                <button type="button" onClick={() => { selectCategory(''); updateQuery(''); }} className="min-h-11 rounded-md border border-commerce-line bg-white px-3 py-2 text-sm font-black text-slate-700 hover:border-commerce-ink hover:text-commerce-ink">
                  전체 보기
                </button>
              </div>
              {isLoading ? <div className="rounded-md border border-commerce-line p-5 text-sm text-slate-500">부품 목록을 불러오는 중입니다.</div> : null}
              {isError ? <div className="rounded-md border border-orange-200 bg-orange-50 p-5 text-sm text-orange-700">부품 목록 API를 불러오지 못했습니다.</div> : null}
              {!isLoading && !isError ? (
                <>
                  <div className="mb-3 flex flex-col gap-2 text-xs font-bold text-slate-500 sm:flex-row sm:items-center sm:justify-between">
                    <span>{total.toLocaleString()}개 중 {fromIndex.toLocaleString()}-{toIndex.toLocaleString()}개 표시</span>
                    <span>페이지 {safePage + 1} / {totalPages}</span>
                  </div>
                  <DataTable columns={['product', 'manufacturer', 'supplier', 'price', 'action']} rows={partRows(parts, selectedPartIds, addPart)} />
                  <div className="mt-4 flex items-center justify-end gap-2">
                    <button
                      type="button"
                      onClick={() => movePage(safePage - 1)}
                      disabled={safePage === 0}
                      className="rounded-md border border-commerce-line bg-white px-3 py-2 text-sm font-black text-slate-700 disabled:cursor-not-allowed disabled:border-slate-200 disabled:text-slate-300"
                    >
                      이전
                    </button>
                    <button
                      type="button"
                      onClick={() => movePage(safePage + 1)}
                      disabled={safePage >= totalPages - 1}
                      className="rounded-md border border-commerce-line bg-white px-3 py-2 text-sm font-black text-slate-700 disabled:cursor-not-allowed disabled:border-slate-200 disabled:text-slate-300"
                    >
                      다음
                    </button>
                  </div>
                </>
              ) : null}
            </Panel>
          </section>

          <aside className="min-w-0 xl:sticky xl:top-5 xl:self-start">
            <Panel title="견적 장바구니" subtitle="선택한 부품 총액과 검증 진입점을 확인합니다.">
              <MetricCard label="견적 합계" value={`${selectedTotal.toLocaleString()}원`} />
              <div className="mt-4 space-y-2">
                {!hasToken ? (
                  <div className="rounded-md border border-dashed border-slate-300 p-4 text-sm text-slate-500">
                    로그인하면 제품 상세와 목록에서 담은 부품이 서버 견적초안에 저장됩니다.
                    <Link to={`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`} className="mt-3 block rounded-md bg-commerce-ink px-3 py-2 text-center text-xs font-black text-white">
                      로그인하고 견적 담기
                    </Link>
                  </div>
                ) : isQuoteDraftLoading ? (
                  <div className="rounded-md border border-commerce-line p-4 text-sm text-slate-500">내 견적초안을 불러오는 중입니다.</div>
                ) : isQuoteDraftError ? (
                  <div className="rounded-md border border-orange-200 bg-orange-50 p-4 text-sm text-orange-700">견적초안 API를 불러오지 못했습니다.</div>
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
              </div>
              <div className="mt-4 space-y-3">
                <button className="flex w-full min-h-11 items-center justify-center gap-2 rounded-md bg-commerce-ink px-4 py-3 text-sm font-black text-white hover:bg-slate-700">
                  <PackageCheck size={17} />
                  Tool 검증하기
                </button>
                <Link to="/builds/00000000-0000-4000-8000-000000002001" className="flex min-h-11 items-center justify-center gap-2 rounded-md border border-commerce-line bg-white px-4 py-3 text-center text-sm font-black text-commerce-ink hover:border-commerce-ink">
                  <AlertTriangle size={17} className="text-commerce-amber" />
                  구매하기
                </Link>
              </div>
            </Panel>
          </aside>
        </div>
      </div>
      <AiBuildAssistant surface="self-quote" />
    </Screen>
  );
}

function AiSelectedBuildPanel({
  build,
  selectedPartIds,
  onClear
}: {
  build: AiSelectedBuild;
  selectedPartIds: Set<string>;
  onClear: () => void;
}) {
  const duplicateCount = build.items.filter((item) => selectedPartIds.has(item.partId)).length;
  const appliedPartCategories = build.appliedPartCategories ?? [];

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
            {duplicateCount > 0 ? <span className="rounded bg-emerald-50 px-2 py-1 text-[11px] font-black text-emerald-700">중복 {duplicateCount}개 감지</span> : null}
          </div>
          <h2 className="text-xl font-black text-commerce-ink">AI 선택 조합</h2>
          <p className="mt-2 max-w-3xl break-keep text-sm leading-6 text-slate-600">
            {build.title} · {build.summary} 선택 시점의 AI 조합과 현재 견적 장바구니 반영 상태를 비교합니다.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="rounded-md bg-white px-4 py-3 text-right">
            <div className="text-xs font-bold text-slate-500">AI 조합 합계</div>
            <div className="text-lg font-black text-commerce-sale">{build.totalPrice.toLocaleString()}원</div>
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
        {build.items.map((item) => {
          const alreadySelected = selectedPartIds.has(item.partId);
          const categoryLabel = PART_CATEGORY_LABELS[item.category] ?? item.category;
          return (
            <div key={item.partId} className="rounded-lg border border-commerce-line bg-white p-3 text-xs">
              <div className="mb-2 flex items-center justify-between gap-2">
                <span className="rounded bg-slate-100 px-2 py-1 font-black text-slate-700">{categoryLabel}</span>
                <span className={`rounded px-2 py-1 font-black ${alreadySelected ? 'bg-emerald-50 text-emerald-700' : 'bg-blue-50 text-brand-blue'}`}>
                  {alreadySelected ? '이미 담김' : '별도 표시'}
                </span>
              </div>
              <div className="min-h-10 font-black leading-5 text-commerce-ink">{item.name}</div>
              <div className="mt-1 text-slate-500">{item.manufacturer} · 수량 {item.quantity}</div>
              <div className="mt-2 break-keep text-slate-500">{item.note}</div>
              <div className="mt-3 font-black text-brand-blue">{item.price.toLocaleString()}원</div>
            </div>
          );
        })}
      </div>

      <div className="flex flex-col gap-2 border-t border-blue-100 bg-white px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="break-keep text-xs font-bold leading-5 text-slate-500">
          AI 조합 적용은 서버 batch API로 처리되며, 현재 견적 장바구니에 있는 부품은 이미 담김으로 표시합니다.
        </div>
        <Link to="/my/quotes" className="inline-flex min-h-10 items-center justify-center gap-2 rounded-md border border-commerce-line bg-white px-4 text-sm font-black text-commerce-ink hover:border-commerce-ink">
          <Bell size={16} />
          목표가 알림 설정
        </Link>
      </div>
    </section>
  );
}

function partRows(parts: PartRow[], selectedPartIds: Set<string>, onAddPart: (part: PartRow) => void) {
  return parts.map((part) => ({
    product: <PartProductCell part={part} />,
    manufacturer: part.manufacturer ?? '-',
    supplier: <SupplierCell part={part} />,
    price: `${part.price.toLocaleString()}원`,
    action: (
      <button
        type="button"
        aria-label={`${part.name} 견적 담기`}
        disabled={selectedPartIds.has(part.id)}
        onClick={() => onAddPart(part)}
        className="rounded-md bg-commerce-ink px-3 py-2 text-xs font-black text-white transition hover:bg-slate-700 disabled:bg-slate-300"
      >
        {selectedPartIds.has(part.id) ? '담김' : '담기'}
      </button>
    )
  }));
}

function PriceTrendBadge({ partId }: { partId: string }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['parts', partId, 'price-history', 'NAVER_SHOPPING_SEARCH'],
    queryFn: () => getPartPriceHistory(partId, { days: 3650, source: 'NAVER_SHOPPING_SEARCH', limit: 60 })
  });
  if (isLoading) {
    return <div className="mt-2 text-[11px] text-slate-400">가격 기록 확인 중</div>;
  }
  if (isError || !data) {
    return <div className="mt-2 text-[11px] text-slate-400">가격 기록 없음</div>;
  }
  const sampleCount = data.summary.sampleCount;
  if (sampleCount < 2) {
    return <div className="mt-2 text-[11px] text-slate-500">가격 기록 {sampleCount}개</div>;
  }
  const change = data.summary.changeAmount;
  const tone = change > 0 ? 'text-orange-700' : change < 0 ? 'text-emerald-700' : 'text-slate-500';
  const sign = change > 0 ? '+' : '';
  return (
    <div className={`mt-2 text-[11px] font-bold ${tone}`}>
      {sampleCount}회 기록 · {sign}{change.toLocaleString()}원 ({sign}{data.summary.changeRatePercent.toFixed(2)}%)
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
          className="h-16 w-16 rounded-md border border-commerce-line bg-slate-100 object-cover hover:border-commerce-ink"
        />
      </Link>
      <div>
        <Link to={`/parts/${part.id}`} className="font-black leading-5 text-commerce-ink hover:text-brand-blue hover:underline">{part.name}</Link>
        <div className="mt-1 text-[11px] font-medium text-slate-500">{partShortSpec(part)}</div>
        <div className="mt-2 inline-flex rounded bg-blue-50 px-2 py-1 text-[11px] font-black text-brand-blue">호환성 체크 가능</div>
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

function normalizePage(page: string | null) {
  if (!page) {
    return 0;
  }
  const parsed = Number.parseInt(page, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}
