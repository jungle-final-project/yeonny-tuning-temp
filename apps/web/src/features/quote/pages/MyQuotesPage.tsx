import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowDown, ArrowUp, Check, ClipboardList, Copy, FileText, GitBranch, Pencil, PencilLine, Save, ShoppingBag, Target, Trash2, X } from 'lucide-react';
import { Panel, Screen, StateMessage } from '../../../components/ui';
import { applyAiBuildToQuoteDraft } from '../../parts/partsApi';
import { listAssemblyRequests } from '../../parts/assemblyApi';
import { QuotePerformancePanel } from '../../parts/components/slot-board/QuotePerformancePanel';
import type { BuildCompositeScore, PartCategory } from '../aiSelection';
import { BuildDependencyGraph } from '../components/BuildDependencyGraph';
import { createQuotePriceAlert, deleteBuild, getBuildHistory, getPriceAlerts, renameBuild, resolveBuildGraph, type PriceAlert } from '../quoteApi';
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
  const [alertInputError, setAlertInputError] = useState('');
  const alertFormRef = useRef<HTMLDivElement | null>(null);

  const buildsQuery = useQuery({ queryKey: ['build-history'], queryFn: getBuildHistory });
  const alertsQuery = useQuery({ queryKey: ['price-alerts'], queryFn: getPriceAlerts });
  const assemblyRequestsQuery = useQuery({
    queryKey: ['assembly-requests'],
    queryFn: () => listAssemblyRequests(),
    refetchInterval: (query) => {
      const items = (query.state.data as Awaited<ReturnType<typeof listAssemblyRequests>> | undefined)?.items ?? [];
      return items.some((item) => item.status === 'REQUESTED' || item.status === 'OFFERED') ? 5000 : 30_000;
    }
  });
  const builds = useMemo(
    () => uniqueBuildsByPartCombination(buildsQuery.data?.items ?? []),
    [buildsQuery.data?.items]
  );
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
  const offeredAssemblyRequest = assemblyRequestsQuery.data?.items.find(
    (request) => request.status === 'OFFERED' && (request.availableOfferCount ?? 1) > 0
  );
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
  const renameBuildMutation = useMutation({
    mutationFn: ({ buildId, name }: { buildId: string; name: string }) => renameBuild(buildId, name),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['build-history'] })
  });
  const deleteBuildMutation = useMutation({
    mutationFn: (buildId: string) => deleteBuild(buildId),
    onSuccess: (_result, buildId) => {
      if (selectedAlertBuildId === buildId) {
        setSelectedAlertBuildId('');
      }
      void queryClient.invalidateQueries({ queryKey: ['build-history'] });
    }
  });

  function submitAlert(event: FormEvent) {
    event.preventDefault();
    // 음수·0이 서버까지 가면 DB 제약 위반(500)에 엉뚱한 실패 사유가 표시된다 — 여기서 차단.
    if (!selectedPartIdForSubmit || !Number.isFinite(targetPriceNumber) || targetPriceNumber < 1) {
      setAlertInputError('목표가는 1원 이상의 숫자로 입력해 주세요.');
      return;
    }
    setAlertInputError('');
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
        {!buildsQuery.isLoading && !buildsQuery.isError && builds.length ? (
          <SavedBuildsComparison builds={builds} />
        ) : null}

        <div className="grid gap-5">
          <Panel
            title="저장 견적"
            subtitle="상세 확인, 부품 변경, 목표가 알림 등록까지 바로 이어집니다."
            className="order-2"
            action={(
              <div className="flex flex-wrap items-center gap-2">
                <Link
                  to={offeredAssemblyRequest ? `/checkout/offers/${offeredAssemblyRequest.id}` : '/my/assembly-requests'}
                  data-testid="my-assembly-requests-link"
                  className="inline-flex min-h-9 items-center gap-1.5 rounded-md border border-commerce-line bg-white px-3 text-xs font-black text-commerce-ink hover:border-[#de6c2d] hover:text-[#de6c2d]"
                >
                  <ClipboardList size={14} />
                  {offeredAssemblyRequest
                    ? offeredAssemblyRequest.availableOfferCount == null
                      ? '도착한 기사 제안 확인'
                      : `도착한 기사 제안 ${offeredAssemblyRequest.availableOfferCount}건 확인`
                    : '조립 요청 진행'}
                </Link>
                <Link to="/requirements/new" className="rounded-md bg-[#de6c2d] px-3 py-2 text-xs font-black text-white hover:bg-[#c45c22]">AI 견적 시작</Link>
              </div>
            )}
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
                    onDuplicate={openSelfQuoteForBuild}
                    onOpenGraph={setGraphBuild}
                    onRename={(name) => renameBuildMutation.mutate({ buildId: build.id, name })}
                    onDelete={() => { if (!deleteBuildMutation.isPending) deleteBuildMutation.mutate(build.id); }}
                    isRenaming={renameBuildMutation.isPending && renameBuildMutation.variables?.buildId === build.id}
                    isDuplicating={isApplyingBuild(applyBuildMutation.variables, build, 'self-quote', applyBuildMutation.isPending)}
                    isDeleting={deleteBuildMutation.isPending && deleteBuildMutation.variables === build.id}
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
                  <Link to="/requirements/new" className="rounded-md bg-[#de6c2d] px-4 py-2.5 text-sm font-bold text-white hover:bg-[#c45c22]">AI 견적 시작</Link>
                  <Link to="/self-quote" className="rounded-md border border-slate-300 px-4 py-2.5 text-sm font-bold text-slate-700 hover:border-commerce-ink hover:text-commerce-ink">셀프 견적 시작</Link>
                </div>
              </div>
            )}
          </Panel>

          <div ref={alertFormRef} data-testid="quote-alert-registration" className="order-1">
            <Panel title="목표가 알림 등록">
              <form onSubmit={submitAlert} className="grid gap-4 lg:grid-cols-[minmax(320px,1.4fr)_minmax(180px,0.7fr)_160px_minmax(220px,0.9fr)] lg:items-end">
                <div>
                  {selectedAlertBuild ? (
                    <div className="mb-3 rounded-md border border-[#f4c8b2] bg-[#fff5ef] px-3 py-2">
                      <div className="text-[11px] font-black text-[#de6c2d]">선택한 저장 견적</div>
                      <div className="mt-1 truncate text-sm font-black text-commerce-ink" title={displayBuildName(selectedAlertBuild)}>{displayBuildName(selectedAlertBuild)}</div>
                      {selectedSavedPart ? (
                        <p className="mt-1 text-xs font-semibold text-slate-500">
                          현재 저장가 {selectedSavedPart.price.toLocaleString()}원
                        </p>
                      ) : null}
                    </div>
                  ) : null}
                  <label htmlFor="quote-alert-saved-part" className="mb-1 block text-xs font-black text-slate-600">저장 견적 부품</label>
                  <select
                    id="quote-alert-saved-part"
                    className="h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-sm font-bold text-commerce-ink focus:border-[#de6c2d] focus:outline-none focus:ring-4 focus:ring-[#f4c8b2] disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-400"
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
                  {!selectedSavedPart ? (
                    <p className="mt-2 break-keep text-xs leading-5 text-slate-500">
                      목표가를 등록하려면 저장 견적 카드의 목표가 등록 버튼을 먼저 선택하세요.
                    </p>
                  ) : null}
                </div>

                <div>
                  <label htmlFor="quote-alert-target-price" className="mb-1 block text-xs font-black text-slate-600">목표가</label>
                  <input
                    id="quote-alert-target-price"
                    className="h-11 w-full rounded-md border border-slate-300 px-3 text-sm font-bold text-commerce-ink focus:border-[#de6c2d] focus:outline-none focus:ring-4 focus:ring-[#f4c8b2]"
                    inputMode="numeric"
                    value={targetPrice}
                    onChange={(event) => {
                      setTargetPrice(event.target.value);
                      setAlertInputError('');
                    }}
                  />
                  {alertInputError ? <p className="mt-1 text-xs font-bold text-red-600">{alertInputError}</p> : null}
                </div>

                <button
                  disabled={createAlertMutation.isPending || !selectedPartIdForSubmit || !targetPriceNumber}
                  className="flex w-full min-h-11 items-center justify-center rounded-md bg-[#de6c2d] px-4 py-3 text-sm font-black text-white hover:bg-[#c45c22] disabled:cursor-not-allowed disabled:bg-slate-400 disabled:hover:bg-slate-400"
                >
                  <Save className="mr-1.5 inline" size={15} /> {createAlertMutation.isPending ? '등록 중' : '알림 등록'}
                </button>

                {nearestAlert ? (
                  <div className="rounded-md border border-[#f4c8b2] bg-[#fff5ef] px-3 py-2">
                    <div className="text-xs font-black text-slate-500">가장 가까운 목표</div>
                    <div className="mt-1 text-sm font-black text-commerce-ink">{nearestAlert.partName}</div>
                    <div className="mt-1 text-xs font-bold text-[#de6c2d]">{priceAlertDeltaText(nearestAlert)}</div>
                  </div>
                ) : null}

                {createAlertMutation.isSuccess ? <div className="lg:col-span-full"><StateMessage type="success" title="알림 등록 완료" body="목표가 알림 목록에 반영했습니다." /></div> : null}
                {createAlertMutation.isError ? <div className="lg:col-span-full"><StateMessage type="warn" title="알림 등록 실패" body="이미 같은 목표가 알림이 있거나 부품 ID가 유효하지 않습니다." /></div> : null}
              </form>
            </Panel>
          </div>

          <Panel
            title="목표가 알림"
            subtitle="현재가가 목표가에 얼마나 가까운지 차액과 진행률로 확인합니다."
            className="order-3"
            action={<span data-testid="my-quotes-achieved-count" className="text-xs font-black text-emerald-600">목표 달성 {achievedAlertCount}개</span>}
          >
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

function AlertStatusPill({ alert }: { alert: PriceAlert }) {
  const achieved = isPriceTargetAchieved(alert);
  return (
    <span className={`inline-flex rounded-full border px-2 py-1 text-[11px] font-black ${
      achieved
        ? 'border-emerald-200 bg-emerald-100 text-emerald-700'
        : 'border-[#f4c8b2] bg-[#fff5ef] text-[#de6c2d]'
    }`}
    >
      {achieved ? '목표 달성' : '추적 중'}
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
  onDuplicate,
  onOpenGraph,
  onRename,
  onDelete,
  isRenaming,
  isDuplicating,
  isDeleting
}: {
  build: BuildSummary;
  isPreparingCheckout: boolean;
  isPreparingSelfQuote: boolean;
  onAlertSelect: (build: BuildSummary) => void;
  onCheckout: (build: BuildSummary) => void;
  onEditParts: (build: BuildSummary) => void;
  onDuplicate: (build: BuildSummary) => void;
  onOpenGraph: (build: BuildSummary) => void;
  onRename: (name: string) => void;
  onDelete: () => void;
  isRenaming: boolean;
  isDuplicating: boolean;
  isDeleting: boolean;
}) {
  const mainItems = (build.items ?? []).slice(0, 4);
  const hasAlertablePart = Boolean(resolvePartId(build.items?.[0]));
  const hasCheckoutItems = quoteDraftItemsForBuild(build).length > 0;
  const [isEditingName, setIsEditingName] = useState(false);
  const [nameInput, setNameInput] = useState(displayBuildName(build));
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  function submitRename() {
    const trimmed = nameInput.trim();
    setIsEditingName(false);
    if (trimmed && trimmed !== build.name) {
      onRename(trimmed);
    } else {
      setNameInput(displayBuildName(build));
    }
  }

  function cancelRename() {
    setNameInput(displayBuildName(build));
    setIsEditingName(false);
  }

  return (
    <article data-testid={`saved-build-card-${build.id}`} className="rounded-md border border-slate-200 bg-white p-4 transition hover:border-[#f4c8b2] hover:shadow-product">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="rounded bg-[#fff5ef] px-2 py-1 text-[11px] font-black text-[#de6c2d]">{build.recommendedFor ?? '저장 견적'}</span>
            <button
              type="button"
              disabled={!hasCheckoutItems}
              onClick={() => onOpenGraph(build)}
              className="inline-flex min-h-8 items-center gap-1.5 rounded-md border border-[#f4c8b2] bg-[#fff5ef] px-2.5 text-[11px] font-black text-[#de6c2d] hover:border-[#de6c2d] hover:bg-white disabled:cursor-not-allowed disabled:border-slate-200 disabled:bg-slate-50 disabled:text-slate-400"
            >
              <GitBranch size={13} />
              견적 관계 그래프 보기
            </button>
          </div>
          {isEditingName ? (
            <div className="mt-2 flex items-center gap-1.5">
              <input
                data-testid={`rename-input-${build.id}`}
                aria-label="견적 이름"
                autoFocus
                value={nameInput}
                maxLength={120}
                onChange={(event) => setNameInput(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') submitRename();
                  if (event.key === 'Escape') cancelRename();
                }}
                className="min-w-0 flex-1 rounded-md border border-[#de6c2d] px-2 py-1 text-lg font-black leading-6 text-commerce-ink focus:outline-none focus:ring-2 focus:ring-[#f4c8b2]"
              />
              <button type="button" aria-label="이름 저장" data-testid={`rename-save-${build.id}`} onClick={submitRename} className="grid h-8 w-8 shrink-0 place-items-center rounded-md bg-[#de6c2d] text-white hover:bg-[#c45c22]">
                <Check size={15} />
              </button>
              <button type="button" aria-label="이름 변경 취소" onClick={cancelRename} className="grid h-8 w-8 shrink-0 place-items-center rounded-md border border-slate-300 bg-white text-slate-600 hover:border-commerce-ink">
                <X size={15} />
              </button>
            </div>
          ) : (
            <div className="mt-2 flex items-center gap-1.5">
              <h2 className="min-w-0 text-lg font-black leading-6 text-commerce-ink">{isRenaming ? nameInput : displayBuildName(build)}</h2>
              <button
                type="button"
                aria-label={`${displayBuildName(build)} 이름 변경`}
                data-testid={`rename-${build.id}`}
                onClick={() => { setNameInput(displayBuildName(build)); setIsEditingName(true); }}
                className="shrink-0 rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600"
              >
                <Pencil size={14} />
              </button>
            </div>
          )}
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
        </div>
        <div className="shrink-0 lg:text-right">
          <div className="text-xs font-black text-slate-500">견적 합계</div>
          <div className="mt-1 text-2xl font-black text-[#de6c2d]">{build.totalPrice.toLocaleString()}원</div>
          <div className="mt-1 text-xs font-semibold text-slate-500">{formatDateTime(build.createdAt)}</div>
        </div>
      </div>
      <div className="mt-4 flex flex-wrap gap-2 border-t border-slate-100 pt-3">
        <button
          type="button"
          disabled={!hasCheckoutItems || isPreparingCheckout}
          onClick={() => onCheckout(build)}
          className="inline-flex min-h-9 items-center gap-1.5 rounded-md bg-[#de6c2d] px-3 text-xs font-black text-white hover:bg-[#c45c22] disabled:cursor-not-allowed disabled:bg-slate-400 disabled:hover:bg-slate-400"
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
          className="inline-flex min-h-9 items-center gap-1.5 rounded-md border border-[#f4c8b2] bg-[#fff5ef] px-3 text-xs font-black text-[#de6c2d] hover:border-[#de6c2d] disabled:cursor-not-allowed disabled:border-slate-200 disabled:bg-slate-50 disabled:text-slate-400"
        >
          <Target size={14} /> 목표가 등록
        </button>
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <button
            type="button"
            data-testid={`duplicate-${build.id}`}
            disabled={!hasCheckoutItems || isDuplicating}
            onClick={() => onDuplicate(build)}
            className="inline-flex min-h-9 items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 text-xs font-black text-slate-700 hover:border-commerce-ink hover:text-commerce-ink disabled:cursor-wait disabled:text-slate-400"
          >
            <Copy size={14} /> {isDuplicating ? '편집 준비 중' : '복제 후 편집'}
          </button>
          {confirmingDelete ? (
            <div className="inline-flex items-center gap-1.5 rounded-md border border-red-200 bg-red-50 px-2 py-1">
              <span className="text-[11px] font-black text-red-700">삭제할까요?</span>
              <button
                type="button"
                data-testid={`confirm-delete-${build.id}`}
                disabled={isDeleting}
                onClick={() => { setConfirmingDelete(false); onDelete(); }}
                className="rounded bg-red-600 px-2 py-1 text-[11px] font-black text-white hover:bg-red-700 disabled:bg-red-300"
              >
                삭제
              </button>
              <button
                type="button"
                onClick={() => setConfirmingDelete(false)}
                className="rounded border border-slate-300 bg-white px-2 py-1 text-[11px] font-black text-slate-600 hover:border-commerce-ink"
              >
                취소
              </button>
            </div>
          ) : (
            <button
              type="button"
              data-testid={`delete-${build.id}`}
              disabled={isDeleting}
              onClick={() => setConfirmingDelete(true)}
              className="inline-flex min-h-9 items-center gap-1.5 rounded-md border border-red-200 bg-white px-3 text-xs font-black text-commerce-sale hover:border-red-300 hover:text-red-700 disabled:cursor-wait disabled:text-slate-400"
            >
              <Trash2 size={14} /> {isDeleting ? '삭제 중' : '삭제'}
            </button>
          )}
        </div>
      </div>
    </article>
  );
}

// 저장 견적 비교: 선택 순서대로 A/B를 고정하고 기존 견적·그래프 응답만으로 대칭 비교한다.
const COMPARE_CATEGORY_ORDER = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER'];

type ComparisonColumn = {
  build: BuildSummary;
  compositeScore: BuildCompositeScore | null;
  performanceDetails: Record<string, unknown>;
  isLoading: boolean;
  isError: boolean;
};

type CompareMetric = {
  label: string;
  valueA: number;
  valueB: number;
  unit: string;
};

type CategoryComparison = {
  metric: CompareMetric | null;
  description: string;
  metaA: string[];
  metaB: string[];
  samePart: boolean;
};

type ComparisonOutcome = {
  prefix: string;
  emphasis?: string;
  tone: 'positive' | 'neutral' | 'muted';
};

function itemsForCategory(build: BuildSummary, category: string): BuildItem[] {
  return (build.items ?? []).filter((item) => item.category === category);
}

function SavedBuildsComparison({ builds }: { builds: BuildSummary[] }) {
  const [selectedIds, setSelectedIds] = useState<string[]>(() => builds.slice(0, 2).map((build) => build.id));

  // 견적 목록이 갱신되면 삭제된 선택만 제거한다. 사용자가 비운 선택은 자동 복원하지 않는다.
  useEffect(() => {
    setSelectedIds((prev) => {
      const valid = prev.filter((id) => builds.some((build) => build.id === id)).slice(0, 2);
      if (valid.length === prev.length && valid.every((id, index) => id === prev[index])) {
        return prev;
      }
      return valid;
    });
  }, [builds]);

  const selectedBuilds = useMemo(
    () => selectedIds
      .map((id) => builds.find((build) => build.id === id))
      .filter((build): build is BuildSummary => Boolean(build)),
    [builds, selectedIds]
  );

  const perfResults = useQueries({
    queries: selectedBuilds.map((build) => {
      const graphItems = quoteDraftItemsForBuild(build);
      return {
        queryKey: ['saved-build-composite-score', build.id, graphBuildSignature(graphItems), build.totalPrice],
        queryFn: () => resolveBuildGraph({
          source: 'AI_BUILD',
          items: graphItems,
          budgetWon: build.totalPrice
        }),
        enabled: graphItems.length > 0,
        staleTime: 5 * 60 * 1000
      };
    })
  });

  if (builds.length === 0) {
    return null;
  }

  function toggleBuild(id: string) {
    setSelectedIds((prev) => {
      if (prev.includes(id)) {
        return prev.filter((value) => value !== id);
      }
      if (prev.length >= 2) return prev;
      return [...prev, id];
    });
  }

  const columns = selectedBuilds.map((build, index) => {
    const result = perfResults[index];
    const compositeScore = result?.data?.compositeScore ?? null;
    return {
      build,
      compositeScore,
      performanceDetails: performanceDetails(result?.data),
      isLoading: result?.isLoading ?? false,
      isError: result?.isError ?? false
    } satisfies ComparisonColumn;
  });

  return (
    <section data-testid="saved-builds-comparison" className="rounded-md border border-commerce-line bg-white p-5 shadow-product">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <p className="text-xs font-black tracking-wide text-[#de6c2d]">견적 비교</p>
          <h2 className="mt-1 text-lg font-black text-commerce-ink">견적 골라 부품·성능 비교</h2>
          <p className="mt-1 break-keep text-xs leading-5 text-slate-500">
            비교할 견적 2개를 고르면 가격·종합점수와 부품별 실제 수치를 좌우로 비교합니다.
          </p>
        </div>
        <p className="text-[11px] font-bold text-slate-400">종합 점수와 벤치마크는 참고값이며 실제 FPS·체감과 다를 수 있음</p>
      </div>

      {/* 비교할 견적 선택 칩 */}
      <div className="mt-4 flex flex-wrap items-center gap-2">
        <span className="text-[11px] font-black text-slate-500">비교할 견적</span>
        {builds.map((build) => {
          const active = selectedIds.includes(build.id);
          const selectionIndex = selectedIds.indexOf(build.id);
          const disabled = !active && selectedIds.length >= 2;
          return (
            <button
              key={build.id}
              type="button"
              data-testid={`compare-toggle-${build.id}`}
              aria-pressed={active}
              disabled={disabled}
              onClick={() => toggleBuild(build.id)}
              className={`inline-flex max-w-[220px] items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-bold transition ${
                active
                  ? 'border-[#de6c2d] bg-[#fff5ef] text-[#de6c2d]'
                  : disabled
                    ? 'cursor-not-allowed border-slate-100 bg-slate-50 text-slate-300'
                    : 'border-slate-200 bg-white text-slate-500 hover:border-slate-300 hover:text-slate-700'
              }`}
              title={displayBuildName(build)}
            >
              <span className={`inline-flex h-4 min-w-4 shrink-0 items-center justify-center rounded-full text-[9px] font-black ${active ? 'bg-[#de6c2d] text-white' : 'bg-slate-200 text-slate-400'}`}>
                {active ? (selectionIndex === 0 ? 'A' : 'B') : ''}
              </span>
              <span className="truncate">{displayBuildName(build)}</span>
            </button>
          );
        })}
      </div>

      {columns.length < 2 ? (
        <p data-testid="quote-compare-selection-guide" className="mt-5 rounded-md border border-dashed border-[#DE6C2D]/40 bg-[#DE6C2D]/10 px-4 py-7 text-center text-sm font-black text-[#DE6C2D]">
          {columns.length === 0 ? '비교할 견적 2개를 선택하세요' : '비교할 견적을 하나 더 선택하세요'}
        </p>
      ) : <SavedBuildComparisonResult columnA={columns[0]} columnB={columns[1]} />}
    </section>
  );
}

function SavedBuildComparisonResult({ columnA, columnB }: { columnA: ComparisonColumn; columnB: ComparisonColumn }) {
  const categories = COMPARE_CATEGORY_ORDER.filter((category) =>
    itemsForCategory(columnA.build, category).length > 0 || itemsForCategory(columnB.build, category).length > 0
  );
  const scoreA = columnA.compositeScore?.score;
  const scoreB = columnB.compositeScore?.score;
  const scoreComparison = typeof scoreA === 'number' && typeof scoreB === 'number' ? scoreA - scoreB : 0;

  return (
    <div className="mt-4 space-y-4">
      <div className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(260px,0.9fr)_minmax(0,1fr)]">
        <QuoteSummaryCard label="A" column={columnA} isHigherScore={scoreComparison > 0} />
        <ComparisonDeltaCard columnA={columnA} columnB={columnB} />
        <QuoteSummaryCard label="B" column={columnB} isHigherScore={scoreComparison < 0} />
      </div>

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        <div className="hidden grid-cols-[minmax(0,1fr)_132px_76px_132px_minmax(0,1fr)] items-center border-b border-slate-200 bg-slate-50 px-4 py-2 text-center text-[11px] font-black text-slate-500 md:grid">
          <span>A 견적</span>
          <span>A 지표</span>
          <span>부품</span>
          <span>B 지표</span>
          <span>B 견적</span>
        </div>
        <div className="divide-y divide-slate-100">
          {categories.map((category) => {
            const itemsA = itemsForCategory(columnA.build, category);
            const itemsB = itemsForCategory(columnB.build, category);
            const comparison = compareCategory(category, itemsA, itemsB, columnA.performanceDetails, columnB.performanceDetails);
            return (
              <PartComparisonRow
                key={category}
                category={category}
                itemsA={itemsA}
                itemsB={itemsB}
                comparison={comparison}
              />
            );
          })}
        </div>
      </div>
    </div>
  );
}

function QuoteSummaryCard({ label, column, isHigherScore }: { label: 'A' | 'B'; column: ComparisonColumn; isHigherScore: boolean }) {
  const score = column.compositeScore;
  return (
    <article data-testid={`quote-summary-${label}`} className={`relative isolate rounded-lg border border-[#DE6C2D]/35 bg-white px-4 py-3 shadow-sm ${isHigherScore ? 'shadow-[0_0_18px_rgba(222,108,45,0.16)]' : ''}`}>
      {isHigherScore ? <span aria-hidden="true" className="pointer-events-none absolute -inset-1 -z-10 rounded-xl bg-[#DE6C2D]/10 blur-md animate-pulse" /> : null}
      <div className="grid h-full grid-cols-[auto_minmax(0,1fr)_minmax(0,1fr)] items-center gap-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-[#DE6C2D] text-base font-black text-white">{label}</span>
        <div className="min-w-0 self-center">
          <div className="text-[10px] font-bold text-slate-400">총 견적 금액</div>
          <div className="mt-1 text-lg font-black tracking-tight text-commerce-ink">{column.build.totalPrice.toLocaleString('ko-KR')}원</div>
        </div>
        <div className="min-w-0 self-center border-l border-slate-200 pl-3">
          <div className="text-[10px] font-bold text-slate-400">종합 점수</div>
          <ScoreValue column={column} isHigherScore={isHigherScore} />
        </div>
      </div>
    </article>
  );
}

function ScoreValue({ column, isHigherScore }: { column: ComparisonColumn; isHigherScore: boolean }) {
  if (column.isLoading) return <div className="mt-1 h-6 w-20 animate-pulse rounded bg-slate-200" />;
  if (column.isError) return <div className="mt-1 text-xs font-black text-slate-500">조회 실패</div>;
  if (!column.compositeScore) return <div className="mt-1 text-xs font-black text-slate-500">자료 없음</div>;
  return (
    <div className={`mt-1 text-lg font-black tracking-tight ${isHigherScore ? 'text-red-600' : 'text-commerce-ink'}`}>
      {column.compositeScore.score.toLocaleString('ko-KR')}점
      <span className="ml-1 text-[10px] font-bold text-slate-400">/ {column.compositeScore.maxScore.toLocaleString('ko-KR')}</span>
    </div>
  );
}

function ComparisonDeltaCard({ columnA, columnB }: { columnA: ComparisonColumn; columnB: ComparisonColumn }) {
  const price = priceDeltaOutcome(columnA.build.totalPrice, columnB.build.totalPrice);
  const score = scoreDeltaOutcome(columnA, columnB);
  return (
    <article className="flex min-h-[116px] items-center justify-center rounded-lg border border-slate-200 bg-slate-50/70 px-4 py-3 shadow-sm">
      <div className="w-full max-w-[260px] space-y-3">
        <ComparisonResultLine kind="price" testId="quote-compare-price-delta" outcome={price} />
        <ComparisonResultLine kind="score" testId="quote-compare-score-delta" outcome={score} />
        <p className="pt-0.5 text-center text-[10px] font-bold text-slate-400">가격·성능은 독립 비교</p>
      </div>
    </article>
  );
}

function ComparisonResultLine({ kind, testId, outcome }: { kind: 'price' | 'score'; testId: string; outcome: ComparisonOutcome }) {
  const Icon = kind === 'price' ? ArrowDown : ArrowUp;
  const iconLabel = kind === 'price' ? '가격 낮음' : '성능 높음';
  const emphasisClass = outcome.tone === 'positive' ? 'text-red-600' : outcome.tone === 'muted' ? 'text-slate-500' : 'text-slate-600';
  const unitPattern = kind === 'price' ? /^(.+?원)(.*)$/ : /^(.+?점)(.*)$/;
  const [, emphasisValue = outcome.emphasis ?? '', emphasisSuffix = ''] = outcome.emphasis?.match(unitPattern) ?? [];
  return (
    <div className="flex items-baseline justify-center gap-2.5 text-lg">
      <span aria-label={iconLabel} className="animate-bounce text-slate-400"><Icon aria-hidden="true" size={18} strokeWidth={2.5} /></span>
      <p data-testid={testId} className="min-w-0 text-center font-black text-commerce-ink">
        <span>{outcome.prefix}</span>
        {outcome.emphasis ? (
          <>
            <span className={`font-black ${emphasisClass}`}>{emphasisValue}</span>
            <span>{emphasisSuffix}</span>
          </>
        ) : null}
      </p>
    </div>
  );
}

function PartComparisonRow({
  category,
  itemsA,
  itemsB,
  comparison
}: {
  category: string;
  itemsA: BuildItem[];
  itemsB: BuildItem[];
  comparison: CategoryComparison;
}) {
  const specification = isSpecificationCategory(category);
  const compact = comparison.samePart;
  return (
    <article
      data-testid={`quote-compare-row-${category}`}
      className={`grid grid-cols-[minmax(0,1fr)_64px_minmax(0,1fr)] gap-x-3 gap-y-2 px-4 md:grid-cols-[minmax(0,1fr)_132px_76px_132px_minmax(0,1fr)] md:items-center ${compact ? 'py-2.5' : 'py-3'}`}
    >
      <PartSummary category={category} items={itemsA} meta={specification ? [] : comparison.metaA} side="A" />
      <div className="col-start-1 row-start-2 md:col-start-2 md:row-start-1">
        {specification
          ? <SpecificationMetric category={category} values={comparison.metaA} side="A" hasPart={itemsA.length > 0} />
          : <MetricBar category={category} metric={comparison.metric} side="A" hasPart={itemsA.length > 0} samePart={comparison.samePart} />}
      </div>
      <div className="col-start-2 row-span-2 row-start-1 flex self-center justify-center md:col-start-3 md:row-span-1 md:row-start-1">
        <span className="inline-flex min-h-8 min-w-14 items-center justify-center rounded-md bg-slate-100 px-2 text-xs font-black text-slate-600">
          {labelForCategory(category)}
        </span>
      </div>
      <div className="col-start-3 row-start-2 md:col-start-4 md:row-start-1">
        {specification
          ? <SpecificationMetric category={category} values={comparison.metaB} side="B" hasPart={itemsB.length > 0} />
          : <MetricBar category={category} metric={comparison.metric} side="B" hasPart={itemsB.length > 0} samePart={comparison.samePart} />}
      </div>
      <PartSummary category={category} items={itemsB} meta={specification ? [] : comparison.metaB} side="B" />
      <p data-testid={`quote-compare-description-${category}`} className={`col-span-3 row-start-3 truncate text-center text-xs font-black leading-4 md:col-span-5 md:row-start-2 ${comparisonDescriptionClass(comparison.description)}`}>
        {comparison.description}
      </p>
    </article>
  );
}

function PartSummary({ category, items, meta, side }: { category: string; items: BuildItem[]; meta: string[]; side: 'A' | 'B' }) {
  const isLeft = side === 'A';
  const positionClass = isLeft
    ? 'col-start-1 text-right md:col-start-1'
    : 'col-start-3 text-left md:col-start-5';
  if (items.length === 0) {
    return <div className={`${positionClass} row-start-1 text-sm font-black text-slate-500`}>미포함</div>;
  }
  const [primary, ...rest] = items;
  const totalPrice = items.reduce((sum, item) => sum + (item.price ?? 0), 0);
  return (
    <div className={`${positionClass} row-start-1 min-w-0`}>
      <div className={`${category === 'CASE' ? 'line-clamp-2' : 'truncate'} text-sm font-black leading-5 text-commerce-ink`} title={items.map((item) => item.name).join(', ')}>
        {primary.name}{rest.length > 0 ? ` 외 ${rest.length}` : ''}
      </div>
      <div className="mt-px text-sm font-bold text-slate-500">{totalPrice.toLocaleString('ko-KR')}원</div>
      {meta.length > 0 ? <div className="mt-0.5 truncate text-xs font-semibold leading-4 text-slate-400" title={meta.slice(0, 2).join(' · ')}>{meta.slice(0, 2).join(' · ')}</div> : null}
    </div>
  );
}

function MetricBar({ category, metric, side, hasPart, samePart }: { category: string; metric: CompareMetric | null; side: 'A' | 'B'; hasPart: boolean; samePart: boolean }) {
  const [isFilled, setIsFilled] = useState(false);

  useEffect(() => {
    const frame = requestAnimationFrame(() => setIsFilled(true));
    return () => cancelAnimationFrame(frame);
  }, []);

  if (!hasPart) return <div className="text-center text-xs font-bold text-slate-500">미포함</div>;
  if (!metric) return null;
  if (samePart) return <div data-testid={`quote-compare-bar-${category}-${side}`} className="text-center text-xs font-black text-slate-500">동일</div>;
  const value = side === 'A' ? metric.valueA : metric.valueB;
  const max = Math.max(metric.valueA, metric.valueB);
  const index = Math.round((value / max) * 100);
  return (
    <div data-testid={`quote-compare-bar-${category}-${side}`} className="min-w-0">
      <div className={`mb-0.5 flex items-baseline gap-1 ${side === 'A' ? 'justify-end' : 'justify-start'}`}>
        <span className="text-sm font-black text-slate-600">{formatMetricValue(value, metric.unit)}</span>
        <span className="text-xs font-bold text-slate-400">{metric.label}</span>
      </div>
      <div className={`flex h-2 overflow-hidden rounded-full bg-slate-100 ${side === 'A' ? 'justify-end' : 'justify-start'}`}>
        <div className="h-full rounded-full bg-[#FDBA74] transition-[width] duration-700 ease-out" style={{ width: isFilled ? `${index}%` : '0%' }} />
      </div>
    </div>
  );
}

function SpecificationMetric({ category, values, side, hasPart }: { category: string; values: string[]; side: 'A' | 'B'; hasPart: boolean }) {
  if (!hasPart) return <div className="text-center text-xs font-bold text-slate-500">미포함</div>;
  if (values.length === 0) return null;
  return (
    <div data-testid={`quote-compare-spec-${category}-${side}`} className={`${category === 'CASE' ? 'line-clamp-2' : 'truncate'} text-xs font-bold leading-4 text-slate-500 ${side === 'A' ? 'text-right' : 'text-left'}`} title={values.slice(0, 3).join(' · ')}>
      {values.slice(0, 3).join(' · ')}
    </div>
  );
}

function compareCategory(
  category: string,
  itemsA: BuildItem[],
  itemsB: BuildItem[],
  performanceA: Record<string, unknown>,
  performanceB: Record<string, unknown>
): CategoryComparison {
  const samePart = sameParts(itemsA, itemsB);
  const metaA = partMeta(category, itemsA);
  const metaB = partMeta(category, itemsB);
  if (itemsA.length === 0 || itemsB.length === 0) {
    const missing = itemsA.length === 0 && itemsB.length === 0 ? '모두 미포함' : itemsA.length === 0 ? 'A 미포함' : 'B 미포함';
    return { metric: null, description: missing, metaA, metaB, samePart: false };
  }

  if (category === 'CPU' || category === 'GPU') {
    const key = category === 'CPU' ? 'cpuBenchmarkScore' : 'gpuBenchmarkScore';
    const valueA = numberValue(performanceA[key]);
    const valueB = numberValue(performanceB[key]);
    const metric = positiveMetric(`${category} 벤치마크`, valueA, valueB, '점');
    return {
      metric,
      description: samePart ? '동일 부품' : metric ? percentageDescription(`${category} 성능`, metric.valueA, metric.valueB) : '비교 가능한 수치 없음',
      metaA,
      metaB,
      samePart
    };
  }

  if (category === 'RAM') {
    const capacityA = sumAttribute(itemsA, 'capacityGb');
    const capacityB = sumAttribute(itemsB, 'capacityGb');
    const speedA = maxAttribute(itemsA, 'speedMhz');
    const speedB = maxAttribute(itemsB, 'speedMhz');
    const metric = positiveMetric('RAM 용량', capacityA, capacityB, 'GB');
    let description = samePart ? '동일 부품' : numericDifferenceDescription('RAM 용량', capacityA, capacityB, 'GB', '큼');
    if (!samePart && capacityA === capacityB && capacityA !== null) {
      description = numericDifferenceDescription('RAM 클럭', speedA, speedB, 'MHz', '높음');
    }
    return { metric, description, metaA, metaB, samePart };
  }

  if (category === 'STORAGE') {
    const readA = maxAttribute(itemsA, 'readMbps');
    const readB = maxAttribute(itemsB, 'readMbps');
    const capacityA = sumAttribute(itemsA, 'capacityGb');
    const capacityB = sumAttribute(itemsB, 'capacityGb');
    const useRead = isPositive(readA) && isPositive(readB);
    const metric = useRead
      ? positiveMetric('읽기 속도', readA, readB, 'MB/s')
      : positiveMetric('저장 용량', capacityA, capacityB, 'GB');
    const description = samePart
      ? '동일 부품'
      : useRead && metric
        ? percentageDescription('SSD 읽기 속도', metric.valueA, metric.valueB)
        : storageCapacityDescription(capacityA, capacityB);
    return { metric, description, metaA, metaB, samePart };
  }

  if (category === 'PSU') {
    const capacityA = maxAttribute(itemsA, 'capacityW');
    const capacityB = maxAttribute(itemsB, 'capacityW');
    const metric = positiveMetric('정격 출력', capacityA, capacityB, 'W');
    return {
      metric,
      description: samePart ? '동일 부품' : numericDifferenceDescription('정격 출력', capacityA, capacityB, 'W', '높음'),
      metaA,
      metaB,
      samePart
    };
  }

  return {
    metric: null,
    description: samePart ? '동일 부품' : specificationDescription(category, itemsA[0], itemsB[0]),
    metaA,
    metaB,
    samePart
  };
}

function performanceDetails(result?: { toolResults?: Array<{ tool: string; details?: Record<string, unknown> }> }) {
  return result?.toolResults?.find((tool) => tool.tool === 'performance')?.details ?? {};
}

function positiveMetric(label: string, valueA: number | null, valueB: number | null, unit: string): CompareMetric | null {
  if (!isPositive(valueA) || !isPositive(valueB)) return null;
  return { label, valueA, valueB, unit };
}

function numberValue(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value !== 'string' || !/^-?\d+(?:\.\d+)?$/.test(value.trim())) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function attributeNumber(item: BuildItem | undefined, key: string): number | null {
  return numberValue(item?.attributes?.[key]);
}

function sumAttribute(items: BuildItem[], key: string): number | null {
  const values = items.map((item) => attributeNumber(item, key)).filter((value): value is number => value !== null);
  return values.length === items.length && values.length > 0 ? values.reduce((sum, value) => sum + value, 0) : null;
}

function maxAttribute(items: BuildItem[], key: string): number | null {
  const values = items.map((item) => attributeNumber(item, key)).filter((value): value is number => value !== null);
  return values.length > 0 ? Math.max(...values) : null;
}

function attributeText(item: BuildItem | undefined, key: string): string | null {
  const value = item?.attributes?.[key];
  if (typeof value === 'string' && value.trim()) return value.trim();
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  if (typeof value === 'boolean') return value ? '지원' : '미지원';
  if (Array.isArray(value) && value.length > 0) return value.map(String).join(', ');
  return null;
}

function partMeta(category: string, items: BuildItem[]): string[] {
  if (items.length === 0) return [];
  const item = items[0];
  const values: Array<string | null> = [];
  if (category === 'CPU') {
    values.push(unitText(attributeNumber(item, 'coreCount'), '코어'), unitText(attributeNumber(item, 'threadCount'), '스레드'));
  } else if (category === 'GPU') {
    values.push(unitText(attributeNumber(item, 'vramGb'), 'GB VRAM'));
  } else if (category === 'RAM') {
    values.push(unitText(sumAttribute(items, 'capacityGb'), 'GB'), unitText(maxAttribute(items, 'speedMhz'), 'MHz'), unitText(sumAttribute(items, 'moduleCount'), '개 모듈'));
  } else if (category === 'STORAGE') {
    values.push(formatCapacity(sumAttribute(items, 'capacityGb')), unitText(maxAttribute(items, 'readMbps'), 'MB/s 읽기'), unitText(maxAttribute(items, 'writeMbps'), 'MB/s 쓰기'));
  } else if (category === 'PSU') {
    values.push(unitText(maxAttribute(items, 'capacityW'), 'W'), attributeText(item, 'efficiency'));
  } else if (category === 'MOTHERBOARD') {
    values.push(attributeText(item, 'socket'), attributeText(item, 'chipset'), attributeText(item, 'memoryType'), attributeText(item, 'formFactor'));
  } else if (category === 'CASE') {
    const maxGpu = unitText(attributeNumber(item, 'maxGpuLengthMm'), 'mm');
    const maxCooler = unitText(attributeNumber(item, 'maxCpuCoolerHeightMm'), 'mm');
    values.push(attributeText(item, 'formFactor'), maxGpu ? `GPU ${maxGpu}` : null, maxCooler ? `쿨러 ${maxCooler}` : null);
  } else if (category === 'COOLER') {
    values.push(
      coolerTypeText(attributeText(item, 'coolerType')),
      unitText(attributeNumber(item, 'radiatorMm'), 'mm'),
      unitText(attributeNumber(item, 'tdpW'), 'W')
    );
  }
  return values.filter((value): value is string => Boolean(value));
}

function sameParts(itemsA: BuildItem[], itemsB: BuildItem[]) {
  if (itemsA.length === 0 || itemsA.length !== itemsB.length) return false;
  const idsA = itemsA.map(partIdentity).filter((id): id is string => Boolean(id)).sort();
  const idsB = itemsB.map(partIdentity).filter((id): id is string => Boolean(id)).sort();
  return idsA.length === itemsA.length && idsB.length === itemsB.length && idsA.every((id, index) => id === idsB[index]);
}

function partIdentity(item: BuildItem) {
  return item.partId ?? item.id ?? null;
}

function percentageDescription(label: string, valueA: number, valueB: number) {
  if (valueA === valueB) return '동일 수준';
  const winner = valueA > valueB ? 'A' : 'B';
  const lower = Math.min(valueA, valueB);
  const percent = lower > 0 ? Math.round((Math.abs(valueA - valueB) / lower) * 100) : 0;
  return percent <= 3 ? `${label} 유사 · ${winner} +${percent}%` : `${winner} ${label} +${percent}%`;
}

function numericDifferenceDescription(
  label: string,
  valueA: number | null,
  valueB: number | null,
  unit: string,
  _adjective: string,
  equalText = '동일 수준'
) {
  if (valueA === null || valueB === null) return '비교 가능한 수치 없음';
  if (valueA === valueB) return equalText;
  const winner = valueA > valueB ? 'A' : 'B';
  return `${winner} ${label} +${formatMetricValue(Math.abs(valueA - valueB), unit)}`;
}

function storageCapacityDescription(valueA: number | null, valueB: number | null) {
  if (valueA === null || valueB === null) return '비교 가능한 수치 없음';
  if (valueA === valueB) return '동일 수준';
  const winner = valueA > valueB ? 'A' : 'B';
  return `${winner} 저장 용량 +${formatCapacity(Math.abs(valueA - valueB))}`;
}

function specificationDescription(category: string, itemA: BuildItem, itemB: BuildItem) {
  const fields: Record<string, Array<{ label: string; keys: string[] }>> = {
    MOTHERBOARD: [
      { label: '소켓', keys: ['socket'] },
      { label: '칩셋', keys: ['chipset'] },
      { label: '메모리', keys: ['memoryType'] },
      { label: '폼팩터', keys: ['formFactor'] }
    ],
    CASE: [
      { label: '폼팩터', keys: ['formFactor'] },
      { label: 'GPU 장착 길이', keys: ['maxGpuLengthMm'] },
      { label: 'CPU 쿨러 높이', keys: ['maxCpuCoolerHeightMm'] }
    ],
    COOLER: [
      { label: '쿨러 유형', keys: ['coolerType'] },
      { label: '지원 소켓', keys: ['socketSupport'] },
      { label: '높이', keys: ['heightMm', 'coolerHeightMm'] },
      { label: '라디에이터', keys: ['radiatorMm'] },
      { label: '표기 TDP', keys: ['tdpW'] }
    ]
  };
  const compared = (fields[category] ?? []).map((field) => ({
    label: field.label,
    valueA: firstAttributeValue(itemA, field.keys),
    valueB: firstAttributeValue(itemB, field.keys)
  }));
  const comparable = compared.filter((field) => field.valueA !== null && field.valueB !== null);
  const different = comparable.filter((field) => normalizeSpec(field.valueA) !== normalizeSpec(field.valueB));
  if (different.length > 0) {
    if (category === 'COOLER' && different.length > 1) {
      return `${different.slice(0, 2).map((field) => field.label).join('·')} 다름`;
    }
    return `${different[0].label} 다름`;
  }
  if (compared.some((field) => (field.valueA === null) !== (field.valueB === null))) return '주요 규격 일부 없음';
  return comparable.length > 0 ? '주요 규격 동일' : '비교 가능한 수치 없음';
}

function comparisonDescriptionClass(description: string) {
  if (description.includes('미포함') || description.includes('비교 가능한')) return 'text-slate-500';
  if (description.includes('동일') || description.includes('유사') || description.includes('규격')) return 'text-slate-500';
  return 'text-orange-600';
}

function isSpecificationCategory(category: string) {
  return category === 'MOTHERBOARD' || category === 'CASE' || category === 'COOLER';
}

function coolerTypeText(value: string | null) {
  if (!value) return null;
  const normalized = value.toUpperCase();
  if (normalized.includes('AIR') || value.includes('공랭')) return '공랭';
  if (normalized.includes('WATER') || normalized.includes('AIO') || value.includes('수랭')) return '수랭';
  return value;
}

function firstAttributeValue(item: BuildItem, keys: string[]) {
  for (const key of keys) {
    const value = attributeText(item, key);
    if (value !== null) return value;
  }
  return null;
}

function normalizeSpec(value: string | null) {
  return value?.replace(/\s+/g, '').toLowerCase() ?? '';
}

function isPositive(value: number | null): value is number {
  return value !== null && value > 0;
}

function unitText(value: number | null, unit: string) {
  return value === null ? null : `${value.toLocaleString('ko-KR')}${unit}`;
}

function formatCapacity(value: number | null) {
  if (value === null) return null;
  return value >= 1000 && value % 1000 === 0 ? `${value / 1000}TB` : `${value.toLocaleString('ko-KR')}GB`;
}

function formatMetricValue(value: number, unit: string) {
  if (unit === 'GB' && value >= 1000) return formatCapacity(value) ?? '';
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 1 })}${unit}`;
}

function priceDeltaOutcome(priceA: number, priceB: number): ComparisonOutcome {
  if (priceA === priceB) return { prefix: '동일', tone: 'neutral' };
  const winner = priceA < priceB ? 'A' : 'B';
  return {
    prefix: `${winner}가 `,
    emphasis: `${Math.abs(priceA - priceB).toLocaleString('ko-KR')}원 저렴`,
    tone: 'positive'
  };
}

function scoreDeltaOutcome(columnA: ComparisonColumn, columnB: ComparisonColumn): ComparisonOutcome {
  if (columnA.isLoading || columnB.isLoading) return { prefix: '계산 중', tone: 'muted' };
  if (columnA.isError || columnB.isError) return { prefix: '조회 실패', tone: 'muted' };
  const scoreA = columnA.compositeScore?.score;
  const scoreB = columnB.compositeScore?.score;
  if (scoreA === undefined || scoreB === undefined) return { prefix: '자료 없음', tone: 'muted' };
  if (scoreA === scoreB) return { prefix: '동일 수준', tone: 'neutral' };
  const winner = scoreA > scoreB ? 'A' : 'B';
  return {
    prefix: `${winner}가 `,
    emphasis: `${Math.abs(scoreA - scoreB).toLocaleString('ko-KR')}점 높음`,
    tone: 'positive'
  };
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
              achieved ? 'bg-emerald-100 text-emerald-700' : 'bg-[#fff5ef] text-[#de6c2d]'
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
          <div className="mt-1 text-lg font-black text-[#de6c2d]">{alert.currentPrice.toLocaleString()}원</div>
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
        <div className={`h-full rounded-full ${achieved ? 'bg-emerald-500' : 'bg-[#de6c2d]'}`} style={{ width: `${progress}%` }} />
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
            <div className="text-xs font-black text-[#de6c2d]">읽기 전용</div>
            <h2 className="mt-1 truncate text-lg font-black text-commerce-ink" title={displayBuildName(build)}>{displayBuildName(build)}</h2>
            <p className="mt-1 text-xs font-semibold text-slate-500">저장 견적의 부품 관계를 확인합니다. 이 팝업에서는 부품 교체나 담기 동작을 하지 않습니다.</p>
          </div>
          <button
            type="button"
            aria-label="관계 그래프 닫기"
            onClick={onClose}
            className="grid h-9 w-9 shrink-0 place-items-center rounded-md border border-commerce-line bg-white text-slate-500 hover:border-slate-300 hover:text-commerce-ink focus:outline-none focus:ring-4 focus:ring-[#f4c8b2]"
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
      buildName: displayBuildName(build),
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

function uniqueBuildsByPartCombination(builds: BuildSummary[]) {
  const seen = new Set<string>();
  return builds.filter((build) => {
    const signature = buildPartCombinationSignature(build);
    if (!signature || seen.has(signature)) return !signature;
    seen.add(signature);
    return true;
  });
}

function buildPartCombinationSignature(build: BuildSummary) {
  const items = build.items ?? [];
  if (items.length === 0) return '';
  const parts = items.map((item) => {
    const partId = resolvePartId(item);
    return partId ? `${item.category}:${partId}` : '';
  });
  if (parts.some((part) => !part)) return '';
  return [...new Set(parts)].sort().join('|');
}

function displayBuildName(build: BuildSummary) {
  const currentName = build.name?.trim();
  const isGenericName = /^(셀프\s*견적\s*저장\s*조합|저장\s*견적|self\s*quote\s*saved\s*combination)$/i.test(currentName ?? '');
  if (currentName && !/\bbuild\b/i.test(currentName) && !isGenericName) return currentName;

  const context = [currentName, build.recommendedFor, build.summary]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
  const purpose = context.includes('qhd')
    ? 'QHD 게이밍'
    : /creator|work|작업/.test(context)
      ? '작업용'
      : /high|performance|고성능/.test(context)
        ? '고성능'
        : /gaming|game|게이밍/.test(context)
          ? '게이밍'
          : /balanced|balance|균형/.test(context)
            ? '균형'
            : /budget|value|가성비/.test(context)
              ? '가성비'
              : '맞춤';
  const priceBand = Math.max(10, Math.round(build.totalPrice / 100_000) * 10);
  return `약 ${priceBand}만원 · ${purpose} 견적`;
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
