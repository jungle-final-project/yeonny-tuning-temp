import { useEffect, useMemo, useRef, useState, type SyntheticEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
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
  X,
  Zap,
  type LucideIcon
} from 'lucide-react';
import { AppHeader } from '../../../components/ui';
import { useLockedPageScroll } from '../../../hooks/useHiddenPageScrollbar';
import { AUTH_CHANGED_EVENT, getToken } from '../../../lib/api';
import {
  AI_BUILD_ASSISTANT_VISIBILITY_CHANGED_EVENT,
  isAiAssistantOpen,
  openAiAssistant,
  type AiAssistantVisibilityDetail
} from '../../../lib/events';
import { partImageUrl } from '../../parts/partDisplay';
import {
  applyAiBuildToQuoteDraft,
  getHome,
  getPublicHome,
  queueRecommendationEventsBulk,
  recordRecommendationEvent,
} from '../../parts/partsApi';
import type { HomeRecommendedPart, RecommendationEventRequest } from '../../parts/types';
import { type PartCategory } from '../aiSelection';
import {
  HomeFeaturedBuildPreview,
  type HomeFeaturedBuildPreviewItem
} from '../components/HomeFeaturedBuildPreview';
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
  },
  {
    id: 'home-featured-budget-starter',
    name: '가성비 입문 추천팩',
    tag: '입문',
    spec: 'FHD 게임 · 문서 작업 · 합리적 예산',
    summary: '합리적인 예산으로 일상 작업과 FHD 게임을 시작하기 좋은 구성입니다.',
    tone: 'from-white via-white to-white'
  }
];

const FALLBACK_PRODUCT_IMAGE = '/assets/home-banners/pc-build-festa.png';
const HERO_DUMMY_IMAGES = [
  '/assets/home-hero/motherboard-editorial.jpg',
  '/assets/home-hero/cpu-chip-illustration.jpg',
  '/assets/home-hero/cpu-collection.jpg',
  '/assets/home-hero/gpu-illustration.jpg',
  '/assets/home-hero/cooling-fan-illustration.jpg',
  '/assets/home-hero/mouse-editorial.jpg',
  '/assets/home-hero/pc-case-illustration.jpg',
  '/assets/home-hero/motherboard-editorial.jpg',
  '/assets/home-hero/mouse-editorial.jpg',
  '/assets/home-hero/cooling-fan-illustration.jpg',
  '/assets/home-hero/gpu-illustration.jpg',
  '/assets/home-hero/pc-case-illustration.jpg'
] as const;
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
  const isAuthenticated = Boolean(getToken());
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();
  const skipHomeChoicePromptRef = useRef(searchParams.get('assistant') === 'open');
  const [selectedCuratedBuildId, setSelectedCuratedBuildId] = useState<string | null>(null);
  const [applyingBuildId, setApplyingBuildId] = useState<string | null>(null);
  const [applyError, setApplyError] = useState<string | null>(null);
  const [showHomeChoicePrompt, setShowHomeChoicePrompt] = useState(() => Boolean(getToken()) && shouldShowHomeLoginChoice(skipHomeChoicePromptRef.current));
  const [showAiFlowChoicePrompt, setShowAiFlowChoicePrompt] = useState(false);
  const [neverShowHomeChoice, setNeverShowHomeChoice] = useState(false);
  useLockedPageScroll(showHomeChoicePrompt || showAiFlowChoicePrompt);

  const publicHomeQuery = useQuery({
    queryKey: ['public-home'],
    queryFn: getPublicHome,
    enabled: !isAuthenticated,
    staleTime: 60_000
  });

  const homeQuery = useQuery({
    queryKey: ['home', 'authenticated'],
    queryFn: getHome,
    enabled: isAuthenticated,
    staleTime: 60_000
  });

  const homeData = isAuthenticated ? homeQuery.data : publicHomeQuery.data;

  const curatedPreviewItems = useMemo<HomeFeaturedBuildPreviewItem[]>(() => (
    CURATED_BUILD_TEMPLATES.map((template, buildIndex) => {
      const buildParts = PART_CATEGORIES.map((category) => {
        const candidates = homeData?.categoryParts[category.value] ?? [];
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
  ), [homeData]);

  const selectedCuratedBuild = selectedCuratedBuildId
    ? curatedPreviewItems.find((item) => item.build.id === selectedCuratedBuildId) ?? null
    : null;
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
    enabled: isAuthenticated && Boolean(selectedCuratedBuild && curatedGraphItems.length === PART_CATEGORIES.length),
    staleTime: 30_000
  });


  useEffect(() => {
    if (searchParams.get('assistant') !== 'open') return;
    if (!isAuthenticated) {
      navigate(`/login?redirect=${encodeURIComponent('/?assistant=open')}`, { replace: true });
      return;
    }
    skipHomeChoicePromptRef.current = true;
    setShowHomeChoicePrompt(false);
    setShowAiFlowChoicePrompt(false);
    const timer = window.setTimeout(() => openAiAssistant({ placement: 'side' }), 0);
    return () => window.clearTimeout(timer);
  }, [isAuthenticated, navigate, searchParams]);

  useEffect(() => {
    const syncHomeChoicePrompt = () => {
      if (!getToken() || skipHomeChoicePromptRef.current || isAiAssistantOpen()) {
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


  async function applyCuratedBuild(build: CuratedBuild, buildParts: HomeFeaturedBuildPreviewItem['buildParts']) {
    if (!isAuthenticated) {
      navigate(`/login?redirect=${encodeURIComponent('/')}`);
      return;
    }
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
    if (!isAuthenticated) {
      navigate(`/login?redirect=${encodeURIComponent('/?assistant=open')}`);
      return;
    }
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
      <main id="main-content" className="modern-home-main w-full bg-[#f7f7f8] pb-20">
        <section className="modern-home-hero relative w-full overflow-hidden border-b border-commerce-line bg-white p-4 sm:p-6">
          <div data-testid="home-hero-dummy-collage" className="modern-home-hero-collage pointer-events-none absolute inset-0 z-0" aria-hidden="true">
            {HERO_DUMMY_IMAGES.map((src, index) => (
              <img
                key={index}
                src={src}
                alt=""
                draggable={false}
                className={'modern-home-hero-dummy modern-home-hero-dummy-' + (index + 1)}
              />
            ))}
          </div>
          <div
            data-testid="home-hero-gradient-layer"
            className="pointer-events-none absolute inset-0 z-10 bg-gradient-to-b from-[#fbeee7] to-white opacity-60"
            aria-hidden="true"
          />
          <div className="modern-home-intro relative z-20 flex min-h-[364px] flex-col items-center justify-center rounded-xl border border-transparent bg-transparent p-6 text-center sm:p-8 lg:p-12">
            <div className="mx-auto max-w-[860px]">
              <h1 className="text-[48px] font-black leading-[1.08] tracking-[-0.03em] text-commerce-ink sm:text-[58px]">
                견적부터 조립 후 AS까지,
                <br />
                <span className="text-[#de6c2d]">한 흐름으로</span>
              </h1>
              <p className="mx-auto mt-6 max-w-[900px] text-[17px] font-normal leading-relaxed text-slate-600 sm:text-[19px]">
                당신이 원하는 맞춤형 PC를 가상으로 견적을 맞추고,
                <br />
                조립 및 AS 기사까지 매칭되는 올인원 플랫폼입니다.
              </p>
              <Link
                to="/self-quote"
                className="mt-[22px] inline-flex min-h-[36px] items-center justify-center rounded-full bg-[#de6c2d] px-[22px] text-sm font-bold text-white transition hover:bg-[#c45c22] focus:outline-none focus:ring-4 focus:ring-blue-100"
              >
                나만의 견적 알아보기
              </Link>
            </div>
          </div>
        </section>

        <div className="mx-auto w-full max-w-[1320px] px-4 sm:px-6 lg:px-8 xl:px-0">
          <section className="modern-home-recommendations mt-16" aria-labelledby="home-recommendations-title">
          <div className="flex justify-center text-center">
            <div>
              <h2 id="home-recommendations-title" className="text-2xl font-bold leading-tight text-commerce-ink">인기있는 조합</h2>
            </div>
          </div>

          {applyError ? (
            <div role="alert" className="mt-5 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm font-bold text-red-700">
              {applyError}
            </div>
          ) : null}

          <div className="mt-6">
            <HomeFeaturedBuildPreview
              items={curatedPreviewItems}
              selectedBuildId={selectedCuratedBuild?.build.id ?? null}
              applyingBuildId={applyingBuildId}
              graph={curatedGraphQuery.data}
              isGraphLoading={curatedGraphQuery.isLoading}
              isGraphError={curatedGraphQuery.isError}
              onSelectBuild={(buildId) => {
                if (!isAuthenticated) {
                  navigate('/login?redirect=' + encodeURIComponent('/'));
                  return;
                }
                setSelectedCuratedBuildId(buildId);
              }}
              onClearSelection={() => setSelectedCuratedBuildId(null)}
              onApplyBuild={applyCuratedBuild}
              onImageError={handleProductImageError}
            />
          </div>
        </section>

          <PopularPartsSection
            isAuthenticated={isAuthenticated}
            items={homeData?.recommendedParts.items ?? []}
            loading={isAuthenticated ? homeQuery.isLoading : publicHomeQuery.isLoading}
            error={isAuthenticated ? homeQuery.isError : publicHomeQuery.isError}
          />
        </div>
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
        className="relative w-full max-w-[760px] scale-[1.2]"
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
        className="relative w-full max-w-[940px] scale-[1.2]"
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

type PopularPartsSectionProps = {
  isAuthenticated: boolean;
  items: HomeRecommendedPart[];
  loading: boolean;
  error: boolean;
};

function PopularPartsSection({ isAuthenticated, items, loading, error }: PopularPartsSectionProps) {
  const impressedIdsRef = useRef(new Set<string>());
  const visibleParts = items.slice(0, 5);

  useEffect(() => {
    if (!isAuthenticated) return;
    const events: RecommendationEventRequest[] = [];
    for (const item of items.slice(0, 5)) {
      if (impressedIdsRef.current.has(item.recommendationId)) continue;
      impressedIdsRef.current.add(item.recommendationId);
      events.push({
        eventType: 'IMPRESSION',
        sourceSurface: 'HOME_RECOMMENDED_PARTS',
        recommendationId: item.recommendationId,
        partId: item.part.id,
        category: item.part.category,
        rankPosition: item.rankPosition,
        idempotencyKey: `home-impression-${item.recommendationId}`
      });
    }
    if (events.length) {
      void queueRecommendationEventsBulk({ events }).catch(() => undefined);
    }
  }, [isAuthenticated, items]);

  return (
    <section data-testid="home-parts-section" className="mt-16" aria-labelledby="popular-parts-title">
      <div className="relative flex items-end justify-center gap-4">
        <div>
          <h2 id="popular-parts-title" className="text-2xl font-bold leading-tight text-commerce-ink">맞춤형 부품</h2>
        </div>
        <Link
          to="/parts"
          className="absolute right-0 hidden items-center self-end text-xs font-semibold leading-tight tracking-[0.16em] text-slate-500 transition-colors hover:text-commerce-ink focus:outline-none focus:ring-2 focus:ring-slate-300 sm:inline-flex"
        >
          MORE {'>'}
        </Link>
      </div>

      {loading ? (
        <div className="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5" aria-label="추천 부품 불러오는 중">
          {Array.from({ length: 5 }, (_, index) => (
            <div key={index} className="aspect-square animate-pulse rounded-[18px] bg-slate-200" />
          ))}
        </div>
      ) : null}
      {error ? (
        <div role="alert" className="mt-6 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm font-bold text-amber-800">
          인기 부품을 불러오지 못했습니다. 전체 부품에서 다시 확인해 주세요.
        </div>
      ) : null}
      {visibleParts.length ? (
        <div className="modern-home-product-rail mt-6 pb-3">
          {visibleParts.map((item) => (
            <PopularPartCard key={item.recommendationId} item={item} trackEvent={isAuthenticated} />
          ))}
        </div>
      ) : null}
    </section>
  );
}

function PopularPartCard({ item, trackEvent }: { item: HomeRecommendedPart; trackEvent: boolean }) {
  const href = `/parts/${item.part.id}?recId=${encodeURIComponent(item.recommendationId)}&recSurface=HOME_RECOMMENDED_PARTS&rank=${item.rankPosition}`;

  return (
    <Link
      to={href}
      aria-label={`인기 부품 ${item.rankPosition + 1}번 보기`}
      onClick={() => {
        if (!trackEvent) return;
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
      className="modern-home-product-card group min-w-0 rounded-lg focus:outline-none focus:ring-4 focus:ring-blue-100"
    >
      <div className="modern-home-product-image relative grid aspect-square place-items-center overflow-hidden rounded-[18px] bg-white p-3">
        <img
          src={partImageUrl(item.part)}
          alt={`${item.part.name} 제품 사진`}
          loading="lazy"
          onError={handleProductImageError}
          className="h-full w-full object-contain transition duration-200 group-hover:scale-[1.03]"
        />
        <span className="absolute bottom-2.5 left-2.5 z-10 rounded-full border-[0.7px] border-slate-400 bg-white px-2.5 py-1 text-[11px] font-medium leading-none text-black">
          {item.part.category}
        </span>
      </div>
      <div className="pt-3">
        <div className="mx-auto w-[80%] text-sm font-normal leading-5 text-commerce-ink">
          <h3 title={item.part.name} className="w-full truncate text-left">{item.part.name}</h3>
        </div>
        <div className="mx-auto mt-2 w-[80%] text-left text-base font-semibold tabular-nums text-commerce-ink">{item.part.price.toLocaleString()}원</div>
      </div>
    </Link>
  );
}


function handleProductImageError(event: SyntheticEvent<HTMLImageElement>) {
  const image = event.currentTarget;
  if (image.dataset.fallback === 'true') return;
  image.dataset.fallback = 'true';
  image.src = FALLBACK_PRODUCT_IMAGE;
}
