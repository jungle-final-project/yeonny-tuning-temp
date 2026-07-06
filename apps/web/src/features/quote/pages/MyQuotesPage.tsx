import { FormEvent, type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BellRing, CheckCircle2, FileText, GitBranch, PencilLine, Save, ShoppingBag, Target, X } from 'lucide-react';
import { Panel, Screen, StateMessage } from '../../../components/ui';
import { applyAiBuildToQuoteDraft } from '../../parts/partsApi';
import { QuotePerformancePanel } from '../../parts/components/slot-board/QuotePerformancePanel';
import type { PartCategory } from '../aiSelection';
import { BuildDependencyGraph } from '../components/BuildDependencyGraph';
import { checkBuildPerformance, createQuotePriceAlert, getBuildHistory, getPriceAlerts, resolveBuildGraph, type PriceAlert } from '../quoteApi';
import type { BuildItem, BuildSummary } from '../types';

type SavedPartOption = {
  partId: string;
  label: string;
  category: string;
  buildName: string;
  price: number;
};
type SavedBuildApplyDestination = 'checkout' | 'self-quote';
type SavedBuildApplyVariables = {
  build: BuildSummary;
  destination: SavedBuildApplyDestination;
};

export function MyQuotesPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [selectedAlertBuildId, setSelectedAlertBuildId] = useState('');
  const [selectedSavedPartId, setSelectedSavedPartId] = useState('');
  const [graphBuild, setGraphBuild] = useState<BuildSummary | null>(null);
  const [targetPrice, setTargetPrice] = useState('850000');
  const alertFormRef = useRef<HTMLDivElement | null>(null);

  const buildsQuery = useQuery({ queryKey: ['build-history'], queryFn: getBuildHistory });
  const alertsQuery = useQuery({ queryKey: ['price-alerts'], queryFn: getPriceAlerts });

  const builds = buildsQuery.data?.items ?? [];
  const alerts = alertsQuery.data?.items ?? [];
  const selectedAlertBuild = useMemo(
    () => builds.find((build) => build.id === selectedAlertBuildId) ?? builds[0],
    [builds, selectedAlertBuildId]
  );
  const savedPartOptions = useMemo(
    () => selectedAlertBuild ? collectSavedPartOptions(selectedAlertBuild) : [],
    [selectedAlertBuild]
  );
  const selectedSavedPart = savedPartOptions.find((option) => option.partId === selectedSavedPartId);
  const selectedPartIdForSubmit = selectedSavedPartId;
  const targetPriceNumber = Number(targetPrice.replace(/,/g, ''));
  const achievedAlertCount = alerts.filter((alert) => isPriceTargetAchieved(alert)).length;
  const nearestAlert = useMemo(() => findNearestAlert(alerts), [alerts]);
  const graphBuildItems = useMemo(() => graphBuild ? quoteDraftItemsForBuild(graphBuild) : [], [graphBuild]);
  const graphQuery = useQuery({
    queryKey: ['build-graph', 'saved-build', graphBuild?.id, graphBuildSignature(graphBuildItems), graphBuild?.totalPrice],
    queryFn: () => resolveBuildGraph({
      source: 'AI_BUILD',
      items: graphBuildItems,
      budgetWon: graphBuild?.totalPrice
    }),
    enabled: Boolean(graphBuild && graphBuildItems.length > 0)
  });

  useEffect(() => {
    if (!selectedAlertBuildId && builds[0]) {
      setSelectedAlertBuildId(builds[0].id);
    }
  }, [builds, selectedAlertBuildId]);

  useEffect(() => {
    if (savedPartOptions.length === 0) {
      setSelectedSavedPartId('');
      return;
    }
    if (!savedPartOptions.some((option) => option.partId === selectedSavedPartId)) {
      setSelectedSavedPartId(savedPartOptions[0].partId);
    }
  }, [savedPartOptions, selectedSavedPartId]);

  const createAlertMutation = useMutation({
    mutationFn: () => createQuotePriceAlert(selectedPartIdForSubmit, targetPriceNumber),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['price-alerts'] })
  });
  const applyBuildMutation = useMutation({
    mutationFn: ({ build }: SavedBuildApplyVariables) => applyAiBuildToQuoteDraft({
      buildId: build.id,
      conflictPolicy: 'REPLACE',
      items: quoteDraftItemsForBuild(build)
    }),
    onSuccess: (draft, variables) => {
      queryClient.setQueryData(['quote-draft', 'current'], draft);
      void queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] });
      navigate(variables.destination === 'checkout' ? '/checkout' : '/self-quote');
    }
  });

  function submitAlert(event: FormEvent) {
    event.preventDefault();
    if (!selectedPartIdForSubmit || !targetPriceNumber) {
      return;
    }
    createAlertMutation.mutate();
  }

  function selectBuildPartForAlert(build: BuildSummary) {
    const firstPartId = resolvePartId(build.items?.[0]);
    if (!firstPartId) return;
    setSelectedAlertBuildId(build.id);
    setSelectedSavedPartId(firstPartId);
    alertFormRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  function openCheckoutForBuild(build: BuildSummary) {
    if (quoteDraftItemsForBuild(build).length === 0 || applyBuildMutation.isPending) return;
    applyBuildMutation.mutate({ build, destination: 'checkout' });
  }

  function openSelfQuoteForBuild(build: BuildSummary) {
    if (quoteDraftItemsForBuild(build).length === 0 || applyBuildMutation.isPending) return;
    applyBuildMutation.mutate({ build, destination: 'self-quote' });
  }

  return (
    <Screen>
      <div className="space-y-5">
        <section className="rounded-md border border-commerce-line bg-white p-5 shadow-product">
          <div className="flex flex-col gap-4 xl:flex-row xl:items-end xl:justify-between">
            <div>
              <p className="text-xs font-black tracking-wide text-brand-blue">견적 데스크</p>
              <h1 className="mt-1 text-2xl font-black tracking-tight text-commerce-ink">내 견적함 / 목표가 알림</h1>
              <p className="mt-2 max-w-3xl break-keep text-sm leading-6 text-slate-600">
                저장한 견적을 확인하고, 관심 부품의 현재가가 목표가에 가까워지는지 한 화면에서 추적합니다.
              </p>
            </div>
            <div className="grid gap-3 sm:grid-cols-3 xl:min-w-[520px]">
              <SummaryMetric testId="my-quotes-build-count" icon={<FileText size={17} />} label="저장 견적" value={`${builds.length}개`} />
              <SummaryMetric testId="my-quotes-alert-count" icon={<BellRing size={17} />} label="목표가 알림" value={`${alerts.length}개`} />
              <SummaryMetric testId="my-quotes-achieved-count" icon={<CheckCircle2 size={17} />} label="목표 달성" value={`${achievedAlertCount}개`} tone="success" />
            </div>
          </div>
        </section>

        <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_380px]">
          <Panel
            title="저장 견적"
            subtitle="상세 확인, 부품 변경, 목표가 알림 등록까지 바로 이어집니다."
            action={<Link to="/requirements/new" className="rounded-md bg-brand-blue px-3 py-2 text-xs font-black text-white hover:bg-blue-700">AI 견적 시작</Link>}
          >
            {buildsQuery.isLoading ? (
              <SavedBuildSkeleton />
            ) : buildsQuery.isError ? (
              <StateMessage type="warn" title="견적 조회 실패" body="저장된 견적을 불러오지 못했습니다. 잠시 후 다시 확인해 주세요." />
            ) : builds.length ? (
              <div className="space-y-3">
                {builds.map((build) => (
                  <SavedBuildCard
                    key={build.id}
                    build={build}
                    isPreparingCheckout={isApplyingBuild(applyBuildMutation.variables, build, 'checkout', applyBuildMutation.isPending)}
                    isPreparingSelfQuote={isApplyingBuild(applyBuildMutation.variables, build, 'self-quote', applyBuildMutation.isPending)}
                    onAlertSelect={selectBuildPartForAlert}
                    onCheckout={openCheckoutForBuild}
                    onEditParts={openSelfQuoteForBuild}
                    onOpenGraph={setGraphBuild}
                  />
                ))}
                {applyBuildMutation.isError ? (
                  <StateMessage type="warn" title="견적 적용 실패" body="저장 견적을 현재 장바구니에 적용하지 못했습니다. 잠시 후 다시 시도해 주세요." />
                ) : null}
              </div>
            ) : (
              <div className="space-y-3">
                <StateMessage type="info" title="저장된 견적 없음" body="AI 추천 또는 셀프 견적으로 조합을 만든 뒤 저장하면 이곳에서 다시 확인할 수 있습니다." />
                <div className="flex flex-wrap gap-2">
                  <Link to="/requirements/new" className="rounded-md bg-brand-blue px-4 py-2.5 text-sm font-bold text-white hover:bg-blue-700">AI 견적 시작</Link>
                  <Link to="/self-quote" className="rounded-md border border-slate-300 px-4 py-2.5 text-sm font-bold text-slate-700 hover:border-commerce-ink hover:text-commerce-ink">셀프 견적 시작</Link>
                </div>
              </div>
            )}
          </Panel>

          <div ref={alertFormRef} className="xl:sticky xl:top-5 xl:self-start">
            <Panel title="목표가 알림 등록" subtitle="선택한 저장 견적에 포함된 부품만 목표가 알림으로 등록할 수 있습니다.">
              <form onSubmit={submitAlert} className="space-y-4">
                <div>
                  {selectedAlertBuild ? (
                    <div className="mb-3 rounded-md border border-blue-100 bg-blue-50 px-3 py-2">
                      <div className="text-[11px] font-black text-brand-blue">선택한 저장 견적</div>
                      <div className="mt-1 truncate text-sm font-black text-commerce-ink" title={selectedAlertBuild.name}>{selectedAlertBuild.name}</div>
                    </div>
                  ) : null}
                  <label htmlFor="quote-alert-saved-part" className="mb-1 block text-xs font-black text-slate-600">저장 견적 부품</label>
                  <select
                    id="quote-alert-saved-part"
                    className="h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-sm font-bold text-commerce-ink focus:border-brand-blue focus:outline-none focus:ring-4 focus:ring-blue-100 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-400"
                    value={selectedSavedPartId}
                    onChange={(event) => setSelectedSavedPartId(event.target.value)}
                    disabled={savedPartOptions.length === 0}
                  >
                    {savedPartOptions.map((option) => (
                      <option key={option.partId} value={option.partId}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                  {selectedSavedPart ? (
                    <p className="mt-2 break-keep text-xs leading-5 text-slate-500">
                      {selectedSavedPart.buildName} · 현재 저장가 {selectedSavedPart.price.toLocaleString()}원
                    </p>
                  ) : (
                    <p className="mt-2 break-keep text-xs leading-5 text-slate-500">
                      목표가를 등록하려면 저장 견적 카드의 목표가 등록 버튼을 먼저 선택하세요.
                    </p>
                  )}
                </div>

                <div>
                  <label htmlFor="quote-alert-target-price" className="mb-1 block text-xs font-black text-slate-600">목표가</label>
                  <input
                    id="quote-alert-target-price"
                    className="h-11 w-full rounded-md border border-slate-300 px-3 text-sm font-bold text-commerce-ink focus:border-brand-blue focus:outline-none focus:ring-4 focus:ring-blue-100"
                    inputMode="numeric"
                    value={targetPrice}
                    onChange={(event) => setTargetPrice(event.target.value)}
                  />
                </div>

                <button
                  disabled={createAlertMutation.isPending || !selectedPartIdForSubmit || !targetPriceNumber}
                  className="flex w-full min-h-11 items-center justify-center rounded-md bg-brand-blue px-4 py-3 text-sm font-black text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-400"
                >
                  <Save className="mr-1.5 inline" size={15} /> {createAlertMutation.isPending ? '등록 중' : '알림 등록'}
                </button>

                {nearestAlert ? (
                  <div className="rounded-md border border-blue-100 bg-blue-50 px-3 py-2">
                    <div className="text-xs font-black text-slate-500">가장 가까운 목표</div>
                    <div className="mt-1 text-sm font-black text-commerce-ink">{nearestAlert.partName}</div>
                    <div className="mt-1 text-xs font-bold text-brand-blue">{priceAlertDeltaText(nearestAlert)}</div>
                  </div>
                ) : null}

                {createAlertMutation.isSuccess ? <StateMessage type="success" title="알림 등록 완료" body="목표가 알림 목록에 반영했습니다." /> : null}
                {createAlertMutation.isError ? <StateMessage type="warn" title="알림 등록 실패" body="이미 같은 목표가 알림이 있거나 부품 ID가 유효하지 않습니다." /> : null}
              </form>
            </Panel>
          </div>

          <Panel title="목표가 알림" subtitle="현재가가 목표가에 얼마나 가까운지 차액과 진행률로 확인합니다." className="xl:col-span-2">
            {alertsQuery.isLoading ? (
              <AlertSkeleton />
            ) : alertsQuery.isError ? (
              <StateMessage type="warn" title="알림 조회 실패" body="등록된 목표가 알림을 불러오지 못했습니다. 잠시 후 다시 확인해 주세요." />
            ) : alerts.length ? (
              <div className="grid gap-3 lg:grid-cols-2">
                {alerts.map((alert) => (
                  <PriceAlertRow key={`${alert.partId}-${alert.targetPrice}`} alert={alert} />
                ))}
              </div>
            ) : (
              <StateMessage type="info" title="등록된 알림 없음" body="저장 견적의 관심 부품을 선택하고 목표가를 등록해 보세요." />
            )}
          </Panel>
        </div>
      </div>
      {graphBuild ? (
        <SavedBuildGraphDialog
          build={graphBuild}
          graph={graphQuery.data}
          isLoading={graphQuery.isLoading}
          isError={graphQuery.isError}
          onClose={() => setGraphBuild(null)}
        />
      ) : null}
    </Screen>
  );
}

function SummaryMetric({
  icon,
  label,
  value,
  testId,
  tone = 'default'
}: {
  icon: ReactNode;
  label: string;
  value: string;
  testId: string;
  tone?: 'default' | 'success';
}) {
  return (
    <div data-testid={testId} className={`rounded-md border px-3 py-3 ${
      tone === 'success'
        ? 'border-emerald-100 bg-emerald-50'
        : 'border-slate-200 bg-slate-50'
    }`}
    >
      <div className="flex items-center gap-2 text-xs font-black text-slate-500">
        <span className={tone === 'success' ? 'text-emerald-600' : 'text-brand-blue'}>{icon}</span>
        {label}
      </div>
      <div className="mt-1 text-xl font-black text-commerce-ink">{value}</div>
    </div>
  );
}

function AlertStatusPill({ alert }: { alert: PriceAlert }) {
  const achieved = isPriceTargetAchieved(alert);
  return (
    <span className={`inline-flex rounded-full border px-2 py-1 text-[11px] font-black ${
      achieved
        ? 'border-emerald-200 bg-emerald-100 text-emerald-700'
        : 'border-blue-200 bg-blue-50 text-brand-blue'
    }`}
    >
      {achieved ? '목표 도달' : '추적 중'}
    </span>
  );
}

function SavedBuildCard({
  build,
  isPreparingCheckout,
  isPreparingSelfQuote,
  onAlertSelect,
  onCheckout,
  onEditParts,
  onOpenGraph
}: {
  build: BuildSummary;
  isPreparingCheckout: boolean;
  isPreparingSelfQuote: boolean;
  onAlertSelect: (build: BuildSummary) => void;
  onCheckout: (build: BuildSummary) => void;
  onEditParts: (build: BuildSummary) => void;
  onOpenGraph: (build: BuildSummary) => void;
}) {
  const mainItems = (build.items ?? []).slice(0, 4);
  const hasAlertablePart = Boolean(resolvePartId(build.items?.[0]));
  const hasCheckoutItems = quoteDraftItemsForBuild(build).length > 0;

  return (
    <article data-testid={`saved-build-card-${build.id}`} className="rounded-md border border-slate-200 bg-white p-4 transition hover:border-blue-200 hover:shadow-product">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="rounded bg-blue-50 px-2 py-1 text-[11px] font-black text-brand-blue">{build.recommendedFor ?? '저장 견적'}</span>
            <button
              type="button"
              disabled={!hasCheckoutItems}
              onClick={() => onOpenGraph(build)}
              className="inline-flex min-h-8 items-center gap-1.5 rounded-md border border-blue-100 bg-blue-50 px-2.5 text-[11px] font-black text-brand-blue hover:border-blue-200 hover:bg-white disabled:cursor-not-allowed disabled:border-slate-200 disabled:bg-slate-50 disabled:text-slate-400"
            >
              <GitBranch size={13} />
              견적 관계 그래프 보기
            </button>
          </div>
          <h2 className="mt-2 text-lg font-black leading-6 text-commerce-ink">{build.name}</h2>
          <p className="mt-1 line-clamp-2 break-keep text-sm leading-6 text-slate-600">
            {build.summary ?? '내부 자산과 저장된 현재가 기준으로 구성한 견적입니다.'}
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            {mainItems.map((item) => (
              <span key={`${build.id}-${item.category}-${resolvePartId(item) ?? item.name}`} className="rounded-md border border-slate-200 bg-slate-50 px-2 py-1 text-xs font-bold text-slate-600" title={item.name}>
                {labelForCategory(item.category)} · {item.name}
              </span>
            ))}
          </div>
          {/* 저장 견적 단위 성능 요약 — 저장한 견적들을 성능으로 한눈에 비교한다(공개 벤치마크 참고값). */}
          <SavedBuildPerformanceStrip build={build} />
        </div>
        <div className="shrink-0 lg:text-right">
          <div className="text-xs font-black text-slate-500">견적 합계</div>
          <div className="mt-1 text-2xl font-black text-brand-blue">{build.totalPrice.toLocaleString()}원</div>
          <div className="mt-1 text-xs font-semibold text-slate-500">{formatDateTime(build.createdAt)}</div>
        </div>
      </div>
      <div className="mt-4 flex flex-wrap gap-2 border-t border-slate-100 pt-3">
        <button
          type="button"
          disabled={!hasCheckoutItems || isPreparingCheckout}
          onClick={() => onCheckout(build)}
          className="inline-flex min-h-9 items-center gap-1.5 rounded-md bg-brand-blue px-3 text-xs font-black text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-400"
        >
          <ShoppingBag size={14} /> {isPreparingCheckout ? '구매 준비 중' : '구매하기'}
        </button>
        <Link to={`/builds/${build.id}`} className="inline-flex min-h-9 items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 text-xs font-black text-slate-700 hover:border-commerce-ink hover:text-commerce-ink">
          <FileText size={14} /> 견적 상세
        </Link>
        <button
          type="button"
          disabled={!hasCheckoutItems || isPreparingSelfQuote}
          onClick={() => onEditParts(build)}
          className="inline-flex min-h-9 items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 text-xs font-black text-slate-700 hover:border-commerce-ink hover:text-commerce-ink disabled:cursor-not-allowed disabled:border-slate-200 disabled:bg-slate-50 disabled:text-slate-400"
        >
          <PencilLine size={14} /> {isPreparingSelfQuote ? '이동 준비 중' : '부품 변경'}
        </button>
        <button
          type="button"
          disabled={!hasAlertablePart}
          onClick={() => onAlertSelect(build)}
          className="inline-flex min-h-9 items-center gap-1.5 rounded-md border border-blue-100 bg-blue-50 px-3 text-xs font-black text-brand-blue hover:border-blue-200 disabled:cursor-not-allowed disabled:border-slate-200 disabled:bg-slate-50 disabled:text-slate-400"
        >
          <Target size={14} /> 목표가 등록
        </button>
      </div>
    </article>
  );
}

// 저장 견적 카드의 경량 성능 요약: CPU/GPU 벤치마크 점수를 등급 막대로 — 저장 견적끼리 성능 비교.
// 그래프 resolve(무거움) 대신 performance 툴만 부르는 경량 엔드포인트를 재사용한다.
function SavedBuildPerformanceStrip({ build }: { build: BuildSummary }) {
  const partIds = useMemo(() => {
    return (build.items ?? [])
      .filter((item) => item.category === 'CPU' || item.category === 'GPU')
      .map((item) => resolvePartId(item))
      .filter(Boolean);
  }, [build.items]);

  const { data, isLoading } = useQuery({
    queryKey: ['saved-build-performance', build.id, partIds.join(',')],
    queryFn: () => checkBuildPerformance({ partIds }),
    enabled: partIds.length > 0,
    staleTime: 5 * 60 * 1000
  });

  if (partIds.length === 0) {
    return null;
  }
  const details = (data?.details ?? {}) as { cpuBenchmarkScore?: number; gpuBenchmarkScore?: number };
  const cpu = toPerfScore(details.cpuBenchmarkScore);
  const gpu = toPerfScore(details.gpuBenchmarkScore);

  return (
    <div data-testid={`saved-build-performance-${build.id}`} className="mt-3 rounded-md border border-slate-200 bg-slate-50/70 px-3 py-2.5">
      <div className="mb-1.5 text-[11px] font-black text-slate-500">성능 요약 <span className="font-bold text-slate-400">(공개 벤치마크 참고)</span></div>
      {isLoading ? (
        <div className="h-7 animate-pulse rounded bg-slate-200" />
      ) : (
        <div className="grid grid-cols-2 gap-2.5">
          <PerfMiniBar label="CPU" score={cpu} />
          <PerfMiniBar label="GPU" score={gpu} />
        </div>
      )}
    </div>
  );
}

function PerfMiniBar({ label, score }: { label: string; score: number | null }) {
  const tone = score === null ? 'bg-slate-300' : score >= 80 ? 'bg-emerald-500' : score >= 55 ? 'bg-brand-blue' : 'bg-amber-500';
  return (
    <div>
      <div className="flex items-baseline justify-between gap-1">
        <span className="text-[10px] font-black text-slate-600">{label}</span>
        <span className="text-[10px] font-bold text-slate-500">{score === null ? '자료 없음' : `${perfTier(score)} · ${Math.round(score)}`}</span>
      </div>
      <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-slate-200">
        <div className={`h-full rounded-full ${tone}`} style={{ width: `${score === null ? 0 : Math.min(100, Math.max(4, score))}%` }} />
      </div>
    </div>
  );
}

function toPerfScore(value: unknown): number | null {
  const num = Number(value);
  return Number.isFinite(num) && num > 0 ? num : null;
}

function perfTier(score: number): string {
  if (score >= 85) return '최상위급';
  if (score >= 70) return '상위급';
  if (score >= 55) return '중상위급';
  if (score >= 40) return '중급';
  return '입문급';
}

function PriceAlertRow({ alert }: { alert: PriceAlert }) {
  const achieved = isPriceTargetAchieved(alert);
  const progress = alert.currentPrice > 0
    ? Math.min(100, Math.max(8, (alert.targetPrice / alert.currentPrice) * 100))
    : 0;

  return (
    <article data-testid={`price-alert-row-${alert.partId}`} className={`rounded-md border p-4 ${
      achieved
        ? 'border-emerald-200 bg-emerald-50'
        : 'border-slate-200 bg-white'
    }`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <AlertStatusPill alert={alert} />
            <span className={`rounded px-2 py-1 text-[11px] font-black ${
              achieved ? 'bg-emerald-100 text-emerald-700' : 'bg-blue-50 text-brand-blue'
            }`}
            >
              {priceAlertDeltaText(alert)}
            </span>
          </div>
          <h3 className="mt-2 truncate text-base font-black text-commerce-ink" title={alert.partName}>{alert.partName}</h3>
          <p className="mt-1 text-xs font-semibold text-slate-500">등록일 {formatDateTime(alert.createdAt)}</p>
        </div>
        <div className="shrink-0 text-right">
          <div className="text-xs font-black text-slate-500">현재가</div>
          <div className="mt-1 text-lg font-black text-brand-blue">{alert.currentPrice.toLocaleString()}원</div>
        </div>
      </div>
      <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
        <div className="rounded-md bg-white/70 px-3 py-2">
          <div className="text-xs font-black text-slate-500">목표가</div>
          <div className="mt-1 font-black text-commerce-ink">{alert.targetPrice.toLocaleString()}원</div>
        </div>
        <div className="rounded-md bg-white/70 px-3 py-2">
          <div className="text-xs font-black text-slate-500">차액</div>
          <div className={`mt-1 font-black ${achieved ? 'text-emerald-700' : 'text-commerce-ink'}`}>{formatPriceDelta(alert)}</div>
        </div>
      </div>
      <div className="mt-4 h-2 overflow-hidden rounded-full bg-slate-100">
        <div className={`h-full rounded-full ${achieved ? 'bg-emerald-500' : 'bg-brand-blue'}`} style={{ width: `${progress}%` }} />
      </div>
    </article>
  );
}

function SavedBuildGraphDialog({
  build,
  graph,
  isLoading,
  isError,
  onClose
}: {
  build: BuildSummary;
  graph: Awaited<ReturnType<typeof resolveBuildGraph>> | undefined;
  isLoading: boolean;
  isError: boolean;
  onClose: () => void;
}) {
  return (
    <div className="fixed inset-0 z-[90] bg-slate-950/40 p-3 sm:p-6" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) {
        onClose();
      }
    }}>
      <section
        role="dialog"
        aria-modal="true"
        aria-label="저장 견적 관계 그래프"
        className="mx-auto flex h-full max-h-[920px] w-full max-w-6xl flex-col overflow-hidden rounded-lg bg-white shadow-2xl"
      >
        <header className="flex items-start justify-between gap-4 border-b border-commerce-line px-4 py-3 sm:px-5">
          <div className="min-w-0">
            <div className="text-xs font-black text-brand-blue">읽기 전용</div>
            <h2 className="mt-1 truncate text-lg font-black text-commerce-ink" title={build.name}>{build.name}</h2>
            <p className="mt-1 text-xs font-semibold text-slate-500">저장 견적의 부품 관계를 확인합니다. 이 팝업에서는 부품 교체나 담기 동작을 하지 않습니다.</p>
          </div>
          <button
            type="button"
            aria-label="관계 그래프 닫기"
            onClick={onClose}
            className="grid h-9 w-9 shrink-0 place-items-center rounded-md border border-commerce-line bg-white text-slate-500 hover:border-slate-300 hover:text-commerce-ink focus:outline-none focus:ring-4 focus:ring-blue-100"
          >
            <X size={18} aria-hidden="true" />
          </button>
        </header>
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-3 sm:p-5">
          <BuildDependencyGraph
            graph={graph}
            isLoading={isLoading}
            isError={isError}
            totalPrice={build.totalPrice}
            title="견적 관계 그래프"
            subtitle="저장 견적에 포함된 부품만 기준으로 계산한 읽기 전용 관계도입니다."
          />
          {/* 저장 견적 단위 성능 상세 — resolve 응답에 이미 담긴 performance 결과 + 게임별 FPS 참고. */}
          <QuotePerformancePanel graph={graph} items={perfItemsForBuild(build)} />
        </div>
      </section>
    </div>
  );
}

function SavedBuildSkeleton() {
  return (
    <div className="space-y-3">
      {[0, 1].map((index) => (
        <div key={index} className="h-36 animate-pulse rounded-md border border-slate-200 bg-slate-50" />
      ))}
    </div>
  );
}

function AlertSkeleton() {
  return (
    <div className="grid gap-3 lg:grid-cols-2">
      {[0, 1].map((index) => (
        <div key={index} className="h-40 animate-pulse rounded-md border border-slate-200 bg-slate-50" />
      ))}
    </div>
  );
}

function collectSavedPartOptions(build: BuildSummary): SavedPartOption[] {
  const seen = new Set<string>();
  const options: SavedPartOption[] = [];

  for (const item of build.items ?? []) {
    const partId = resolvePartId(item);
    if (!partId || seen.has(partId)) continue;
    seen.add(partId);
    options.push({
      partId,
      label: `${labelForCategory(item.category)} · ${item.name}`,
      category: item.category,
      buildName: build.name,
      price: item.price
    });
  }

  return options;
}

// 성능 패널이 쓰는 최소 형태(category, partId)로 저장 견적 부품을 정규화한다.
function perfItemsForBuild(build: BuildSummary) {
  return (build.items ?? [])
    .map((item) => ({ category: item.category, partId: resolvePartId(item) }))
    .filter((item) => Boolean(item.partId));
}

function quoteDraftItemsForBuild(build: BuildSummary) {
  return (build.items ?? [])
    .map((item) => {
      const partId = resolvePartId(item);
      if (!partId || !isPartCategory(item.category)) return null;
      return {
        partId,
        category: item.category,
        quantity: quantityForBuildItem(item)
      };
    })
    .filter((item): item is { partId: string; category: PartCategory; quantity: number } => Boolean(item));
}

function graphBuildSignature(items: Array<{ partId: string; category: PartCategory; quantity: number }>) {
  return items
    .map((item) => `${item.category}:${item.partId}:${item.quantity}`)
    .sort()
    .join('|');
}

function isApplyingBuild(
  variables: SavedBuildApplyVariables | undefined,
  build: BuildSummary,
  destination: SavedBuildApplyDestination,
  isPending: boolean
) {
  return Boolean(isPending && variables?.build.id === build.id && variables.destination === destination);
}

function resolvePartId(item?: BuildItem) {
  return item?.partId ?? item?.id ?? '';
}

function quantityForBuildItem(item: BuildItem) {
  const quantity = (item as BuildItem & { quantity?: number }).quantity;
  return typeof quantity === 'number' && quantity > 0 ? quantity : 1;
}

function isPartCategory(category: string): category is PartCategory {
  return ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER'].includes(category);
}

function isPriceTargetAchieved(alert: PriceAlert) {
  return alert.currentPrice <= alert.targetPrice || alert.status.toUpperCase() === 'TRIGGERED';
}

function findNearestAlert(alerts: PriceAlert[]) {
  return [...alerts]
    .filter((alert) => !isPriceTargetAchieved(alert))
    .sort((a, b) => Math.abs(a.currentPrice - a.targetPrice) - Math.abs(b.currentPrice - b.targetPrice))[0]
    ?? alerts.find(isPriceTargetAchieved);
}

function priceAlertDeltaText(alert: PriceAlert) {
  if (isPriceTargetAchieved(alert)) {
    return '목표 달성';
  }
  return `목표까지 ${(alert.currentPrice - alert.targetPrice).toLocaleString()}원`;
}

function formatPriceDelta(alert: PriceAlert) {
  const delta = alert.currentPrice - alert.targetPrice;
  if (delta === 0) {
    return '0원';
  }
  if (delta < 0) {
    return `${Math.abs(delta).toLocaleString()}원 낮음`;
  }
  return `${delta.toLocaleString()}원 높음`;
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

function formatDateTime(value?: string) {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value.replace('T', ' ').slice(0, 19);
  }
  return date.toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' });
}
