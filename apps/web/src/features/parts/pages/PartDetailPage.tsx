import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { Panel, Screen, StateMessage } from '../../../components/ui';
import { getToken } from '../../../lib/api';
import { partImageUrl, partShortSpec, specRows } from '../partDisplay';
import { getPart, getPartPriceHistory, putQuoteDraftItem } from '../partsApi';
import type { PartPriceHistory, PartPriceHistoryPoint } from '../types';

export function PartDetailPage() {
  const { partId = '' } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [quantity, setQuantity] = useState(1);
  const [added, setAdded] = useState(false);
  const hasToken = Boolean(getToken());
  const { data: part, isLoading, isError } = useQuery({
    queryKey: ['parts', partId],
    queryFn: () => getPart(partId),
    enabled: Boolean(partId)
  });
  const { data: priceHistory, isLoading: isPriceHistoryLoading, isError: isPriceHistoryError } = useQuery({
    queryKey: ['parts', partId, 'price-history', 'all-sources'],
    queryFn: () => getPartPriceHistory(partId, { days: 3650, limit: 120 }),
    enabled: Boolean(partId)
  });
  const maxQuantity = part ? maxDraftQuantity(part.category) : 9;
  const addMutation = useMutation({
    mutationFn: (nextQuantity: number) => putQuoteDraftItem(partId, nextQuantity),
    onSuccess: () => {
      setAdded(true);
      queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] });
    }
  });

  const addToDraft = () => {
    if (!hasToken) {
      navigate(`/login?redirect=${encodeURIComponent(location.pathname)}`);
      return;
    }
    setAdded(false);
    addMutation.mutate(quantity);
  };

  useEffect(() => {
    setQuantity((value) => Math.min(value, maxQuantity));
  }, [maxQuantity]);

  if (isLoading) {
    return (
      <Screen>
        <div className="rounded border border-slate-200 bg-white p-8 text-sm text-slate-500">상품 상세를 불러오는 중입니다.</div>
      </Screen>
    );
  }

  if (isError || !part) {
    return (
      <Screen>
        <StateMessage type="warn" title="상품 상세 조회 실패" body="GET /api/parts/{id} 응답을 확인해야 합니다." />
      </Screen>
    );
  }

  const rows = specRows(part);
  const offerUrl = part.externalOffer?.offerUrl;

  return (
    <Screen>
      <div className="mb-4 flex items-center justify-between">
        <Link to={`/self-quote?category=${part.category}`} className="text-sm font-bold text-brand-blue hover:underline">
          부품 목록으로 돌아가기
        </Link>
        <span className="rounded border border-slate-200 bg-slate-50 px-3 py-1 text-xs font-bold text-slate-500">{part.category}</span>
      </div>

      <div className="grid grid-cols-[620px_1fr] gap-6">
        <section className="rounded border border-slate-200 bg-white p-6">
          <img src={partImageUrl(part)} alt={`${part.name} 제품 사진`} className="h-[520px] w-full object-contain" />
        </section>

        <section className="rounded border border-slate-200 bg-white p-6">
          <div className="text-sm text-slate-500">{part.manufacturer ?? '제조사 미확인'}</div>
          <h1 className="mt-2 text-2xl font-bold leading-snug text-slate-950">{part.name}</h1>
          <p className="mt-2 text-sm text-slate-500">{partShortSpec(part)}</p>

          <div className="mt-6 border-t border-slate-200 pt-5">
            <div className="text-sm text-slate-500">현재가</div>
            <div className="mt-1 text-3xl font-extrabold text-red-600">{part.price.toLocaleString()}원</div>
            <div className="mt-3 grid grid-cols-[96px_1fr] gap-y-2 text-sm">
              <div className="text-slate-500">공급처</div>
              <div className="font-bold text-slate-800">{part.externalOffer?.supplierName ?? '저장된 공급처 없음'}</div>
              <div className="text-slate-500">가격 출처</div>
              <div className="text-slate-700">{part.latestPriceCollectedAt ? `${formatDate(part.latestPriceCollectedAt)} 갱신` : '저장된 현재가'}</div>
            </div>
          </div>

          <div className="mt-6 rounded border border-slate-200">
            <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
              <span className="text-sm font-bold text-slate-900">수량 선택</span>
              {maxQuantity > 1 ? (
                <div className="flex h-9 overflow-hidden rounded border border-slate-300">
                  <button type="button" onClick={() => setQuantity((value) => Math.max(1, value - 1))} className="w-10 bg-slate-50 text-lg font-bold text-slate-600">-</button>
                  <div className="flex w-12 items-center justify-center border-x border-slate-300 text-sm font-bold">{quantity}</div>
                  <button type="button" onClick={() => setQuantity((value) => Math.min(maxQuantity, value + 1))} className="w-10 bg-slate-50 text-lg font-bold text-slate-600">+</button>
                </div>
              ) : (
                <div className="rounded border border-slate-200 bg-slate-50 px-3 py-2 text-xs font-bold text-slate-500">1개 고정</div>
              )}
            </div>
            <div className="flex items-center justify-between px-4 py-4">
              <span className="text-sm text-slate-500">총 {quantity}개</span>
              <span className="text-xl font-extrabold text-red-600">{(part.price * quantity).toLocaleString()}원</span>
            </div>
          </div>

          <div className="mt-5 grid grid-cols-2 gap-3">
            <button
              type="button"
              onClick={addToDraft}
              disabled={addMutation.isPending}
              className="h-12 rounded bg-brand-blue text-sm font-bold text-white disabled:bg-slate-300"
            >
              {addMutation.isPending ? '담는 중' : '견적에 담기'}
            </button>
            {offerUrl ? (
              <a href={offerUrl} target="_blank" rel="noreferrer" className="flex h-12 items-center justify-center rounded border border-slate-300 text-sm font-bold text-slate-800 hover:border-brand-blue hover:text-brand-blue">
                구매처 홈페이지로 이동
              </a>
            ) : (
              <button type="button" disabled className="h-12 rounded border border-slate-200 text-sm font-bold text-slate-300">
                구매처 정보 없음
              </button>
            )}
          </div>

          {added ? <div className="mt-4 rounded border border-emerald-200 bg-emerald-50 p-3 text-sm font-bold text-emerald-700">내 견적초안에 저장했습니다.</div> : null}
          {addMutation.isError ? <div className="mt-4 rounded border border-orange-200 bg-orange-50 p-3 text-sm font-bold text-orange-700">견적초안 저장에 실패했습니다.</div> : null}
        </section>
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-[minmax(280px,420px)_minmax(0,1fr)]">
        <Panel title="주요 스펙" subtitle="내부 자산 attributes 기준">
          {rows.length === 0 ? (
            <div className="rounded border border-dashed border-slate-300 p-5 text-sm text-slate-500">표시할 세부 스펙이 없습니다.</div>
          ) : (
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
              {rows.map((row) => (
                <div key={row.label} className="rounded border border-slate-200 bg-slate-50 p-3">
                  <div className="text-xs font-bold text-slate-500">{row.label}</div>
                  <div className="mt-1 text-sm font-bold text-slate-900">{row.value}</div>
                </div>
              ))}
            </div>
          )}
        </Panel>

        <Panel title="가격 변동 추이" subtitle="저장된 외부 가격 이력 기준">
          <PriceHistoryPanel
            history={priceHistory}
            currentPrice={part.price}
            supplierName={part.externalOffer?.supplierName}
            isLoading={isPriceHistoryLoading}
            isError={isPriceHistoryError}
          />
        </Panel>
      </div>
    </Screen>
  );
}

function formatDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' });
}

function maxDraftQuantity(category: string) {
  return category === 'RAM' || category === 'STORAGE' ? 9 : 1;
}

function PriceHistoryPanel({
  history,
  currentPrice,
  supplierName,
  isLoading,
  isError
}: {
  history?: PartPriceHistory;
  currentPrice: number;
  supplierName?: string | null;
  isLoading: boolean;
  isError: boolean;
}) {
  if (isLoading) {
    return <div className="rounded border border-dashed border-slate-300 p-5 text-sm text-slate-500">가격 이력을 불러오는 중입니다.</div>;
  }

  if (isError || !history) {
    return <div className="rounded border border-dashed border-slate-300 p-5 text-sm text-slate-500">가격 이력을 확인하지 못했습니다.</div>;
  }

  const points = history.items.length > 0 ? history.items : [{ price: currentPrice, source: history.source ?? 'STORED_CURRENT_PRICE', collectedAt: new Date().toISOString() }];
  const summary = history.summary;
  const changeTone = summary.changeAmount > 0 ? 'text-orange-700' : summary.changeAmount < 0 ? 'text-emerald-700' : 'text-slate-600';
  const sign = summary.changeAmount > 0 ? '+' : '';
  const sourceLabels = Array.from(new Set(points.map((point) => sourceLabel(point.source)))).join(' · ');

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-4">
        <PriceSummary label="현재가" value={`${history.currentPrice.toLocaleString()}원`} />
        <PriceSummary label="최저가" value={`${summary.minPrice.toLocaleString()}원`} />
        <PriceSummary label="최고가" value={`${summary.maxPrice.toLocaleString()}원`} />
        <PriceSummary label="기록 수" value={`${summary.sampleCount}회`} />
      </div>

      <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
        <PriceTrendChart points={points} currentPrice={history.currentPrice} minPrice={summary.minPrice} maxPrice={summary.maxPrice} />
        <div className="mt-3 flex flex-wrap items-center justify-between gap-2 text-xs">
          <span className="font-bold text-slate-500">{supplierName ?? '저장된 공급처 없음'} · {sourceLabels}</span>
          <span className={`font-black ${changeTone}`}>
            {sign}{summary.changeAmount.toLocaleString()}원 ({sign}{summary.changeRatePercent.toFixed(2)}%)
          </span>
        </div>
      </div>
    </div>
  );
}

function PriceSummary({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-slate-200 bg-white p-3">
      <div className="text-[11px] font-bold text-slate-500">{label}</div>
      <div className="mt-1 text-sm font-black text-slate-900">{value}</div>
    </div>
  );
}

type ChartPoint = PartPriceHistoryPoint & {
  id: string;
  label: string;
  tooltipLabel: string;
  isCurrent: boolean;
};

function PriceTrendChart({
  points,
  currentPrice,
  minPrice,
  maxPrice
}: {
  points: PartPriceHistoryPoint[];
  currentPrice: number;
  minPrice: number;
  maxPrice: number;
}) {
  const [activeIndex, setActiveIndex] = useState<number | null>(null);
  const width = 720;
  const height = 270;
  const paddingLeft = 78;
  const paddingRight = 28;
  const paddingTop = 34;
  const paddingBottom = 48;
  const chartPoints = normalizePriceTrendPoints(points, currentPrice);
  const prices = chartPoints.map((point) => point.price);
  const safeMin = Math.min(minPrice, ...prices);
  const safeMax = Math.max(maxPrice, ...prices);
  const range = Math.max(1, safeMax - safeMin);
  const axisTicks = [
    { label: '최고가', price: safeMax },
    { label: '중간값', price: Math.round((safeMax + safeMin) / 2) },
    { label: '최저가', price: safeMin }
  ];
  const coordinates = chartPoints.map((point, index) => {
    const x = chartPoints.length === 1 ? (paddingLeft + width - paddingRight) / 2 : paddingLeft + (index / (chartPoints.length - 1)) * (width - paddingLeft - paddingRight);
    const y = paddingTop + ((safeMax - point.price) / range) * (height - paddingTop - paddingBottom);
    return { ...point, x, y };
  });
  const path = coordinates.map((point) => `${point.x},${point.y}`).join(' ');
  const activePoint = activeIndex === null ? null : coordinates[activeIndex];
  const tooltipWidth = 190;
  const tooltipX = activePoint ? Math.min(Math.max(activePoint.x - tooltipWidth / 2, paddingLeft), width - tooltipWidth - paddingRight) : 0;
  const tooltipY = activePoint ? Math.max(10, activePoint.y - 78) : 0;

  return (
    <div>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="가격 변동 추이 그래프" className="h-56 w-full overflow-visible">
        {axisTicks.map((tick) => {
          const y = paddingTop + ((safeMax - tick.price) / range) * (height - paddingTop - paddingBottom);
          return (
            <g key={tick.label}>
              <line x1={paddingLeft} y1={y} x2={width - paddingRight} y2={y} stroke="#dbe3ef" strokeDasharray="4 4" strokeWidth="1" />
              <text x={paddingLeft - 10} y={y + 4} textAnchor="end" className="fill-slate-500 text-[11px] font-bold">
                {formatCompactPrice(tick.price)}
              </text>
            </g>
          );
        })}
        <line x1={paddingLeft} y1={paddingTop} x2={paddingLeft} y2={height - paddingBottom} stroke="#cbd5e1" strokeWidth="1" />
        <line x1={paddingLeft} y1={height - paddingBottom} x2={width - paddingRight} y2={height - paddingBottom} stroke="#cbd5e1" strokeWidth="1" />
        <polyline points={path} fill="none" stroke="#2563eb" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" />
        {coordinates.map((point, index) => (
          <g key={point.id}>
            <line x1={point.x} y1={height - paddingBottom} x2={point.x} y2={height - paddingBottom + 5} stroke="#94a3b8" strokeWidth="1" />
            <text x={point.x} y={height - 18} textAnchor="middle" className={`text-[11px] font-black ${point.isCurrent ? 'fill-red-600' : 'fill-slate-500'}`}>
              {point.label}
            </text>
            <circle
              data-testid={point.isCurrent ? 'price-trend-point-current' : 'price-trend-point'}
              aria-label={`${point.tooltipLabel} ${point.price.toLocaleString()}원`}
              tabIndex={0}
              cx={point.x}
              cy={point.y}
              r={activeIndex === index ? 7 : 5}
              fill="#ffffff"
              stroke={point.isCurrent ? '#dc2626' : '#2563eb'}
              strokeWidth={point.isCurrent ? 4 : 3}
              className="cursor-pointer outline-none"
              onMouseEnter={() => setActiveIndex(index)}
              onMouseLeave={() => setActiveIndex(null)}
              onFocus={() => setActiveIndex(index)}
              onBlur={() => setActiveIndex(null)}
            />
          </g>
        ))}
        {activePoint ? (
          <g data-testid="price-trend-tooltip" pointerEvents="none">
            <rect x={tooltipX} y={tooltipY} width={tooltipWidth} height="64" rx="8" fill="#0f172a" opacity="0.94" />
            <text x={tooltipX + 12} y={tooltipY + 20} className="fill-white text-[12px] font-black">
              {activePoint.tooltipLabel}
            </text>
            <text x={tooltipX + 12} y={tooltipY + 39} className="fill-white text-[13px] font-black">
              {activePoint.price.toLocaleString()}원
            </text>
            <text x={tooltipX + 12} y={tooltipY + 56} className="fill-slate-300 text-[11px] font-bold">
              {sourceLabel(activePoint.source)} · {formatDate(activePoint.collectedAt)}
            </text>
          </g>
        ) : null}
      </svg>
      <div className="mt-2 flex items-center justify-between text-[11px] font-bold text-slate-500">
        <span>월별 대표 최저가</span>
        <span>점에 마우스를 올리면 가격정보를 확인할 수 있습니다.</span>
      </div>
    </div>
  );
}

function normalizePriceTrendPoints(points: PartPriceHistoryPoint[], currentPrice: number): ChartPoint[] {
  const trendSource = points.some((point) => point.source === 'DANAWA_PRICE_TREND') ? 'DANAWA_PRICE_TREND' : null;
  const sourcePoints = (trendSource ? points.filter((point) => point.source === trendSource) : points)
    .filter((point) => point.price > 0)
    .sort((a, b) => new Date(a.collectedAt).getTime() - new Date(b.collectedAt).getTime());
  const monthMap = new Map<string, PartPriceHistoryPoint>();

  for (const point of sourcePoints) {
    const key = monthKey(point.collectedAt);
    const previous = monthMap.get(key);
    if (!previous || new Date(point.collectedAt).getTime() >= new Date(previous.collectedAt).getTime()) {
      monthMap.set(key, point);
    }
  }

  const monthlyPoints = Array.from(monthMap.values())
    .sort((a, b) => new Date(a.collectedAt).getTime() - new Date(b.collectedAt).getTime())
    .map((point) => ({
      ...point,
      id: `month-${monthKey(point.collectedAt)}`,
      label: formatMonthLabel(point.collectedAt),
      tooltipLabel: `${formatMonthLabel(point.collectedAt)} 월별가`,
      isCurrent: false
    }));

  return [
    ...monthlyPoints,
    {
      price: currentPrice,
      source: 'STORED_CURRENT_PRICE',
      collectedAt: new Date().toISOString(),
      id: 'current-price',
      label: '현재가',
      tooltipLabel: '현재가',
      isCurrent: true
    }
  ];
}

function monthKey(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value.slice(0, 7);
  }
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

function formatMonthLabel(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    const [year, month] = value.split('-');
    return year && month ? `${year.slice(-2)}.${month}` : value;
  }
  return `${String(date.getFullYear()).slice(-2)}.${String(date.getMonth() + 1).padStart(2, '0')}`;
}

function formatCompactPrice(value: number) {
  if (value >= 10000) {
    return `${Math.round(value / 10000).toLocaleString()}만`;
  }
  return value.toLocaleString();
}

function sourceLabel(source: string) {
  if (source === 'NAVER_SHOPPING_SEARCH') {
    return '네이버 쇼핑';
  }
  if (source === 'DANAWA_BACKUP') {
    return '다나와 백업';
  }
  if (source === 'DANAWA_PRICE_TREND') {
    return '다나와 추이';
  }
  if (source === 'MANUAL_CURRENT_LINEUP') {
    return '수동 기준가';
  }
  if (source === 'STORED_CURRENT_PRICE') {
    return '저장 현재가';
  }
  return source;
}
