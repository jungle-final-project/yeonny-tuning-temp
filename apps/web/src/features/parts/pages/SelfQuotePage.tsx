import { useMutation, useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { CategorySidebar, DataTable, MetricCard, Panel, Screen, StatusBadge } from '../../../components/ui';
import { getPartPriceHistory, listParts, runToolCheck } from '../partsApi';
import type { PartRow, PartSearchParams, ToolRow } from '../types';

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
const TOOL_ORDER = ['compatibility', 'power', 'size', 'performance', 'price'] as const;

export function SelfQuotePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [category, setCategory] = useState<string>(() => normalizeCategory(searchParams.get('category')));
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<PartSearchParams['sort']>('category');
  const [page, setPage] = useState(() => normalizePage(searchParams.get('page')));
  const [selectedParts, setSelectedParts] = useState<PartRow[]>([]);
  const { data, isError, isLoading } = useQuery({
    queryKey: ['parts', 'self-quote', category, query, sort, page],
    queryFn: () => listParts({ category, q: query, page, size: PAGE_SIZE, sort }),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true
  });
  const parts = data?.items ?? [];
  const total = data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const fromIndex = total === 0 ? 0 : safePage * PAGE_SIZE + 1;
  const toIndex = total === 0 ? 0 : Math.min((safePage + 1) * PAGE_SIZE, total);
  const selectedTotal = selectedParts.reduce((sum, part) => sum + part.price, 0);
  const selectedPartIds = new Set(selectedParts.map((part) => part.id));
  const toolMutation = useMutation({
    mutationFn: () => Promise.all(TOOL_ORDER.map((tool) => runToolCheck(tool, {
      partIds: selectedParts.map((part) => part.id),
      context: {
        currentTotalPrice: selectedTotal,
        budget: selectedTotal
      }
    })))
  });

  useEffect(() => {
    const nextCategory = normalizeCategory(searchParams.get('category'));
    setCategory((current) => current === nextCategory ? current : nextCategory);
    const nextPage = normalizePage(searchParams.get('page'));
    setPage((current) => current === nextPage ? current : nextPage);
  }, [searchParams]);

  useEffect(() => {
    if (parts.length === 0) {
      return;
    }
    setSelectedParts((current) => current.map((selectedPart) => (
      parts.find((part) => part.id === selectedPart.id) ?? selectedPart
    )));
  }, [parts]);

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

  const addPart = (part: PartRow) => {
    setSelectedParts((current) => current.some((item) => item.id === part.id) ? current : [...current, part]);
    toolMutation.reset();
  };

  const removePart = (partId: string) => {
    setSelectedParts((current) => current.filter((part) => part.id !== partId));
    toolMutation.reset();
  };

  return (
    <Screen>
      <div className="grid grid-cols-[216px_1fr_300px] gap-5">
        <CategorySidebar items={selfQuoteCategories} activeValue={category} onSelect={selectCategory} />
        <Panel title={categoryLabel(category)} subtitle="왼쪽 카테고리를 누르면 내부 부품 DB 후보가 여기에 나열됩니다.">
          <div className="mb-4 grid grid-cols-[1fr_160px_140px] gap-3">
            <input value={query} onChange={(event) => updateQuery(event.target.value)} placeholder="부품명, 제조사, 사양 검색" className="rounded border border-slate-300 px-3 py-2 text-sm" />
            <select aria-label="정렬 기준" value={sort} onChange={(event) => updateSort(event.target.value as PartSearchParams['sort'])} className="rounded border border-slate-300 px-3 py-2 text-sm">
              <option value="category">카테고리순</option>
              <option value="price_asc">가격 낮은순</option>
              <option value="price_desc">가격 높은순</option>
              <option value="name">이름순</option>
            </select>
            <button type="button" onClick={() => { selectCategory(''); updateQuery(''); }} className="rounded border border-slate-300 px-3 py-2 text-sm font-bold text-slate-700 hover:border-brand-blue hover:text-brand-blue">
              전체 보기
            </button>
          </div>
          {isLoading ? <div className="rounded border border-slate-200 p-5 text-sm text-slate-500">부품 목록을 불러오는 중입니다.</div> : null}
          {isError ? <div className="rounded border border-orange-200 bg-orange-50 p-5 text-sm text-orange-700">부품 목록 API를 불러오지 못했습니다.</div> : null}
          {!isLoading && !isError ? (
            <>
              <div className="mb-3 flex items-center justify-between text-xs text-slate-500">
                <span>{total.toLocaleString()}개 중 {fromIndex.toLocaleString()}-{toIndex.toLocaleString()}개 표시</span>
                <span>페이지 {safePage + 1} / {totalPages}</span>
              </div>
              <DataTable columns={['product', 'manufacturer', 'supplier', 'price', 'action']} rows={partRows(parts, selectedPartIds, addPart)} />
              <div className="mt-4 flex items-center justify-end gap-2">
                <button
                  type="button"
                  onClick={() => movePage(safePage - 1)}
                  disabled={safePage === 0}
                  className="rounded border border-slate-300 px-3 py-2 text-sm font-bold text-slate-700 disabled:cursor-not-allowed disabled:border-slate-200 disabled:text-slate-300"
                >
                  이전
                </button>
                <button
                  type="button"
                  onClick={() => movePage(safePage + 1)}
                  disabled={safePage >= totalPages - 1}
                  className="rounded border border-slate-300 px-3 py-2 text-sm font-bold text-slate-700 disabled:cursor-not-allowed disabled:border-slate-200 disabled:text-slate-300"
                >
                  다음
                </button>
              </div>
            </>
          ) : null}
        </Panel>
        <Panel title="내 견적 / 검증">
          <MetricCard label="견적 합계" value={`${selectedTotal.toLocaleString()}원`} />
          <div className="mt-4 space-y-2">
            {selectedParts.length === 0 ? (
              <div className="rounded border border-dashed border-slate-300 p-4 text-sm text-slate-500">
                왼쪽 목록에서 부품을 담으면 이곳에 내 견적이 쌓입니다.
              </div>
            ) : selectedParts.map((part) => (
              <div key={part.id} className="rounded border border-slate-200 bg-white p-3 text-xs">
                <div className="mb-1 font-bold text-slate-900">{part.category}</div>
                <div className="text-slate-700">{part.name}</div>
                <PriceTrendBadge partId={part.id} />
                <div className="mt-2 flex items-center justify-between">
                  <span className="font-bold text-brand-blue">{part.price.toLocaleString()}원</span>
                  <button type="button" aria-label={`${part.name} 견적에서 제거`} onClick={() => removePart(part.id)} className="rounded border border-slate-300 px-2 py-1 font-bold text-slate-600 hover:border-orange-400 hover:text-orange-600">
                    빼기
                  </button>
                </div>
              </div>
            ))}
          </div>
          <div className="mt-4 space-y-3">
            <button
              type="button"
              onClick={() => toolMutation.mutate()}
              disabled={selectedParts.length === 0 || toolMutation.isPending}
              className="w-full rounded bg-brand-blue px-4 py-3 text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-300"
            >
              {toolMutation.isPending ? '검증 중' : 'Tool 검증하기'}
            </button>
            {toolMutation.isError ? (
              <div className="rounded border border-orange-200 bg-orange-50 p-3 text-xs text-orange-700">
                Tool 검증 API를 불러오지 못했습니다.
              </div>
            ) : null}
            {toolMutation.data ? <ToolResultList results={toolMutation.data} /> : null}
            <Link to="/builds/00000000-0000-4000-8000-000000002001" className="block rounded border border-slate-300 px-4 py-3 text-center text-sm font-bold">추천 결과로 보기</Link>
          </div>
        </Panel>
      </div>
    </Screen>
  );
}

function ToolResultList({ results }: { results: ToolRow[] }) {
  return (
    <div className="space-y-2">
      {results.map((result) => (
        <div key={result.tool} className="rounded border border-slate-200 bg-white p-3 text-xs">
          <div className="mb-2 flex items-center justify-between gap-2">
            <span className="font-bold text-slate-900">{result.tool}</span>
            <div className="flex shrink-0 items-center gap-1">
              <StatusBadge status={result.status} />
              <StatusBadge status={result.confidence} />
            </div>
          </div>
          <div className="text-slate-700">{result.summary}</div>
          <div className="mt-2 text-[11px] text-slate-500">
            score {formatScore(result.score)} · {evidenceSummary(result)}
          </div>
        </div>
      ))}
    </div>
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
        className="rounded bg-brand-blue px-3 py-1.5 text-xs font-bold text-white disabled:bg-slate-300"
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

function PartProductCell({ part }: { part: PartRow }) {
  return (
    <div className="flex min-w-[260px] items-center gap-3">
      <img
        src={partImageUrl(part)}
        alt={`${part.name} 제품 사진`}
        className="h-14 w-14 rounded border border-slate-200 bg-slate-100 object-cover"
      />
      <div>
        <div className="font-bold text-slate-900">{part.name}</div>
        <div className="mt-1 text-[11px] text-slate-500">{partShortSpec(part)}</div>
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
    <a href={offerUrl} target="_blank" rel="noreferrer" className="font-bold text-brand-blue hover:underline">
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

function partShortSpec(part: PartRow) {
  const shortSpec = part.attributes?.shortSpec;
  return typeof shortSpec === 'string' ? shortSpec : part.category;
}

function partImageUrl(part: PartRow) {
  const imageUrl = part.externalOffer?.imageUrl ?? part.attributes?.imageUrl;
  if (typeof imageUrl === 'string' && imageUrl.trim()) {
    return imageUrl;
  }

  const label = part.category === 'STORAGE' ? 'SSD' : part.category;
  const accent = categoryAccent(part.category);
  const svg = `
    <svg xmlns="http://www.w3.org/2000/svg" width="112" height="112" viewBox="0 0 112 112">
      <rect width="112" height="112" rx="14" fill="#f8fafc"/>
      <rect x="12" y="20" width="88" height="56" rx="10" fill="${accent}" opacity="0.92"/>
      <rect x="20" y="28" width="72" height="40" rx="6" fill="#ffffff" opacity="0.16"/>
      <text x="56" y="54" text-anchor="middle" font-family="Arial, sans-serif" font-size="18" font-weight="700" fill="#ffffff">${label}</text>
      <rect x="24" y="84" width="64" height="6" rx="3" fill="#cbd5e1"/>
    </svg>
  `;
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
}

function formatScore(score: number | undefined) {
  return typeof score === 'number' ? score.toFixed(2) : '-';
}

function evidenceSummary(result: ToolRow) {
  const firstEvidence = result.evidence?.[0];
  return firstEvidence?.source_id ?? firstEvidence?.sourceId ?? '근거 없음';
}

function categoryAccent(category: string) {
  switch (category) {
    case 'CPU':
      return '#2563eb';
    case 'MOTHERBOARD':
      return '#475569';
    case 'RAM':
      return '#16a34a';
    case 'GPU':
      return '#7c3aed';
    case 'STORAGE':
      return '#0891b2';
    case 'PSU':
      return '#ca8a04';
    case 'CASE':
      return '#dc2626';
    case 'COOLER':
      return '#0f766e';
    default:
      return '#334155';
  }
}
