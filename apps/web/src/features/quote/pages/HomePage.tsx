import { useEffect, useMemo, useRef, useState, type SyntheticEvent } from 'react';
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import {
  ArrowRight,
  Bot,
  Box,
  Boxes,
  CircuitBoard,
  Cpu,
  Fan,
  FileText,
  HardDrive,
  MemoryStick,
  Monitor,
  ShieldCheck,
  Sparkles,
  Wrench,
  X,
  Zap,
  type LucideIcon
} from 'lucide-react';
import { AppHeader } from '../../../components/ui';
import { useLockedPageScroll } from '../../../hooks/useHiddenPageScrollbar';
import { AUTH_CHANGED_EVENT } from '../../../lib/api';
import {
  AI_BUILD_ASSISTANT_VISIBILITY_CHANGED_EVENT,
  isAiAssistantOpen,
  openAiAssistant,
  type AiAssistantVisibilityDetail
} from '../../../lib/events';
import { partImageUrl } from '../../parts/partDisplay';
import {
  applyAiBuildToQuoteDraft,
  getPart,
  listHomeRecommendedParts,
  listParts,
  recordRecommendationEvent
} from '../../parts/partsApi';
import type { HomeRecommendedPart, PartPage, PartRow } from '../../parts/types';
import {
  AI_ASSISTANT_SESSION_CHANGED_EVENT,
  normalizeAiRecommendedBuild,
  readAssistantSession,
  recentBuildsForChatContext,
  saveSelectedAiBuild,
  type AiAssistantSession,
  type AiRecommendedBuild,
  type PartCategory
} from '../aiSelection';
import { HomeAiBuildPreview } from '../components/HomeAiBuildPreview';
import {
  HomeFeaturedBuildPreview,
  type HomeFeaturedBuildPreviewItem
} from '../components/HomeFeaturedBuildPreview';
import { HomeQuickStartPanel } from '../components/HomeQuickStartPanel';
import { resolveBuildGraph } from '../quoteApi';

type CategoryItem = {
  value: PartCategory;
  label: string;
  Icon: LucideIcon;
};

type CuratedBuild = HomeFeaturedBuildPreviewItem['build'];

const PART_CATEGORIES: CategoryItem[] = [
  { value: 'CPU', label: 'CPU', Icon: Cpu },
  { value: 'GPU', label: 'GPU', Icon: Monitor },
  { value: 'MOTHERBOARD', label: '메인보드', Icon: CircuitBoard },
  { value: 'RAM', label: '메모리', Icon: MemoryStick },
  { value: 'STORAGE', label: '저장장치', Icon: HardDrive },
  { value: 'PSU', label: '파워', Icon: Zap },
  { value: 'CASE', label: '케이스', Icon: Box },
  { value: 'COOLER', label: '쿨러', Icon: Fan }
];

const BUILD_CATEGORY_ORDER: PartCategory[] = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER'];

const CURATED_BUILD_TEMPLATES: Array<Omit<CuratedBuild, 'partSearches'>> = [
  {
    id: 'home-featured-qhd-gaming',
    name: 'QHD 게이밍 추천팩',
    tag: 'BEST',
    spec: 'QHD 게임 · 개발 · 안정성 균형',
    summary: '게임과 개발을 함께 사용하는 사용자를 위한 균형형 구성입니다.',
    tone: 'from-white via-white to-white'
  },
  {
    id: 'home-featured-creator',
    name: '크리에이터 작업팩',
    tag: '작업',
    spec: '영상 편집 · 렌더링 · 넉넉한 저장공간',
    summary: '고해상도 편집과 렌더링을 고려해 CPU와 메모리 여유를 높였습니다.',
    tone: 'from-white via-white to-white'
  },
  {
    id: 'home-featured-reliable',
    name: '신뢰성 우선 추천팩',
    tag: '안정성',
    spec: '호환성 · 전력 여유 · 업그레이드',
    summary: '부품 간 호환성과 전력 여유를 우선해 오래 사용하기 좋은 구성입니다.',
    tone: 'from-white via-white to-white'
  }
];

const FALLBACK_PRODUCT_IMAGE = '/assets/home-banners/pc-build-festa.png';
const HOME_LOGIN_CHOICE_DISMISSED_KEY = 'buildgraph.homeLoginChoice.dismissed';

function readHomeLoginChoiceDismissed() {
  try {
    return localStorage.getItem(HOME_LOGIN_CHOICE_DISMISSED_KEY) === 'true';
  } catch {
    return false;
  }
}

function dismissHomeLoginChoice() {
  try {
    localStorage.setItem(HOME_LOGIN_CHOICE_DISMISSED_KEY, 'true');
  } catch {
    // localStorage가 막힌 환경에서는 현재 화면에서만 닫힌 상태로 둔다.
  }
}

function resetHomeLoginChoiceDismissed() {
  try {
    localStorage.removeItem(HOME_LOGIN_CHOICE_DISMISSED_KEY);
  } catch {
    // localStorage가 막힌 환경에서는 현재 화면 상태만 유지한다.
  }
}

function shouldShowHomeLoginChoice(skipPrompt: boolean) {
  return !skipPrompt && !isAiAssistantOpen() && !readHomeLoginChoiceDismissed();
}

export function HomePage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();
  const skipHomeChoicePromptRef = useRef(searchParams.get('assistant') === 'open');
  const [assistantSession, setAssistantSession] = useState<AiAssistantSession>(() => readAssistantSession());
  const [selectedCuratedBuildId, setSelectedCuratedBuildId] = useState<string | null>(null);
  const [selectedAiBuildId, setSelectedAiBuildId] = useState<string | null>(null);
  const [applyingBuildId, setApplyingBuildId] = useState<string | null>(null);
  const [applyError, setApplyError] = useState<string | null>(null);
  const [showHomeChoicePrompt, setShowHomeChoicePrompt] = useState(() => shouldShowHomeLoginChoice(skipHomeChoicePromptRef.current));
  const [showAiFlowChoicePrompt, setShowAiFlowChoicePrompt] = useState(false);
  const [neverShowHomeChoice, setNeverShowHomeChoice] = useState(false);
  useLockedPageScroll(showHomeChoicePrompt || showAiFlowChoicePrompt);

  const categoryPartQueries = useQueries({
    queries: PART_CATEGORIES.map((category) => ({
      queryKey: ['parts', 'modern-home-curated', category.value],
      queryFn: () => listParts({ category: category.value, status: 'ACTIVE', page: 0, size: 3, sort: 'price_desc' as const }),
      staleTime: 60_000
    }))
  });

  const curatedPreviewItems = useMemo<HomeFeaturedBuildPreviewItem[]>(() => (
    CURATED_BUILD_TEMPLATES.map((template, buildIndex) => {
      const buildParts = PART_CATEGORIES.map((category, categoryIndex) => {
        const page = categoryPartQueries[categoryIndex]?.data as PartPage | undefined;
        const candidates = page?.items ?? [];
        const part = candidates[buildIndex] ?? candidates[0];
        if (!part) return null;
        return {
          search: { category: category.value, searchQuery: part.name },
          part
        };
      })
        .filter((item): item is NonNullable<typeof item> => Boolean(item))
        .sort((left, right) => BUILD_CATEGORY_ORDER.indexOf(left.search.category) - BUILD_CATEGORY_ORDER.indexOf(right.search.category));
      const casePart = buildParts.find((item) => item.search.category === 'CASE')?.part;
      const isComplete = buildParts.length === PART_CATEGORIES.length;
      return {
        build: {
          ...template,
          partSearches: BUILD_CATEGORY_ORDER.map((category) => ({
            category,
            searchQuery: PART_CATEGORIES.find((item) => item.value === category)?.label ?? category
          }))
        },
        buildParts,
        casePart,
        assetTotalPrice: isComplete
          ? buildParts.reduce((sum, item) => sum + item.part.price, 0)
          : null
      };
    })
  ), [categoryPartQueries]);

  const latestHomeAiBuilds = useMemo(
    () => recentBuildsForChatContext(assistantSession),
    [assistantSession]
  );
  const selectedCuratedBuild = selectedCuratedBuildId
    ? curatedPreviewItems.find((item) => item.build.id === selectedCuratedBuildId) ?? null
    : null;
  const selectedAiBuild = selectedAiBuildId
    ? latestHomeAiBuilds.find((build) => build.id === selectedAiBuildId) ?? null
    : null;

  const aiImageQueries = useQueries({
    queries: latestHomeAiBuilds.map((build) => {
      const imageItem = build.items.find((item) => item.category === 'CASE')
        ?? build.items.find((item) => item.category === 'GPU')
        ?? build.items[0];
      return {
        queryKey: ['parts', 'modern-home-ai-image', build.id, imageItem?.partId ?? 'none'],
        queryFn: () => getPart(imageItem!.partId),
        enabled: Boolean(imageItem?.partId),
        staleTime: 60_000
      };
    })
  });

  const aiPreviewItems = latestHomeAiBuilds.map((build, index) => ({
    build,
    imagePart: (aiImageQueries[index]?.data as PartRow | undefined) ?? null
  }));

  const curatedGraphItems = selectedCuratedBuild?.buildParts.map(({ search, part }) => ({
    partId: part.id,
    category: search.category,
    quantity: 1
  })) ?? [];
  const curatedGraphSignature = curatedGraphItems.map((item) => `${item.category}:${item.partId}`).sort().join('|');
  const curatedGraphQuery = useQuery({
    queryKey: ['build-graph', 'modern-home-curated', selectedCuratedBuild?.build.id, curatedGraphSignature],
    queryFn: () => resolveBuildGraph({
      source: 'AI_BUILD',
      view: 'FOCUSED',
      budgetWon: selectedCuratedBuild?.assetTotalPrice ?? undefined,
      items: curatedGraphItems
    }),
    enabled: Boolean(selectedCuratedBuild && curatedGraphItems.length === PART_CATEGORIES.length),
    staleTime: 30_000
  });

  const aiGraphItems = selectedAiBuild?.items.map((item) => ({
    partId: item.partId,
    category: item.category,
    quantity: item.quantity
  })) ?? [];
  const aiGraphSignature = aiGraphItems.map((item) => `${item.category}:${item.partId}:${item.quantity}`).sort().join('|');
  const aiGraphQuery = useQuery({
    queryKey: ['build-graph', 'modern-home-ai', selectedAiBuild?.id, aiGraphSignature],
    queryFn: () => resolveBuildGraph({
      source: 'AI_BUILD',
      view: 'FOCUSED',
      budgetWon: selectedAiBuild?.budgetWon,
      items: aiGraphItems
    }),
    enabled: Boolean(selectedAiBuild && aiGraphItems.length > 0),
    staleTime: 30_000
  });

  useEffect(() => {
    const syncSession = () => setAssistantSession(readAssistantSession());
    window.addEventListener(AI_ASSISTANT_SESSION_CHANGED_EVENT, syncSession);
    window.addEventListener('storage', syncSession);
    return () => {
      window.removeEventListener(AI_ASSISTANT_SESSION_CHANGED_EVENT, syncSession);
      window.removeEventListener('storage', syncSession);
    };
  }, []);

  useEffect(() => {
    if (searchParams.get('assistant') !== 'open') return;
    skipHomeChoicePromptRef.current = true;
    setShowHomeChoicePrompt(false);
    setShowAiFlowChoicePrompt(false);
    const timer = window.setTimeout(() => openAiAssistant({ placement: 'side' }), 0);
    return () => window.clearTimeout(timer);
  }, [searchParams]);

  useEffect(() => {
    const syncHomeChoicePrompt = () => {
      if (skipHomeChoicePromptRef.current || isAiAssistantOpen()) {
        setShowHomeChoicePrompt(false);
        return;
      }
      setNeverShowHomeChoice(false);
      setShowHomeChoicePrompt(shouldShowHomeLoginChoice(false));
    };

    const closeChoicesForAiAssistant = (event: Event) => {
      const detail = event instanceof CustomEvent ? event.detail as AiAssistantVisibilityDetail | undefined : undefined;
      if (!detail?.open) return;
      setShowHomeChoicePrompt(false);
      setShowAiFlowChoicePrompt(false);
    };

    syncHomeChoicePrompt();
    window.addEventListener(AUTH_CHANGED_EVENT, syncHomeChoicePrompt);
    window.addEventListener('storage', syncHomeChoicePrompt);
    window.addEventListener(AI_BUILD_ASSISTANT_VISIBILITY_CHANGED_EVENT, closeChoicesForAiAssistant);
    return () => {
      window.removeEventListener(AUTH_CHANGED_EVENT, syncHomeChoicePrompt);
      window.removeEventListener('storage', syncHomeChoicePrompt);
      window.removeEventListener(AI_BUILD_ASSISTANT_VISIBILITY_CHANGED_EVENT, closeChoicesForAiAssistant);
    };
  }, []);

  useEffect(() => {
    if (selectedAiBuildId && !latestHomeAiBuilds.some((build) => build.id === selectedAiBuildId)) {
      setSelectedAiBuildId(null);
    }
  }, [latestHomeAiBuilds, selectedAiBuildId]);

  async function applyCuratedBuild(build: CuratedBuild, buildParts: HomeFeaturedBuildPreviewItem['buildParts']) {
    if (applyingBuildId || buildParts.length !== PART_CATEGORIES.length) return;
    setApplyingBuildId(build.id);
    setApplyError(null);
    try {
      const appliedDraft = await applyAiBuildToQuoteDraft({
        buildId: build.id,
        conflictPolicy: 'REPLACE',
        items: buildParts.map(({ search, part }) => ({
          partId: part.id,
          category: search.category,
          quantity: 1
        }))
      });
      queryClient.setQueryData(['quote-draft', 'current'], appliedDraft);
      void queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] });
      navigate('/self-quote');
    } catch {
      setApplyError('추천 조합을 현재 견적에 담지 못했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setApplyingBuildId(null);
    }
  }

  async function applyAiBuild(build: AiRecommendedBuild) {
    if (applyingBuildId) return;
    const normalizedBuild = normalizeAiRecommendedBuild(build);
    setApplyingBuildId(normalizedBuild.id);
    setApplyError(null);
    try {
      const appliedDraft = await applyAiBuildToQuoteDraft({
        buildId: normalizedBuild.id,
        conflictPolicy: 'REPLACE',
        items: normalizedBuild.items.map((item) => ({
          partId: item.partId,
          category: item.category,
          quantity: item.quantity
        }))
      });
      saveSelectedAiBuild(normalizedBuild);
      queryClient.setQueryData(['quote-draft', 'current'], appliedDraft);
      void queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] });
      navigate('/self-quote');
    } catch {
      setApplyError('AI 추천 조합을 현재 견적에 담지 못했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setApplyingBuildId(null);
    }
  }

  function rememberHomeChoiceIfNeeded() {
    if (neverShowHomeChoice) {
      dismissHomeLoginChoice();
    }
  }

  function chooseAiBuildFlow() {
    rememberHomeChoiceIfNeeded();
    setShowHomeChoicePrompt(false);
    setShowAiFlowChoicePrompt(true);
  }

  function choosePartsFlow() {
    rememberHomeChoiceIfNeeded();
    setShowHomeChoicePrompt(false);
    setShowAiFlowChoicePrompt(false);
    navigate('/parts');
  }

  function chooseCenteredAiAssistant() {
    setShowAiFlowChoicePrompt(false);
    openAiAssistant({ placement: 'center' });
  }

  function chooseSelfQuoteFlow() {
    setShowAiFlowChoicePrompt(false);
    navigate('/self-quote');
  }

  function chooseAllPartsFlow() {
    setShowAiFlowChoicePrompt(false);
    navigate('/parts');
  }

  function closeHomeChoicePrompt() {
    setShowHomeChoicePrompt(false);
  }

  function closeAiFlowChoicePrompt() {
    setShowAiFlowChoicePrompt(false);
  }

  function changeNeverShowHomeChoice(checked: boolean) {
    setNeverShowHomeChoice(checked);
    if (checked) {
      dismissHomeLoginChoice();
    } else {
      resetHomeLoginChoiceDismissed();
    }
  }

  return (
    <div className="screen-shell modern-home-screen min-h-screen">
      <AppHeader />
      <main id="main-content" className="modern-home-main mx-auto w-full max-w-[1320px] px-4 pb-20 pt-8 sm:px-6 lg:px-8 xl:px-0">
        <section className="modern-home-hero grid items-stretch gap-6 rounded-xl border border-slate-200 bg-white/80 p-4 sm:p-6 xl:grid-cols-[minmax(0,1fr)_420px]">
          <div className="modern-home-intro flex min-h-[560px] flex-col justify-center rounded-xl border border-blue-100 bg-[#f7fbff] p-6 sm:p-8 lg:p-12">
            <div className="max-w-[680px]">
              <div className="inline-flex items-center gap-2 text-sm font-bold text-brand-blue">
                <ShieldCheck size={18} aria-hidden="true" />
                견적 · 검증 · AS
              </div>
              <h1 className="mt-6 max-w-[14ch] text-3xl font-black leading-[1.08] tracking-[-0.03em] text-commerce-ink sm:text-4xl">
                견적부터 조립 후 AS까지, 한 흐름으로
              </h1>
              <p className="mt-5 text-sm font-medium text-slate-600 sm:text-base">예산과 용도만 말하면 시작됩니다.</p>

              <div data-testid="home-hero-process-flow" className="mt-8 flex flex-wrap items-center gap-2 sm:gap-3">
                <div className="inline-flex min-h-14 items-center gap-3 rounded-lg border border-slate-200 bg-white px-4 font-black text-commerce-ink">
                  <span className="grid h-8 w-8 place-items-center rounded-lg bg-blue-50 text-brand-blue"><Bot size={17} aria-hidden="true" /></span>
                  <span className="text-sm">AI 견적</span>
                </div>
                <ArrowRight size={16} className="text-slate-400" aria-hidden="true" />
                <div className="inline-flex min-h-14 items-center gap-3 rounded-lg border border-slate-200 bg-white px-4 font-black text-commerce-ink">
                  <span className="grid h-8 w-8 place-items-center rounded-lg bg-blue-50 text-brand-blue"><ShieldCheck size={17} aria-hidden="true" /></span>
                  <span className="text-sm">호환성 검증</span>
                </div>
                <ArrowRight size={16} className="text-slate-400" aria-hidden="true" />
                <div className="inline-flex min-h-14 items-center gap-3 rounded-lg border border-blue-200 bg-white px-4 font-black text-commerce-ink">
                  <span className="grid h-8 w-8 place-items-center rounded-lg bg-brand-blue text-white"><Wrench size={17} aria-hidden="true" /></span>
                  <span className="text-sm">조립 후 AS</span>
                </div>
              </div>

              <div className="mt-8 flex flex-col gap-3 sm:flex-row">
              <button
                type="button"
                onClick={() => openAiAssistant({ placement: 'side' })}
                className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-commerce-ink px-5 text-sm font-black text-white transition hover:bg-brand-blue focus:outline-none focus:ring-4 focus:ring-blue-100"
              >
                <Bot size={18} aria-hidden="true" />
                AI로 견적 만들기
              </button>
              <Link
                to="/self-quote"
                className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg border border-slate-300 bg-white px-5 text-sm font-black text-commerce-ink transition hover:border-commerce-ink focus:outline-none focus:ring-4 focus:ring-blue-100"
              >
                직접 구성하기
                <ArrowRight size={17} aria-hidden="true" />
              </Link>
              </div>
            </div>
          </div>

          <div className="order-first min-w-0 sm:order-none xl:w-[420px]">
            <HomeQuickStartPanel />
          </div>
        </section>

        <CategoryRail />

        <section className="modern-home-recommendations mt-16" aria-labelledby="home-recommendations-title">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <div className="flex items-center gap-2 text-sm font-bold text-brand-blue">
                <Sparkles size={17} aria-hidden="true" />
                {latestHomeAiBuilds.length ? '방금 나눈 대화로 새 추천을 만들었어요' : '내부 부품 데이터로 미리 검증했습니다'}
              </div>
              <h2 id="home-recommendations-title" className="mt-2 text-2xl font-black text-commerce-ink">검증된 추천 조합</h2>
              <p className="mt-2 text-base text-slate-600">가장 잘 맞는 조합을 먼저 보여드려요. 다른 후보로 바꾸면 무엇이 달라지는지도 함께 보여드릴게요.</p>
            </div>
            {latestHomeAiBuilds.length ? (
              <button
                type="button"
                onClick={() => openAiAssistant({ placement: 'side' })}
                className="inline-flex min-h-11 items-center gap-2 self-start rounded-lg border border-blue-200 bg-blue-50 px-4 text-sm font-black text-brand-blue hover:border-brand-blue focus:outline-none focus:ring-4 focus:ring-blue-100 sm:self-auto"
              >
                AI와 조건 더 다듬기
              </button>
            ) : null}
          </div>

          {applyError ? (
            <div role="alert" className="mt-5 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm font-bold text-red-700">
              {applyError}
            </div>
          ) : null}

          <div className="mt-6">
            {latestHomeAiBuilds.length ? (
              <div data-testid="home-ai-recommendations" className="home-ai-recommendations">
                <HomeAiBuildPreview
                  items={aiPreviewItems}
                  selectedBuildId={selectedAiBuild?.id ?? null}
                  applyingBuildId={applyingBuildId}
                  graph={aiGraphQuery.data}
                  isGraphLoading={aiGraphQuery.isLoading}
                  isGraphError={aiGraphQuery.isError}
                  onSelectBuild={setSelectedAiBuildId}
                  onClearSelection={() => setSelectedAiBuildId(null)}
                  onApplyBuild={applyAiBuild}
                  onImageError={handleProductImageError}
                />
              </div>
            ) : (
              <HomeFeaturedBuildPreview
                items={curatedPreviewItems}
                selectedBuildId={selectedCuratedBuild?.build.id ?? null}
                applyingBuildId={applyingBuildId}
                graph={curatedGraphQuery.data}
                isGraphLoading={curatedGraphQuery.isLoading}
                isGraphError={curatedGraphQuery.isError}
                onSelectBuild={setSelectedCuratedBuildId}
                onClearSelection={() => setSelectedCuratedBuildId(null)}
                onApplyBuild={applyCuratedBuild}
                onImageError={handleProductImageError}
              />
            )}
          </div>
        </section>

        <PopularPartsSection />
        <EvidenceSection />
      </main>
      {showHomeChoicePrompt ? (
        <HomeLoginChoiceDialog
          neverShow={neverShowHomeChoice}
          onNeverShowChange={changeNeverShowHomeChoice}
          onChooseAi={chooseAiBuildFlow}
          onChooseParts={choosePartsFlow}
          onClose={closeHomeChoicePrompt}
        />
      ) : null}
      {showAiFlowChoicePrompt ? (
        <HomeAiFlowChoiceDialog
          onChooseAi={chooseCenteredAiAssistant}
          onChooseSelfQuote={chooseSelfQuoteFlow}
          onChooseAllParts={chooseAllPartsFlow}
          onClose={closeAiFlowChoicePrompt}
        />
      ) : null}
    </div>
  );
}

type HomeLoginChoiceDialogProps = {
  neverShow: boolean;
  onNeverShowChange: (checked: boolean) => void;
  onChooseAi: () => void;
  onChooseParts: () => void;
  onClose: () => void;
};

function HomeLoginChoiceDialog({
  neverShow,
  onNeverShowChange,
  onChooseAi,
  onChooseParts,
  onClose
}: HomeLoginChoiceDialogProps) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 px-4 py-6"
      role="presentation"
      onWheel={(event) => event.stopPropagation()}
      onTouchMove={(event) => event.stopPropagation()}
    >
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="home-login-choice-title"
        aria-describedby="home-login-choice-description"
        data-testid="home-login-choice-dialog"
        className="relative w-full max-w-[760px]"
      >
        <button
          type="button"
          aria-label="선택지 닫기"
          onClick={onClose}
          className="absolute -top-12 right-0 grid h-10 w-10 place-items-center rounded-full bg-white text-slate-600 shadow-lg transition hover:bg-slate-100 hover:text-commerce-ink focus:outline-none focus:ring-4 focus:ring-blue-100"
        >
          <X size={18} aria-hidden="true" />
        </button>
        <div className="mx-auto flex max-w-[620px] items-start gap-3 rounded-2xl bg-white p-6 shadow-lg sm:p-7">
          <div className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-commerce-ink text-white" aria-hidden="true">
            <Bot size={23} strokeWidth={2.3} />
          </div>
          <div>
            <h2 id="home-login-choice-title" className="text-xl font-black leading-7 text-commerce-ink">
              어떤 방식으로 PC를 맞춰볼까요?
            </h2>
            <p id="home-login-choice-description" className="mt-1 break-keep text-sm leading-6 text-slate-600">
              처음이라면 AI 추천으로 빠르게 시작하거나, 전체 부품 목록에서 직접 비교할 수 있습니다.
            </p>
          </div>
        </div>

        <div className="mt-5 grid gap-4 sm:grid-cols-2">
          <button
            type="button"
            autoFocus
            onClick={onChooseAi}
            data-testid="home-login-choice-ai"
            className="group flex min-h-[178px] flex-col items-start rounded-2xl bg-white p-5 text-left shadow-lg transition hover:-translate-y-0.5 hover:bg-blue-50 focus:outline-none focus:ring-4 focus:ring-blue-100"
          >
            <span className="grid h-11 w-11 place-items-center rounded-lg bg-brand-blue text-white transition group-hover:bg-blue-700" aria-hidden="true">
              <Bot size={22} strokeWidth={2.3} />
            </span>
            <span className="mt-4 text-base font-black text-commerce-ink">AI로 부품 맞춰보기</span>
            <span className="mt-1 break-keep text-sm leading-5 text-slate-600">예산과 용도를 말하면 추천 조합을 바로 만들어줍니다.</span>
          </button>

          <button
            type="button"
            onClick={onChooseParts}
            data-testid="home-login-choice-parts"
            className="group flex min-h-[178px] flex-col items-start rounded-2xl bg-white p-5 text-left shadow-lg transition hover:-translate-y-0.5 hover:bg-emerald-50 focus:outline-none focus:ring-4 focus:ring-emerald-100"
          >
            <span className="grid h-11 w-11 place-items-center rounded-lg bg-emerald-600 text-white transition group-hover:bg-emerald-700" aria-hidden="true">
              <Boxes size={22} strokeWidth={2.3} />
            </span>
            <span className="mt-4 text-base font-black text-commerce-ink">부품 보러가기</span>
            <span className="mt-1 break-keep text-sm leading-5 text-slate-600">전체 부품 페이지에서 카테고리와 가격을 직접 살펴봅니다.</span>
          </button>
        </div>

        <label className="mx-auto mt-4 flex w-fit items-center gap-2 rounded-2xl bg-white px-4 py-3 text-sm font-bold text-slate-700 shadow-lg">
          <input
            type="checkbox"
            checked={neverShow}
            onChange={(event) => onNeverShowChange(event.currentTarget.checked)}
            className="h-4 w-4 accent-blue-600"
          />
          다시는 표시하지 않기
        </label>
      </section>
    </div>
  );
}

type HomeAiFlowChoiceDialogProps = {
  onChooseAi: () => void;
  onChooseSelfQuote: () => void;
  onChooseAllParts: () => void;
  onClose: () => void;
};

function HomeAiFlowChoiceDialog({
  onChooseAi,
  onChooseSelfQuote,
  onChooseAllParts,
  onClose
}: HomeAiFlowChoiceDialogProps) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 px-4 py-6"
      role="presentation"
      onWheel={(event) => event.stopPropagation()}
      onTouchMove={(event) => event.stopPropagation()}
    >
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="home-ai-flow-choice-title"
        aria-describedby="home-ai-flow-choice-description"
        data-testid="home-ai-flow-choice-dialog"
        className="relative w-full max-w-[940px]"
      >
        <button
          type="button"
          aria-label="선택지 닫기"
          onClick={onClose}
          className="absolute -top-12 right-0 grid h-10 w-10 place-items-center rounded-full bg-white text-slate-600 shadow-lg transition hover:bg-slate-100 hover:text-commerce-ink focus:outline-none focus:ring-4 focus:ring-blue-100"
        >
          <X size={18} aria-hidden="true" />
        </button>
        <div className="mx-auto flex max-w-[620px] items-start gap-3 rounded-2xl bg-white p-6 shadow-lg sm:p-7">
          <div className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-brand-blue text-white" aria-hidden="true">
            <Bot size={23} strokeWidth={2.3} />
          </div>
          <div>
            <h2 id="home-ai-flow-choice-title" className="text-xl font-black leading-7 text-commerce-ink">
              견적을 어떤 방식으로 시작할까요?
            </h2>
            <p id="home-ai-flow-choice-description" className="mt-1 break-keep text-sm leading-6 text-slate-600">
              AI에게 바로 맡기거나, 직접 담아보면서 비교할 수 있습니다.
            </p>
          </div>
        </div>

        <div className="mt-5 grid gap-4 md:grid-cols-3">
          <button
            type="button"
            autoFocus
            onClick={onChooseAi}
            data-testid="home-ai-flow-choice-ai"
            className="group flex min-h-[178px] flex-col items-start rounded-2xl bg-white p-5 text-left shadow-lg transition hover:-translate-y-0.5 hover:bg-blue-50 focus:outline-none focus:ring-4 focus:ring-blue-100"
          >
            <span className="grid h-11 w-11 place-items-center rounded-lg bg-brand-blue text-white transition group-hover:bg-blue-700" aria-hidden="true">
              <Bot size={22} strokeWidth={2.3} />
            </span>
            <span className="mt-4 text-base font-black text-commerce-ink">AI로 맞춰보기</span>
            <span className="mt-1 break-keep text-sm leading-5 text-slate-600">예산과 용도를 말하면 홈 채팅에서 추천을 시작합니다.</span>
          </button>

          <button
            type="button"
            onClick={onChooseSelfQuote}
            data-testid="home-ai-flow-choice-self-quote"
            className="group flex min-h-[178px] flex-col items-start rounded-2xl bg-white p-5 text-left shadow-lg transition hover:-translate-y-0.5 hover:bg-slate-50 focus:outline-none focus:ring-4 focus:ring-blue-100"
          >
            <span className="grid h-11 w-11 place-items-center rounded-lg bg-commerce-ink text-white transition group-hover:bg-slate-700" aria-hidden="true">
              <FileText size={22} strokeWidth={2.3} />
            </span>
            <span className="mt-4 text-base font-black text-commerce-ink">셀프견적</span>
            <span className="mt-1 break-keep text-sm leading-5 text-slate-600">견적판에서 부품을 직접 담고 조합을 확인합니다.</span>
          </button>

          <button
            type="button"
            onClick={onChooseAllParts}
            data-testid="home-ai-flow-choice-all-parts"
            className="group flex min-h-[178px] flex-col items-start rounded-2xl bg-white p-5 text-left shadow-lg transition hover:-translate-y-0.5 hover:bg-emerald-50 focus:outline-none focus:ring-4 focus:ring-emerald-100"
          >
            <span className="grid h-11 w-11 place-items-center rounded-lg bg-emerald-600 text-white transition group-hover:bg-emerald-700" aria-hidden="true">
              <Boxes size={22} strokeWidth={2.3} />
            </span>
            <span className="mt-4 text-base font-black text-commerce-ink">전체부품</span>
            <span className="mt-1 break-keep text-sm leading-5 text-slate-600">전체 부품 목록에서 카테고리별로 먼저 살펴봅니다.</span>
          </button>
        </div>
      </section>
    </div>
  );
}

function CategoryRail() {
  return (
    <nav aria-label="PC 부품 카테고리" className="modern-home-category-rail mt-6 overflow-x-auto rounded-xl border border-commerce-line bg-white">
      <div className="grid min-w-[760px] grid-cols-8">
        {PART_CATEGORIES.map(({ value, label, Icon }, index) => (
          <Link
            key={value}
            to={`/parts?category=${value}`}
            aria-label={label}
            className={`flex min-h-[88px] flex-col items-center justify-center gap-2 px-3 text-sm font-bold text-commerce-ink transition hover:bg-blue-50 hover:text-brand-blue focus:outline-none focus:ring-4 focus:ring-inset focus:ring-blue-100 ${index > 0 ? 'border-l border-commerce-line' : ''}`}
          >
            <Icon size={25} strokeWidth={1.8} aria-hidden="true" />
            <span>{label}</span>
          </Link>
        ))}
      </div>
    </nav>
  );
}

function PopularPartsSection() {
  const impressedIdsRef = useRef(new Set<string>());
  const partsQuery = useQuery({
    queryKey: ['recommendations', 'modern-home-parts', 8],
    queryFn: () => listHomeRecommendedParts(8),
    staleTime: 60_000
  });

  useEffect(() => {
    for (const item of partsQuery.data?.items ?? []) {
      if (impressedIdsRef.current.has(item.recommendationId)) continue;
      impressedIdsRef.current.add(item.recommendationId);
      void recordRecommendationEvent({
        eventType: 'IMPRESSION',
        sourceSurface: 'HOME_RECOMMENDED_PARTS',
        recommendationId: item.recommendationId,
        partId: item.part.id,
        category: item.part.category,
        rankPosition: item.rankPosition,
        idempotencyKey: `home-impression-${item.recommendationId}`
      }).catch(() => undefined);
    }
  }, [partsQuery.data]);

  return (
    <section data-testid="home-parts-section" className="mt-16" aria-labelledby="popular-parts-title">
      <div className="flex items-end justify-between gap-4">
        <div>
          <h2 id="popular-parts-title" className="text-2xl font-black text-commerce-ink">추천하는 부품</h2>
        </div>
        <Link to="/parts" className="hidden min-h-11 items-center gap-1 text-sm font-black text-brand-blue hover:underline sm:inline-flex">
          전체 부품 보기
          <ArrowRight size={16} aria-hidden="true" />
        </Link>
      </div>

      {partsQuery.isLoading ? (
        <div className="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-4 lg:grid-cols-6" aria-label="추천 부품 불러오는 중">
          {Array.from({ length: 6 }, (_, index) => (
            <div key={index} className="h-64 animate-pulse rounded-lg bg-slate-200" />
          ))}
        </div>
      ) : null}
      {partsQuery.isError ? (
        <div role="alert" className="mt-6 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm font-bold text-amber-800">
          인기 부품을 불러오지 못했습니다. 전체 부품에서 다시 확인해 주세요.
        </div>
      ) : null}
      {partsQuery.data?.items.length ? (
        <div className="modern-home-product-rail mt-6 flex gap-4 overflow-x-auto pb-3">
          {partsQuery.data.items.map((item) => (
            <PopularPartCard key={item.recommendationId} item={item} />
          ))}
        </div>
      ) : null}
    </section>
  );
}

function PopularPartCard({ item }: { item: HomeRecommendedPart }) {
  const href = `/parts/${item.part.id}?recId=${encodeURIComponent(item.recommendationId)}&recSurface=HOME_RECOMMENDED_PARTS&rank=${item.rankPosition}`;
  const reason = item.reasonTags?.includes('benchmark') ? '벤치마크 점수 포함' : '내부 데이터 추천';
  return (
    <Link
      to={href}
      aria-label={`인기 부품 ${item.rankPosition + 1}번 보기`}
      onClick={() => {
        void recordRecommendationEvent({
          eventType: 'CLICK',
          sourceSurface: 'HOME_RECOMMENDED_PARTS',
          recommendationId: item.recommendationId,
          partId: item.part.id,
          category: item.part.category,
          rankPosition: item.rankPosition,
          idempotencyKey: `home-click-${item.recommendationId}`
        }).catch(() => undefined);
      }}
      className="group w-[210px] shrink-0 rounded-lg focus:outline-none focus:ring-4 focus:ring-blue-100"
    >
      <div className="grid h-[176px] place-items-center overflow-hidden rounded-lg border border-commerce-line bg-white p-3 transition group-hover:border-slate-400">
        <img
          src={partImageUrl(item.part)}
          alt={`${item.part.name} 제품 사진`}
          loading="lazy"
          onError={handleProductImageError}
          className="h-full w-full object-contain transition duration-200 group-hover:scale-[1.03]"
        />
      </div>
      <div className="pt-3">
        <div className="text-xs font-bold text-brand-blue">{item.part.category}</div>
        <h3 className="mt-1 line-clamp-2 min-h-10 text-sm font-black leading-5 text-commerce-ink">{item.part.name}</h3>
        <div className="mt-2 text-base font-black tabular-nums text-commerce-ink">{item.part.price.toLocaleString()}원</div>
        <div className="mt-2 flex items-center justify-between gap-2 text-xs text-slate-500">
          <span>{reason}</span>
          <span className="font-bold text-brand-blue">상품 정보 확인</span>
        </div>
      </div>
    </Link>
  );
}

function EvidenceSection() {
  return (
    <section className="modern-home-evidence mt-16 rounded-xl border border-commerce-line bg-[#f8fbff] px-5 py-6 sm:px-8" aria-labelledby="home-evidence-title">
      <div className="grid gap-6 lg:grid-cols-[minmax(220px,0.8fr)_minmax(0,2.2fr)] lg:items-center">
        <div>
          <div className="text-sm font-bold text-brand-blue">다짜줘의 검증 원칙</div>
          <h2 id="home-evidence-title" className="mt-2 text-xl font-black text-commerce-ink">추천 결과보다 근거를 먼저 봅니다</h2>
          <p className="mt-3 text-sm leading-6 text-slate-600">정확한 성능이나 최저가를 보장하지 않고, 현재 확인 가능한 데이터와 주의점을 함께 제공합니다.</p>
        </div>
        <div className="grid gap-5 sm:grid-cols-3 sm:divide-x sm:divide-commerce-line">
          <EvidenceItem title="공식 사양" body="제조사 규격과 현재 등록된 부품 속성을 대조합니다." />
          <EvidenceItem title="검증 도구" body="호환성, 전력, 장착 규격, 성능 범위, 가격을 분리해 확인합니다." />
          <EvidenceItem title="신뢰도 표시" body="근거가 부족하거나 주의가 필요하면 경고 상태로 명확히 표시합니다." />
        </div>
      </div>
    </section>
  );
}

function EvidenceItem({ title, body }: { title: string; body: string }) {
  return (
    <div className="sm:px-5 first:pl-0 last:pr-0">
      <div className="text-sm font-black text-commerce-ink">{title}</div>
      <p className="mt-2 text-sm leading-6 text-slate-600">{body}</p>
    </div>
  );
}

function handleProductImageError(event: SyntheticEvent<HTMLImageElement>) {
  const image = event.currentTarget;
  if (image.dataset.fallback === 'true') return;
  image.dataset.fallback = 'true';
  image.src = FALLBACK_PRODUCT_IMAGE;
}
