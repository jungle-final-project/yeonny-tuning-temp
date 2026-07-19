import { useQuery } from '@tanstack/react-query';
import { TrendingUp } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { AdminShell, DataTable, MetricCard, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { listAdminAssemblyRequests } from '../../parts/assemblyApi';
import { getAdminDashboard } from '../adminApi';

function countLabel(value: number | null | undefined) {
  return `${value ?? 0}건`;
}

function wonLabel(value: number | null | undefined) {
  return `₩${(value ?? 0).toLocaleString()}`;
}

function compactWonLabel(value: number | null | undefined) {
  const amount = value ?? 0;
  if (amount >= 100_000_000) {
    return `₩${Math.round(amount / 10_000_000) / 10}억`;
  }
  if (amount >= 10_000) {
    return `₩${Math.round(amount / 1_000) / 10}만`;
  }
  return wonLabel(amount);
}

function dateLabel(value: string | null | undefined) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date);
}

function orderStatusCount(items: Array<{ status: string; count: number }> | undefined, status: string) {
  return items?.find((item) => item.status === status)?.count ?? 0;
}

const ORDER_STATUS_COLORS: Record<string, string> = {
  PENDING: '#de6c2d',
  IN_PROGRESS: '#2563eb',
  COMPLETED: '#16a34a',
  CANCELLED: '#ef3f3f'
};

function useViewportAnimation<T extends Element>() {
  const elementRef = useRef<T>(null);
  const [isVisible, setIsVisible] = useState(false);

  useEffect(() => {
    const element = elementRef.current;
    if (!element) return;
    if (!('IntersectionObserver' in window)) {
      setIsVisible(true);
      return;
    }

    const observer = new IntersectionObserver(([entry]) => {
      if (entry?.isIntersecting) {
        setIsVisible(true);
        observer.disconnect();
      }
    }, { threshold: 0.2 });

    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return { elementRef, isVisible };
}

export function AdminDashboardPage() {
  const { data: dashboard, isError, isLoading } = useQuery({
    queryKey: ['admin-dashboard'],
    queryFn: getAdminDashboard
  });
  const recentAssemblyRequestsQuery = useQuery({
    queryKey: ['admin-assembly-requests', 'dashboard-recent'],
    queryFn: () => listAdminAssemblyRequests({ page: 0, size: 5 }),
    enabled: Boolean(dashboard),
    retry: false
  });
  if (isLoading) {
    return (
      <AdminShell title="운영 대시보드">
        <StateMessage type="info" title="대시보드 로딩 중" body="운영 지표를 불러오고 있습니다." />
      </AdminShell>
    );
  }

  if (isError || !dashboard) {
    return (
      <AdminShell title="운영 대시보드">
        <StateMessage type="warn" title="대시보드 조회 실패" body="관리자 대시보드 API 응답을 불러오지 못했습니다." />
      </AdminShell>
    );
  }

  const generatedAt = dashboard.generatedAt ?? '갱신 시간 없음';
  const dashboardExportRows = [
    { metric: 'agentRunning', value: dashboard.agentRunning, generatedAt },
    { metric: 'openTickets', value: dashboard.openTickets, generatedAt },
    { metric: 'todayRevenue', value: dashboard.todayRevenue, generatedAt },
    { metric: 'weekRevenue', value: dashboard.weekRevenue, generatedAt },
    { metric: 'previousWeekRevenue', value: dashboard.previousWeekRevenue, generatedAt },
    { metric: 'degraded', value: dashboard.degraded, generatedAt },
    ...(dashboard.revenueTrend ?? []).map((item) => ({
      metric: `revenue:${item.date}`,
      value: item.revenue,
      generatedAt
    })),
    ...(dashboard.orderStatus ?? []).map((item) => ({
      metric: `order:${item.status}`,
      value: item.count,
      generatedAt
    })),
    ...(dashboard.asStatus ?? []).map((item) => ({
      metric: `as:${item.status}`,
      value: item.count,
      generatedAt
    }))
  ];
  const pendingOrderCount = orderStatusCount(dashboard.orderStatus, 'PENDING');
  const inProgressOrderCount = orderStatusCount(dashboard.orderStatus, 'IN_PROGRESS');
  const quickActions = [
    {
      title: '조립 요청 관리',
      description: '요청 상태와 기사 제안을 확인합니다.',
      to: '/admin/assembly',
      meta: `처리 대기 ${pendingOrderCount.toLocaleString()}건`
    },
    {
      title: '기사/제안 운영',
      description: '기사 승인, 제안 추가와 상태 보정을 진행합니다.',
      to: '/admin/assembly',
      meta: `진행 중 ${inProgressOrderCount.toLocaleString()}건`
    },
    {
      title: '부품/가격 관리',
      description: '부품 데이터와 가격 수집 상태를 점검합니다.',
      to: '/admin/parts',
      meta: dashboard.priceJobsRunning > 0 ? '가격 작업 실행 중' : '가격 작업 대기 없음'
    },
    {
      title: 'AS 티켓 확인',
      description: '미해결 티켓과 사용자 문의를 확인합니다.',
      to: '/admin/as-tickets',
      meta: `미해결 ${dashboard.openTickets.toLocaleString()}건`
    }
  ];
  const recentAssemblyRows = (recentAssemblyRequestsQuery.data?.items ?? []).map((item) => ({
    요청번호: <Link className="font-black text-commerce-ink hover:text-[#de6c2d]" to="/admin/assembly">{item.requestNo}</Link>,
    상태: <StatusBadge status={item.status} />,
    '지역/일정': `${item.region} · ${item.preferredDate}`,
    금액: wonLabel(item.finalPrice ?? item.estimatedPartsPrice),
    생성: dateLabel(item.createdAt),
    이동: <Link className="font-bold text-[#de6c2d] hover:text-[#c45c22]" to="/admin/assembly">관리</Link>
  }));

  return (
    <AdminShell title="운영 대시보드" exportRows={dashboardExportRows} exportFileName="admin-dashboard.csv">
      {dashboard.degraded ? (
        <div className="mb-4">
          <StateMessage
            type="warn"
            title="운영 상태 주의"
            body={`일부 운영 지표가 주의 상태입니다. 마지막 갱신: ${generatedAt}`}
          />
        </div>
      ) : null}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <MetricCard label="진행 중 Agent" value={countLabel(dashboard.agentRunning)} tone="orange" />
        <MetricCard label="미해결 AS" value={countLabel(dashboard.openTickets)} tone="orange" />
        <RevenueMetricCard
          label="오늘 매출"
          value={dashboard.todayRevenue}
          comparisonValue={dashboard.revenueTrend?.[dashboard.revenueTrend.length - 2]?.revenue ?? 0}
          comparisonLabel="vs 어제"
        />
        <RevenueMetricCard
          label="이번 주 매출"
          value={dashboard.weekRevenue}
          comparisonValue={dashboard.previousWeekRevenue}
          comparisonLabel="vs 지난 주"
        />
      </div>
      <div className="mt-5 grid grid-cols-1 gap-5 md:grid-cols-2 min-[1100px]:grid-cols-3">
        <Panel title="매출 추이" subtitle="최근 7일 결제 완료 금액">
          <RevenueTrendChart items={dashboard.revenueTrend ?? []} />
        </Panel>
        <Panel title="주문 현황" subtitle="조립 요청 상태 기준">
          <StatusDonutChart items={dashboard.orderStatus ?? []} totalLabel="전체 주문" />
        </Panel>
        <Panel title="AS 현황" subtitle="AS 티켓 처리 상태 기준">
          <StatusDonutChart items={dashboard.asStatus ?? []} totalLabel="전체 AS" />
        </Panel>
      </div>
      <div className="mt-5 grid grid-cols-1 gap-5 xl:grid-cols-[600px_minmax(0,1fr)]">
        <Panel title="빠른 작업" subtitle="자주 확인하는 운영 화면으로 바로 이동">
          <div className="divide-y divide-commerce-line rounded-md border border-commerce-line bg-white">
            {quickActions.map((action) => (
              <Link
                key={action.title}
                to={action.to}
                className="group block px-4 py-3 transition duration-150 ease-out hover:bg-[#fff7f2] focus:outline-none focus:ring-2 focus:ring-[#de6c2d]/30"
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="text-sm font-black text-commerce-ink group-hover:text-[#de6c2d]">{action.title}</div>
                    <p className="mt-1 text-xs leading-5 text-slate-500">{action.description}</p>
                  </div>
                  <span className="shrink-0 rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-black text-slate-600 group-hover:bg-[#fde7d9] group-hover:text-[#9f4218]">
                    {action.meta}
                  </span>
                </div>
              </Link>
            ))}
          </div>
        </Panel>
        <Panel
          title="최근 조립 요청"
          subtitle="최근 접수된 조립 중개 요청"
          action={<Link className="text-xs font-black text-[#de6c2d] hover:text-[#c45c22]" to="/admin/assembly">전체 보기</Link>}
        >
          {recentAssemblyRequestsQuery.isLoading ? (
            <StateMessage type="info" title="조립 요청 로딩 중" body="최근 조립 요청을 불러오고 있습니다." />
          ) : recentAssemblyRequestsQuery.isError ? (
            <StateMessage type="warn" title="조립 요청 조회 실패" body="최근 조립 요청을 불러오지 못했습니다." />
          ) : recentAssemblyRows.length > 0 ? (
            <DataTable columns={['요청번호', '상태', '지역/일정', '금액', '생성', '이동']} rows={recentAssemblyRows} />
          ) : (
            <StateMessage type="info" title="조립 요청 없음" body="표시할 최근 조립 요청이 없습니다." />
          )}
        </Panel>
      </div>
    </AdminShell>
  );
}

function RevenueMetricCard({
  label,
  value,
  comparisonValue,
  comparisonLabel
}: {
  label: string;
  value: number;
  comparisonValue: number;
  comparisonLabel: string;
}) {
  const changePercent = comparisonValue > 0
    ? Math.round(((value - comparisonValue) / comparisonValue) * 100)
    : value > 0 ? 100 : 0;
  const changeLabel = `${changePercent >= 0 ? '+' : ''}${changePercent}% ${comparisonLabel}`;

  return (
    <div className="rounded-md border border-commerce-line bg-white p-4 shadow-sm">
      <div className="text-xs font-bold text-slate-500">{label}</div>
      <div className="mt-2 min-w-0 break-words text-2xl font-black tracking-tight text-commerce-ink">{wonLabel(value)}</div>
      <div className="mt-2 flex items-center gap-1.5 text-[11px] font-semibold text-slate-400">
        <span>{changeLabel}</span>
        <TrendingUp aria-hidden="true" className="h-4 w-4 shrink-0 text-violet-500 motion-safe:animate-pulse" strokeWidth={2.5} />
      </div>
    </div>
  );
}

function RevenueTrendChart({ items }: { items: Array<{ date: string; label: string; revenue: number }> }) {
  const maxRevenue = Math.max(...items.map((item) => item.revenue), 0);
  const { elementRef, isVisible } = useViewportAnimation<HTMLDivElement>();
  if (items.length === 0) {
    return <StateMessage type="info" title="매출 데이터 없음" body="결제 완료 이력이 쌓이면 최근 7일 매출 추이가 표시됩니다." />;
  }

  const axisValues = [maxRevenue, Math.round(maxRevenue * 0.67), Math.round(maxRevenue * 0.33), 0];

  return (
    <div ref={elementRef} className="rounded-md border border-slate-100 bg-slate-50/60 px-2 py-4">
      <div className="grid grid-cols-[52px_minmax(0,1fr)] gap-2">
        <div className="flex h-48 flex-col justify-between text-right text-[10px] font-bold text-slate-500">
          {axisValues.map((value, index) => <span key={`${value}-${index}`}>{compactWonLabel(value)}</span>)}
        </div>
        <div className="min-w-0">
          <div className="relative h-48 border-b border-l border-slate-300">
            {[0, 33, 67].map((position) => (
              <span
                key={position}
                aria-hidden="true"
                className="absolute left-0 right-0 border-t border-dashed border-slate-200"
                style={{ top: `${position}%` }}
              />
            ))}
            <div className="absolute inset-0 grid grid-cols-7 items-end gap-1 px-1.5">
              {items.map((item, index) => {
                const height = maxRevenue > 0 ? Math.max((item.revenue / maxRevenue) * 100, item.revenue > 0 ? 5 : 1) : 1;
                return (
                  <div key={item.date} className="group relative flex h-full min-w-0 items-end justify-center">
                    <div
                      className="w-full max-w-8 rounded-t bg-[#de6c2d] shadow-sm transition-[height,background-color] duration-700 ease-out motion-reduce:transition-none group-hover:bg-[#c45c22]"
                      style={{ height: isVisible ? `${height}%` : '0%', transitionDelay: isVisible ? `${index * 70}ms` : '0ms' }}
                      aria-label={`${item.label} 매출 ${wonLabel(item.revenue)}`}
                      title={`${item.label} · ${wonLabel(item.revenue)}`}
                    />
                    <span className="pointer-events-none absolute top-2 z-10 hidden -translate-y-full whitespace-nowrap rounded bg-slate-900 px-2 py-1 text-[10px] font-bold text-white shadow-sm group-hover:block">
                      {compactWonLabel(item.revenue)}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
          <div className="mt-2 grid grid-cols-7 gap-0">
            {items.map((item) => (
              <div key={item.date} className="whitespace-nowrap text-center text-[8px] font-bold leading-none text-slate-500">{item.label}</div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

type StatusChartItem = { status: string; label: string; count: number };

function polarPoint(cx: number, cy: number, radius: number, angle: number) {
  const radians = (angle * Math.PI) / 180;
  return { x: cx + radius * Math.cos(radians), y: cy + radius * Math.sin(radians) };
}

function donutSegmentPath(cx: number, cy: number, outerRadius: number, innerRadius: number, startAngle: number, endAngle: number) {
  const outerStart = polarPoint(cx, cy, outerRadius, startAngle);
  const outerEnd = polarPoint(cx, cy, outerRadius, endAngle);
  const innerEnd = polarPoint(cx, cy, innerRadius, endAngle);
  const innerStart = polarPoint(cx, cy, innerRadius, startAngle);
  const largeArc = endAngle - startAngle > 180 ? 1 : 0;
  return [
    `M ${outerStart.x} ${outerStart.y}`,
    `A ${outerRadius} ${outerRadius} 0 ${largeArc} 1 ${outerEnd.x} ${outerEnd.y}`,
    `L ${innerEnd.x} ${innerEnd.y}`,
    `A ${innerRadius} ${innerRadius} 0 ${largeArc} 0 ${innerStart.x} ${innerStart.y}`,
    'Z'
  ].join(' ');
}

function StatusDonutChart({ items, totalLabel }: { items: StatusChartItem[]; totalLabel: string }) {
  const total = items.reduce((sum, item) => sum + item.count, 0);
  const largestCount = Math.max(...items.map((item) => item.count), 0);
  const { elementRef, isVisible } = useViewportAnimation<HTMLDivElement>();
  let angle = -90;
  const segments = items
    .filter((item) => item.count > 0)
    .map((item) => {
      const portion = item.count / total;
      const startAngle = angle;
      const endAngle = angle + Math.min(portion * 360, 359.999);
      angle += portion * 360;
      return { ...item, portion, startAngle, endAngle, midAngle: (startAngle + endAngle) / 2 };
    });

  return (
    <div ref={elementRef} className="mx-auto w-full max-w-[320px]">
      <svg viewBox="0 0 240 220" className="h-auto w-full overflow-visible" role="img" aria-label={`${totalLabel} 총 ${total}건`}>
        <circle
          cx="120"
          cy="104"
          r="61"
          fill="none"
          stroke="#e4e7ec"
          strokeWidth="30"
          className="transition-opacity duration-500 motion-reduce:transition-none"
          style={{ opacity: isVisible ? 1 : 0 }}
        />
        {segments.map((segment, index) => {
          const color = ORDER_STATUS_COLORS[segment.status] ?? '#64748b';
          const isLargest = segment.count === largestCount;
          const valuePoint = polarPoint(120, 104, 61, segment.midAngle);
          const lineStart = polarPoint(120, 104, 78, segment.midAngle);
          const lineElbow = polarPoint(120, 104, 91, segment.midAngle);
          const isRight = Math.cos((segment.midAngle * Math.PI) / 180) >= 0;
          const labelY = Math.max(18, Math.min(196, lineElbow.y));
          const lineEndX = isRight ? 213 : 27;
          const labelX = isRight ? 218 : 22;
          const percentage = Math.round(segment.portion * 100);
          return (
            <g
              key={segment.status}
              className="transition-[opacity,transform] duration-700 ease-out motion-reduce:transition-none"
              style={{
                opacity: isVisible ? 1 : 0,
                transform: `scale(${isVisible ? 1 : 0.86})`,
                transformOrigin: '120px 104px',
                transitionDelay: isVisible ? `${index * 90}ms` : '0ms'
              }}
            >
              <path
                d={donutSegmentPath(120, 104, 76, 46, segment.startAngle, segment.endAngle)}
                fill={color}
                className="transition-transform duration-200 ease-out hover:scale-[1.02]"
                style={{ filter: isLargest ? 'drop-shadow(0 4px 5px rgb(15 23 42 / 0.22))' : undefined, transformOrigin: '120px 104px' }}
              />
              <text x={valuePoint.x} y={valuePoint.y + 3} textAnchor="middle" className="fill-white text-[9px] font-black">
                {segment.count}건
              </text>
              <polyline
                points={`${lineStart.x},${lineStart.y} ${lineElbow.x},${labelY} ${lineEndX},${labelY}`}
                fill="none"
                stroke={color}
                strokeWidth="1.5"
                strokeLinecap="round"
                strokeLinejoin="round"
                className="transition-[stroke-dashoffset] duration-700 ease-out motion-reduce:transition-none"
                style={{
                  strokeDasharray: 190,
                  strokeDashoffset: isVisible ? 0 : 190,
                  transitionDelay: isVisible ? `${250 + index * 90}ms` : '0ms'
                }}
              />
              <text x={labelX} y={labelY - 3} textAnchor={isRight ? 'start' : 'end'} fill={color} className="text-[9px] font-black">
                {segment.label}
                <tspan x={labelX} dy="12" className="font-bold">{segment.count}건 · {percentage}%</tspan>
              </text>
            </g>
          );
        })}
        <text x="120" y="101" textAnchor="middle" className="fill-commerce-ink text-[17px] font-black">{countLabel(total)}</text>
        <text x="120" y="118" textAnchor="middle" className="fill-slate-500 text-[9px] font-bold">{totalLabel}</text>
      </svg>
      {total === 0 ? <p className="-mt-3 text-center text-xs font-bold text-slate-500">표시할 상태 데이터가 없습니다.</p> : null}
    </div>
  );
}
