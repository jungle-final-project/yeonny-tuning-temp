import { useEffect, useRef, useState, type SyntheticEvent } from 'react';
import ReactFullpage from '@fullpage/react-fullpage';
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import 'fullpage.js/dist/fullpage.min.css';
import {
  Bot,
  Boxes,
  ChevronLeft,
  ChevronRight,
  FileText,
  LifeBuoy,
  PackageCheck,
  type LucideIcon
} from 'lucide-react';
import { AppHeader } from '../../../components/ui';
import { AUTH_CHANGED_EVENT } from '../../../lib/api';
import { openAiAssistant } from '../../../lib/events';
import { partImageUrl } from '../../parts/partDisplay';
import { applyAiBuildToQuoteDraft, getPart, listHomeRecommendedParts, listParts, recordRecommendationEvent } from '../../parts/partsApi';
import type { HomeRecommendedPart, PartRow } from '../../parts/types';
import { HomeAiBuildPreview } from '../components/HomeAiBuildPreview';
import { HomeFeaturedBuildPreview } from '../components/HomeFeaturedBuildPreview';
import {
  AI_ASSISTANT_SESSION_CHANGED_EVENT,
  clearLegacyAiStorage,
  normalizeAiRecommendedBuild,
  readAssistantSession,
  recentBuildsForChatContext,
  saveSelectedAiBuild,
  type AiAssistantSession,
  type AiRecommendedBuild,
  type PartCategory
} from '../aiSelection';
import { resolveBuildGraph } from '../quoteApi';

// 부품 이미지 로드 실패 시 보여줄 대체 이미지(partImageUrl placeholder와 동일 톤).
const PART_IMAGE_PLACEHOLDER = `data:image/svg+xml;utf8,${encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="112" height="112" viewBox="0 0 112 112"><rect width="112" height="112" rx="14" fill="#f8fafc"/><rect x="12" y="20" width="88" height="56" rx="10" fill="#334155" opacity="0.92"/><rect x="20" y="28" width="72" height="40" rx="6" fill="#ffffff" opacity="0.16"/><text x="56" y="54" text-anchor="middle" font-family="Arial, sans-serif" font-size="16" font-weight="700" fill="#ffffff">NO IMAGE</text><rect x="24" y="84" width="64" height="6" rx="3" fill="#cbd5e1"/></svg>'
)}`;

// onError 재귀 방지 가드: dataset으로 1회만 대체 이미지로 교체해 무한 루프를 막는다.
function handlePartImageError(event: SyntheticEvent<HTMLImageElement>) {
  const target = event.currentTarget;
  if (!target.dataset.fallback) {
    target.dataset.fallback = '1';
    target.src = PART_IMAGE_PLACEHOLDER;
  }
}

type HeroAction = {
  label: string;
  detail: string;
  to: string;
  icon: LucideIcon;
  accent: 'primary' | 'blue' | 'green' | 'slate';
};

type PromoSlide = {
  title: string;
  subtitle: string;
  badge: string;
  src?: string;
  alt?: string;
  tone: string;
};

type HomePromoTile = {
  title: string;
  body: string;
  to: string;
  icon: LucideIcon;
  tone: string;
};

type FeaturedBuild = {
  id: string;
  name: string;
  tag: string;
  spec: string;
  summary: string;
  tone: string;
  partSearches: FeaturedBuildPartSearch[];
};

type FeaturedBuildPartSearch = {
  category: PartCategory;
  searchQuery: string;
};

type FeaturedBuildResolvedPart = {
  search: FeaturedBuildPartSearch;
  part: PartRow;
};

type RecommendationTab = 'popular' | 'ai';

const HOME_RECOMMENDED_PART_LIMIT = 8;

const promoSlides: PromoSlide[] = [
  {
    src: '/assets/home-banners/battle-ground-build.png',
    alt: '배틀그라운드 조립 PC 광고',
    title: '배틀그라운드 추천 PC',
    subtitle: '144Hz로 쾌적한 플레이 환경을 누리세요',
    badge: 'FPS 추천',
    tone: 'from-orange-950 via-slate-950 to-black'
  },
  {
    src: '/assets/home-banners/tactical-fps-build.png',
    alt: '발로란트 조립 PC 광고',
    title: '발로란트 추천 PC',
    subtitle: '레이턴시와 프레임 두 마리 토끼를 잡은 FPS 맞춤형 PC입니다',
    badge: 'TACTICAL FPS',
    tone: 'from-red-950 via-slate-950 to-cyan-950'
  },
  {
    src: '/assets/home-banners/crimson-desert-build.png',
    alt: '붉은사막 조립 PC 광고',
    title: '붉은사막 최적화 PC',
    subtitle: '고성능 CPU와 그래픽카드로 오픈월드를 자유롭게 누비세요',
    badge: 'RPG 추천',
    tone: 'from-red-950 via-black to-slate-950'
  },
  {
    src: '/assets/home-banners/diablo4-build.png',
    alt: '디아블로4 조립 PC 광고',
    title: '디아블로4 추천 PC',
    subtitle: '압도적인 그래픽으로 몰입감이 MAX!',
    badge: 'HIGH-END',
    tone: 'from-red-950 via-stone-950 to-black'
  },
  {
    src: '/assets/home-banners/league-build.png',
    alt: '리그오브레전드 조립 PC 광고',
    title: '리그오브레전드 추천 PC',
    subtitle: '최적의 사양으로 최고의 플레이를 즐겨보세요',
    badge: 'MOBA 추천',
    tone: 'from-blue-950 via-slate-950 to-yellow-950'
  },
  {
    src: '/assets/home-banners/lostark-build.png',
    alt: '로스트아크 조립 PC 광고',
    title: '로스트아크 추천 PC',
    subtitle: '대한민국 최고의 MMORPG에 걸맞는 최적의 사양!',
    badge: 'MMORPG 추천',
    tone: 'from-blue-950 via-slate-950 to-black'
  }
];

const homePromoTiles: HomePromoTile[] = [
  {
    title: 'AI로 견적 맞춰보기',
    body: '예산과 용도를 입력하면 조합 후보를 바로 비교합니다.',
    to: '/requirements/new',
    icon: Bot,
    tone: 'bg-blue-100 text-blue-950'
  },
  {
    title: 'PC 부품 살펴보기',
    body: '최신 데이터를 기준으로 부품 현황을 살펴봅니다.',
    to: '/self-quote',
    icon: Boxes,
    tone: 'bg-emerald-100 text-emerald-950'
  }
];

const heroActions: HeroAction[] = [
  { label: 'PC 견적', detail: '요구사항으로 추천받기', to: '/requirements/new', icon: Bot, accent: 'primary' },
  { label: '전체 부품', detail: '내부 DB 부품 보기', to: '/self-quote?view=list', icon: Boxes, accent: 'blue' },
  { label: 'AS 접수', detail: '문제 증상 접수하기', to: '/support/new', icon: LifeBuoy, accent: 'green' },
  { label: '내 견적함', detail: '저장한 견적 확인', to: '/my/quotes', icon: FileText, accent: 'slate' }
];

const featuredBuilds: FeaturedBuild[] = [
  {
    id: 'home-featured-qhd-gaming',
    name: 'QHD 게이밍 추천팩',
    tag: 'BEST',
    spec: 'RTX 5070 · Ryzen 7 · DDR5 32GB',
    summary: 'QHD 게임과 개발 병행을 위한 균형형 조합입니다.',
    tone: 'from-blue-50 via-white to-white',
    partSearches: [
      { category: 'CPU', searchQuery: 'Ryzen 7' },
      { category: 'MOTHERBOARD', searchQuery: 'B850' },
      { category: 'RAM', searchQuery: 'DDR5 32GB' },
      { category: 'GPU', searchQuery: 'RTX 5070' },
      { category: 'STORAGE', searchQuery: 'NVMe 1TB' },
      { category: 'PSU', searchQuery: '850W' },
      { category: 'CASE', searchQuery: 'FRAME 4000D' },
      { category: 'COOLER', searchQuery: 'Phantom Spirit' }
    ]
  },
  {
    id: 'home-featured-ai-cuda',
    name: 'AI CUDA 실습팩',
    tag: 'AI 추천',
    spec: 'VRAM 우선 · 850W PSU · 2TB SSD',
    summary: 'CUDA 실습과 모델 테스트를 고려한 GPU 우선 조합입니다.',
    tone: 'from-indigo-50 via-white to-white',
    partSearches: [
      { category: 'CPU', searchQuery: 'Ryzen 9' },
      { category: 'MOTHERBOARD', searchQuery: 'X870E' },
      { category: 'RAM', searchQuery: 'DDR5 64GB' },
      { category: 'GPU', searchQuery: 'RTX 5070 Ti' },
      { category: 'STORAGE', searchQuery: 'NVMe 2TB' },
      { category: 'PSU', searchQuery: '1000W' },
      { category: 'CASE', searchQuery: 'H9 Flow' },
      { category: 'COOLER', searchQuery: 'Liquid Freezer III' }
    ]
  },
  {
    id: 'home-featured-low-noise',
    name: '저소음 작업팩',
    tag: '저소음',
    spec: '듀얼타워 공랭 · 흡기형 케이스',
    summary: '장시간 개발 작업에서 소음과 발열을 낮추는 구성입니다.',
    tone: 'from-emerald-50 via-white to-white',
    partSearches: [
      { category: 'CPU', searchQuery: 'Ryzen 7' },
      { category: 'MOTHERBOARD', searchQuery: 'B850' },
      { category: 'RAM', searchQuery: 'DDR5 32GB' },
      { category: 'GPU', searchQuery: 'RTX 5070' },
      { category: 'STORAGE', searchQuery: 'NVMe 2TB' },
      { category: 'PSU', searchQuery: '850W' },
      { category: 'CASE', searchQuery: 'LIGHT BASE 900' },
      { category: 'COOLER', searchQuery: 'Dark Rock Pro 5' }
    ]
  },
  {
    id: 'home-featured-fps-value',
    name: 'FPS 입문 추천팩',
    tag: 'FPS',
    spec: 'RTX 5060 Ti · Ryzen 5 · NVMe 1TB',
    summary: '빠른 반응속도와 안정적인 프레임을 우선한 게이밍 조합입니다.',
    tone: 'from-amber-50 via-white to-white',
    partSearches: [
      { category: 'CPU', searchQuery: '9600X' },
      { category: 'MOTHERBOARD', searchQuery: 'TUF' },
      { category: 'RAM', searchQuery: 'G.SKILL' },
      { category: 'GPU', searchQuery: 'RTX 5060 Ti' },
      { category: 'STORAGE', searchQuery: 'NVMe 1TB' },
      { category: 'PSU', searchQuery: 'RM750e' },
      { category: 'CASE', searchQuery: 'Meshify 3 XL' },
      { category: 'COOLER', searchQuery: 'Phantom Spirit' }
    ]
  },
  {
    id: 'home-featured-creator',
    name: '크리에이터 작업팩',
    tag: '작업',
    spec: 'Ryzen 9 · RTX 5090 · PCIe 5.0 SSD',
    summary: '영상 편집과 렌더링, AI 실습을 함께 고려한 고성능 조합입니다.',
    tone: 'from-violet-50 via-white to-white',
    partSearches: [
      { category: 'CPU', searchQuery: '9900X3D' },
      { category: 'MOTHERBOARD', searchQuery: 'STRIX X870E-A' },
      { category: 'RAM', searchQuery: 'T-CREATE' },
      { category: 'GPU', searchQuery: 'RTX 5090' },
      { category: 'STORAGE', searchQuery: 'Platinum P51' },
      { category: 'PSU', searchQuery: 'VERTEX GX-1200' },
      { category: 'CASE', searchQuery: 'H9 Flow 2025' },
      { category: 'COOLER', searchQuery: 'Kraken Elite 360' }
    ]
  },
  {
    id: 'home-featured-white-tuning',
    name: '화이트 튜닝 추천팩',
    tag: '튜닝',
    spec: 'LIGHT BASE 900 · White RAM · RTX 5080',
    summary: '내부가 잘 보이는 케이스와 안정적인 부품 구성을 맞춘 조합입니다.',
    tone: 'from-slate-50 via-white to-white',
    partSearches: [
      { category: 'CPU', searchQuery: '9800X3D' },
      { category: 'MOTHERBOARD', searchQuery: 'B850I' },
      { category: 'RAM', searchQuery: 'Crucial DDR5-6000' },
      { category: 'GPU', searchQuery: 'RTX 5080 WHITE' },
      { category: 'STORAGE', searchQuery: 'WD BLACK SN8100' },
      { category: 'PSU', searchQuery: 'HX1200i' },
      { category: 'CASE', searchQuery: 'LIGHT BASE 900' },
      { category: 'COOLER', searchQuery: 'ARCTIC Liquid Freezer III PRO' }
    ]
  }
];

export function HomePage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const homeMainRef = useRef<HTMLElement | null>(null);
  const [assistantSession, setAssistantSession] = useState<AiAssistantSession>(() => readAssistantSession());
  const [recommendationTab, setRecommendationTab] = useState<RecommendationTab>(() => readAssistantSession().latestBuilds.length > 0 ? 'ai' : 'popular');
  const [selectedFeaturedBuildId, setSelectedFeaturedBuildId] = useState<string | null>(null);
  const [selectedAiBuildId, setSelectedAiBuildId] = useState<string | null>(null);
  const [applyingBuildId, setApplyingBuildId] = useState<string | null>(null);
  const [applyingFeaturedBuildId, setApplyingFeaturedBuildId] = useState<string | null>(null);
  const [applyError, setApplyError] = useState<string | null>(null);
  const latestHomeAiBuilds = recentBuildsForChatContext(assistantSession);
  const selectedAiBuild = selectedAiBuildId
    ? latestHomeAiBuilds.find((build) => build.id === selectedAiBuildId) ?? null
    : null;
  const featuredBuildPartQueries = useQueries({
    queries: featuredBuilds.map((build) => ({
      queryKey: ['parts', 'home-featured-build', build.id],
      queryFn: async (): Promise<FeaturedBuildResolvedPart[]> => {
        const partPages = await Promise.all(
          build.partSearches.map((part) => listParts({ category: part.category, q: part.searchQuery, page: 0, size: 1, sort: 'price_desc' }))
        );

        return partPages
          .map((page, index) => {
            const part = page.items[0];
            if (!part) return null;
            return {
              search: build.partSearches[index],
              part
            };
          })
          .filter((item): item is FeaturedBuildResolvedPart => Boolean(item));
      },
      // 인기상품 탭이 활성일 때만 발사한다. 게이트가 없으면 마운트 시 6빌드×8부품=48개 요청이
      // 무조건 나가, 인기상품 탭을 보지 않는 AI-탭 사용자에게도 불필요한 fan-out이 발생한다.
      enabled: recommendationTab === 'popular',
      staleTime: 60_000
    }))
  });
  const selectedFeaturedBuildIndex = selectedFeaturedBuildId
    ? featuredBuilds.findIndex((build) => build.id === selectedFeaturedBuildId)
    : -1;
  const selectedFeaturedBuild = selectedFeaturedBuildIndex >= 0 ? featuredBuilds[selectedFeaturedBuildIndex] : null;
  const selectedFeaturedBuildParts = selectedFeaturedBuildIndex >= 0 ? featuredBuildPartQueries[selectedFeaturedBuildIndex]?.data ?? [] : [];
  const selectedFeaturedBuildPartCount = selectedFeaturedBuild?.partSearches.length ?? 0;
  const selectedFeaturedBuildTotalPrice = selectedFeaturedBuildPartCount > 0 && selectedFeaturedBuildParts.length === selectedFeaturedBuildPartCount
    ? selectedFeaturedBuildParts.reduce((sum, item) => sum + item.part.price, 0)
    : null;
  const featuredBuildGraphQuery = useQuery({
    queryKey: [
      'build-graph',
      'home-featured-build-preview',
      selectedFeaturedBuild?.id ?? 'none',
      featuredBuildGraphSignature(selectedFeaturedBuildParts)
    ],
    queryFn: () => resolveBuildGraph({
      source: 'AI_BUILD',
      view: 'FOCUSED',
      items: selectedFeaturedBuildParts.map(({ search, part }) => ({
        partId: part.id,
        category: search.category,
        quantity: 1
      })),
      budgetWon: selectedFeaturedBuildTotalPrice ?? undefined,
      focus: {
        mode: 'BUILD_OVERVIEW',
        tool: 'price'
      }
    }),
    enabled: recommendationTab === 'popular' && Boolean(selectedFeaturedBuild) && selectedFeaturedBuildPartCount > 0 && selectedFeaturedBuildParts.length === selectedFeaturedBuildPartCount,
    staleTime: 60_000
  });
  const featuredPreviewItems = featuredBuilds.map((build, index) => {
    const buildParts = featuredBuildPartQueries[index]?.data ?? [];
    const casePart = buildParts.find((item) => item.part.category === 'CASE')?.part;
    const assetTotalPrice = buildParts.length === build.partSearches.length
      ? buildParts.reduce((sum, item) => sum + item.part.price, 0)
      : null;

    return {
      build,
      buildParts,
      casePart,
      assetTotalPrice
    };
  });
  const aiBuildImagePartQueries = useQueries({
    queries: latestHomeAiBuilds.map((build) => {
      const imageItem = aiBuildPreviewImageItem(build, latestHomeAiBuilds);
      return {
        queryKey: ['parts', 'home-ai-build-image-part', build.id, imageItem?.partId],
        queryFn: () => imageItem ? getPart(imageItem.partId) : Promise.resolve(null),
        enabled: Boolean(imageItem),
        staleTime: 60_000
      };
    })
  });
  const aiPreviewItems = latestHomeAiBuilds.map((build, index) => ({
    build,
    imagePart: aiBuildImagePartQueries[index]?.data ?? null
  }));
  const graphQuery = useQuery({
    queryKey: [
      'build-graph',
      'home-ai-build',
      selectedAiBuild?.id,
      selectedAiBuild?.items.map((item) => item.partId).join(',')
    ],
    queryFn: () => resolveBuildGraph({
      source: 'AI_BUILD',
      view: 'FOCUSED',
      items: selectedAiBuild?.items.map((item) => ({
        partId: item.partId,
        category: item.category,
        quantity: item.quantity
      })),
      budgetWon: selectedAiBuild?.budgetWon,
      focus: {
        mode: 'BUILD_OVERVIEW',
        tool: 'price'
      }
    }),
    enabled: recommendationTab === 'ai' && Boolean(selectedAiBuild),
    staleTime: 60_000
  });

  useEffect(() => {
    const syncAssistantSession = () => {
      clearLegacyAiStorage();
      const nextSession = readAssistantSession();
      setAssistantSession(nextSession);
      if (nextSession.latestBuilds.length > 0) {
        setRecommendationTab('ai');
      } else {
        setRecommendationTab('popular');
        setSelectedAiBuildId(null);
      }
    };
    syncAssistantSession();
    window.addEventListener(AI_ASSISTANT_SESSION_CHANGED_EVENT, syncAssistantSession);
    window.addEventListener(AUTH_CHANGED_EVENT, syncAssistantSession);
    window.addEventListener('storage', syncAssistantSession);
    return () => {
      window.removeEventListener(AI_ASSISTANT_SESSION_CHANGED_EVENT, syncAssistantSession);
      window.removeEventListener(AUTH_CHANGED_EVENT, syncAssistantSession);
      window.removeEventListener('storage', syncAssistantSession);
    };
  }, []);

  useEffect(() => {
    if (selectedAiBuildId && !latestHomeAiBuilds.some((build) => build.id === selectedAiBuildId)) {
      setSelectedAiBuildId(null);
    }
  }, [latestHomeAiBuilds, selectedAiBuildId]);

  useEffect(() => {
    const main = homeMainRef.current;
    const screen = main?.closest('.screen-shell');
    if (!main || !screen) return;

    const updateHomeHeaderHeight = () => {
      const headerHeight = Math.max(0, Math.round(main.getBoundingClientRect().top));
      main.style.setProperty('--home-header-height', `${headerHeight}px`);
      const fullpageApi = (window as Window & { fullpage_api?: { reBuild?: () => void } }).fullpage_api;
      fullpageApi?.reBuild?.();
    };

    updateHomeHeaderHeight();
    window.addEventListener('resize', updateHomeHeaderHeight);

    const resizeObserver = new ResizeObserver(updateHomeHeaderHeight);
    Array.from(screen.children).forEach((child) => {
      if (child !== main) {
        resizeObserver.observe(child);
      }
    });

    return () => {
      window.removeEventListener('resize', updateHomeHeaderHeight);
      resizeObserver.disconnect();
    };
  }, []);

  async function selectAiBuild(build: AiRecommendedBuild) {
    if (applyingBuildId || applyingFeaturedBuildId) return;
    const normalizedBuild = normalizeAiRecommendedBuild(build);
    saveSelectedAiBuild(normalizedBuild);
    setApplyError(null);
    setApplyingBuildId(normalizedBuild.id);
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
      queryClient.setQueryData(['quote-draft', 'current'], appliedDraft);
      void queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] });
      navigate('/self-quote');
    } catch {
      setApplyError('AI 추천 조합을 셀프 견적 장바구니에 적용하지 못했습니다.');
    } finally {
      setApplyingBuildId(null);
    }
  }

  async function selectFeaturedBuild(build: FeaturedBuild, buildParts: FeaturedBuildResolvedPart[]) {
    if (applyingFeaturedBuildId || applyingBuildId) return;
    setApplyError(null);
    if (buildParts.length < build.partSearches.length) {
      setApplyError('추천상품 견적 정보를 아직 모두 불러오고 있습니다. 잠시 후 다시 선택해 주세요.');
      return;
    }

    setApplyingFeaturedBuildId(build.id);
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
      setApplyError('추천상품 견적을 셀프 견적 장바구니에 담지 못했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setApplyingFeaturedBuildId(null);
    }
  }

  return (
    <div className="screen-shell home-screen-shell">
      <AppHeader />
      <main ref={homeMainRef} className="home-screen-main mx-auto w-full max-w-[1550px] px-4 sm:px-6 lg:px-8 xl:px-0">
      <div data-testid="home-fullpage-scroll" className="home-fullpage-shell">
        <ReactFullpage
          licenseKey=""
          anchors={['storefront', 'recommended-builds', 'popular-parts']}
          scrollingSpeed={1000}
          navigation
          navigationTooltips={['홈', '추천 빌드', '인기 부품']}
          credits={{ enabled: false }}
          verticalCentered={false}
          bigSectionsDestination="top"
          fitToSection
          keyboardScrolling
          lockAnchors
          recordHistory={false}
          responsiveWidth={768}
          scrollOverflow={false}
          render={() => (
            <ReactFullpage.Wrapper>
              <section className="section home-fullpage-section home-fullpage-section--hero">
                <HomeStorefront />
              </section>

              <section className="section home-fullpage-section home-fullpage-section--center">
          <div className="panel home-fit-panel home-recommended-panel p-4 sm:p-5">
            <div className="mb-3 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
              <div>
                <div className="text-xs font-black text-brand-blue"></div>
                <h2 className="mt-1 text-xl font-black text-commerce-ink">추천상품</h2>
                <p className="mt-1 text-sm text-slate-500">우측 토클을 클릭하여 인기상품과 AI 추천을 함께 살펴보세요.</p>
              </div>
              <div role="tablist" aria-label="홈 추천상품 탭" className="inline-flex rounded-lg border border-commerce-line bg-slate-50 p-1">
                <button
                  type="button"
                  role="tab"
                  aria-selected={recommendationTab === 'popular'}
                  onClick={() => setRecommendationTab('popular')}
                  className={`min-h-10 rounded-md px-4 text-sm font-black transition ${recommendationTab === 'popular' ? 'bg-commerce-ink text-white shadow-product' : 'text-slate-600 hover:bg-white hover:text-commerce-ink'}`}
                >
                  인기상품
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={recommendationTab === 'ai'}
                  onClick={() => setRecommendationTab('ai')}
                  className={`min-h-10 rounded-md px-4 text-sm font-black transition ${recommendationTab === 'ai' ? 'bg-commerce-ink text-white shadow-product' : 'text-slate-600 hover:bg-white hover:text-commerce-ink'}`}
                >
                  AI 추천상품
                </button>
              </div>
            </div>
            {applyError ? (
              <div role="alert" className="mb-3 rounded-md border border-red-100 bg-red-50 px-4 py-3 text-sm font-bold text-red-700">
                {applyError}
              </div>
            ) : null}
            {recommendationTab === 'popular' ? (
              <HomeFeaturedBuildPreview
                items={featuredPreviewItems}
                selectedBuildId={selectedFeaturedBuild?.id ?? null}
                applyingBuildId={applyingFeaturedBuildId}
                graph={featuredBuildGraphQuery.data}
                isGraphLoading={featuredBuildGraphQuery.isLoading}
                isGraphError={featuredBuildGraphQuery.isError}
                onSelectBuild={setSelectedFeaturedBuildId}
                onClearSelection={() => setSelectedFeaturedBuildId(null)}
                onApplyBuild={selectFeaturedBuild}
                onImageError={handlePartImageError}
              />
            ) : (
              <div data-testid="home-ai-recommendations" className="home-ai-recommendations">
                {latestHomeAiBuilds.length > 0 ? (
                  <HomeAiBuildPreview
                    items={aiPreviewItems}
                    selectedBuildId={selectedAiBuild?.id ?? null}
                    applyingBuildId={applyingBuildId}
                    graph={graphQuery.data}
                    isGraphLoading={graphQuery.isLoading}
                    isGraphError={graphQuery.isError}
                    onSelectBuild={setSelectedAiBuildId}
                    onClearSelection={() => setSelectedAiBuildId(null)}
                    onApplyBuild={selectAiBuild}
                    onImageError={handlePartImageError}
                  />
                ) : (
                  <div className="rounded-lg border border-dashed border-blue-200 bg-blue-50/60 p-6 text-center">
                    <div className="mx-auto grid h-12 w-12 place-items-center rounded-xl bg-white text-brand-blue shadow-product">
                      <Bot size={24} />
                    </div>
                    <h3 className="mt-3 text-base font-black text-commerce-ink">AI 추천상품 대기 중</h3>
                    <p className="mx-auto mt-2 max-w-lg break-keep text-sm leading-6 text-slate-500">
                      AI에게 예산이나 부품을 물어보면 추천상품 3개가 여기에 표시됩니다.
                    </p>
                  </div>
                )}
              </div>
            )}
          </div>
        </section>

              <section className="section home-fullpage-section home-fullpage-section--center">
                <PopularPartsSection />
              </section>
            </ReactFullpage.Wrapper>
          )}
        />
      </div>
      </main>
    </div>
  );
}

function featuredBuildGraphSignature(buildParts: FeaturedBuildResolvedPart[]) {
  return buildParts
    .map(({ search, part }) => `${search.category}:${part.id}:1`)
    .sort()
    .join('|');
}

function aiBuildPreviewImageItem(
  build: AiRecommendedBuild,
  allBuilds: AiRecommendedBuild[]
): AiRecommendedBuild['items'][number] | undefined {
  const categoryPriority: PartCategory[] = ['GPU', 'CASE', 'CPU', 'COOLER', 'MOTHERBOARD', 'RAM', 'STORAGE', 'PSU'];
  const variedCategory = categoryPriority.find((category) => {
    const partIds = allBuilds
      .map((candidate) => candidate.items.find((item) => item.category === category)?.partId)
      .filter((partId): partId is string => Boolean(partId));
    return partIds.length > 0 && new Set(partIds).size > 1 && build.items.some((item) => item.category === category);
  });
  const displayPriority = variedCategory
    ? [variedCategory, ...categoryPriority.filter((category) => category !== variedCategory)]
    : categoryPriority;

  for (const category of displayPriority) {
    const item = build.items.find((part) => part.category === category);
    if (item) {
      return item;
    }
  }
  return build.items[0];
}

function HomeStorefront() {
  return (
    <div className="home-storefront-fit space-y-6">
      <PromoBanner />
      <HeroActionGrid />
      <HomePromoTileStrip />
    </div>
  );
}

function PromoBanner() {
  const [activeIndex, setActiveIndex] = useState(0);

  useEffect(() => {
    const timer = window.setInterval(() => {
      goToNextSlide();
    }, 4_000);

    return () => window.clearInterval(timer);
  }, []);

  function goToPreviousSlide() {
    setActiveIndex((current) => (current - 1 + promoSlides.length) % promoSlides.length);
  }

  function goToNextSlide() {
    setActiveIndex((current) => (current + 1) % promoSlides.length);
  }

  return (
    <section className="relative" aria-label="홈 광고 배너">
      <div className="home-promo-stage relative h-[430px] overflow-hidden sm:h-[500px] lg:h-[560px] xl:h-[600px]">
        {promoSlides.map((slide, index) => {
          const offset = carouselOffset(index, activeIndex, promoSlides.length);
          const isActive = activeIndex === index;
          const isSide = Math.abs(offset) === 1;
          const isVisible = isActive || isSide;

          return (
            <button
              key={slide.title}
              type="button"
              aria-label={`${index + 1}번 광고 보기: ${slide.title}`}
              aria-current={isActive ? 'true' : undefined}
              onClick={() => setActiveIndex(index)}
              className={`group absolute top-1/2 overflow-hidden rounded-2xl bg-gradient-to-br ${slide.tone} text-left shadow-product transition-all duration-700 ease-out focus:outline-none focus:ring-4 focus:ring-blue-100 ${isVisible ? 'pointer-events-auto' : 'pointer-events-none'} ${isActive ? 'ring-2 ring-commerce-ink' : 'ring-1 ring-white/20'}`}
              style={{
                left: `${50 + offset * 34}%`,
                width: isActive ? '58%' : '30%',
                height: isActive ? '92%' : '72%',
                zIndex: isActive ? 30 : isSide ? 20 : 0,
                opacity: isActive ? 1 : isSide ? 0.68 : 0,
                transform: `translate(-50%, -50%) scale(${isActive ? 1 : 0.9})`
              }}
            >
              {slide.src ? (
                <img
                  src={slide.src}
                  alt={slide.alt}
                  className={`absolute inset-0 h-full w-full object-contain transition duration-500 group-hover:scale-[1.01] ${isActive ? 'opacity-100' : 'opacity-90 brightness-75'}`}
                  draggable={false}
                />
              ) : (
                <div className={`${isActive ? 'h-20 w-20' : 'h-14 w-14'} absolute right-6 top-6 grid place-items-center rounded-2xl bg-white/10 text-white/80 ring-1 ring-white/20`}>
                  <PackageCheck size={isActive ? 42 : 30} strokeWidth={1.8} />
                </div>
              )}
              <div className="absolute inset-0 bg-gradient-to-t from-black/85 via-black/35 to-black/5" />
              <div className={`relative flex h-full flex-col justify-end text-white ${isActive ? 'p-8' : 'p-5'}`}>
                <span className={`mb-3 w-fit rounded-full bg-white/15 font-black text-white ring-1 ring-white/20 ${isActive ? 'px-4 py-1.5 text-xs' : 'px-3 py-1 text-[10px]'}`}>
                  {slide.badge}
                </span>
                <h2 className={`font-black leading-tight tracking-tight ${isActive ? 'text-3xl lg:text-5xl' : 'text-xl lg:text-2xl'}`}>{slide.title}</h2>
                <p className={`mt-3 line-clamp-2 font-semibold leading-6 text-white/80 ${isActive ? 'text-base lg:text-lg' : 'text-xs lg:text-sm'}`}>{slide.subtitle}</p>
              </div>
            </button>
          );
        })}
        <button
          type="button"
          aria-label="이전 광고 보기"
          onClick={goToPreviousSlide}
          className="absolute left-[18%] top-1/2 z-40 grid h-11 w-11 -translate-y-1/2 place-items-center rounded-full bg-white/90 text-commerce-ink shadow-product transition hover:bg-white focus:outline-none focus:ring-4 focus:ring-blue-100"
        >
          <ChevronLeft size={24} />
        </button>
        <button
          type="button"
          aria-label="다음 광고 보기"
          onClick={goToNextSlide}
          className="absolute right-[18%] top-1/2 z-40 grid h-11 w-11 -translate-y-1/2 place-items-center rounded-full bg-white/90 text-commerce-ink shadow-product transition hover:bg-white focus:outline-none focus:ring-4 focus:ring-blue-100"
        >
          <ChevronRight size={24} />
        </button>
      </div>
      <div className="mt-2 flex items-center justify-center gap-2">
        {promoSlides.map((slide, index) => (
          <button
            key={slide.title}
            type="button"
            aria-label={`${index + 1}번 광고 보기`}
            aria-current={activeIndex === index ? 'true' : undefined}
            onClick={() => setActiveIndex(index)}
            className={`h-1.5 rounded-full transition-all ${activeIndex === index ? 'w-16 bg-commerce-ink' : 'w-7 bg-slate-300 hover:bg-slate-400'}`}
          />
        ))}
      </div>
    </section>
  );
}

function carouselOffset(index: number, activeIndex: number, slideCount: number) {
  let offset = index - activeIndex;
  if (offset > slideCount / 2) {
    offset -= slideCount;
  }
  if (offset < -slideCount / 2) {
    offset += slideCount;
  }
  return offset;
}

function HeroActionGrid() {
  return (
    <div className="home-action-grid flex flex-wrap justify-center gap-7 sm:gap-10">
      {heroActions.map((item) => {
        const tone = heroActionTone(item.accent);
        return (
          <Link
            key={item.label}
            aria-label={`${item.label}: ${item.detail}`}
            to={item.to}
            className="home-action-link group flex w-[92px] flex-col items-center gap-2.5 rounded-md text-center transition hover:-translate-y-0.5 focus:outline-none focus:ring-4 focus:ring-blue-100 sm:w-[106px]"
          >
            <div className={`home-action-icon grid h-16 w-16 place-items-center rounded-2xl transition group-hover:shadow-product ${tone.icon}`}>
              <item.icon size={29} strokeWidth={2.3} />
            </div>
            <span className="w-full truncate text-sm font-black text-commerce-ink">{item.label}</span>
          </Link>
        );
      })}
    </div>
  );
}

function HomePromoTileStrip() {
  return (
    <div className="home-promo-tile-strip mx-auto grid w-full max-w-[1120px] gap-4 md:grid-cols-2">
      {homePromoTiles.map((tile) => {
        const className = `home-promo-tile group relative min-h-[118px] overflow-hidden rounded-xl px-7 py-6 text-left shadow-product transition hover:-translate-y-0.5 hover:shadow-xl focus:outline-none focus:ring-4 focus:ring-blue-100 ${tile.tone}`;
        const content = (
          <div className="flex items-center justify-between gap-4">
            <div>
              <div className="text-lg font-black">{tile.title}</div>
              <div className="mt-2 max-w-[360px] break-keep text-sm font-semibold opacity-75">{tile.body}</div>
            </div>
            <div className="home-promo-tile-icon grid h-14 w-14 shrink-0 place-items-center rounded-2xl bg-white/60 transition group-hover:scale-[1.03]">
              <tile.icon size={26} strokeWidth={2.2} />
            </div>
          </div>
        );

        if (tile.to === '/requirements/new') {
          return (
            <button
              key={tile.title}
              type="button"
              onClick={() => openAiAssistant()}
              className={className}
            >
              {content}
            </button>
          );
        }

        return (
          <Link
            key={tile.title}
            to={tile.to}
            className={className}
          >
            {content}
          </Link>
        );
      })}
    </div>
  );
}

function heroActionTone(accent: HeroAction['accent']) {
  const tones = {
    primary: {
      icon: 'bg-commerce-ink text-white'
    },
    blue: {
      icon: 'bg-blue-50 text-brand-blue'
    },
    green: {
      icon: 'bg-emerald-50 text-emerald-600'
    },
    slate: {
      icon: 'bg-slate-100 text-commerce-ink'
    }
  };
  return tones[accent];
}

function PopularPartsSection() {
  const eventSessionId = useRef(`home-parts-${Date.now()}-${Math.random().toString(36).slice(2)}`);
  const recordedImpressions = useRef(new Set<string>());
  const homePartsQuery = useQuery({
    queryKey: ['recommendations', 'home-parts', HOME_RECOMMENDED_PART_LIMIT],
    queryFn: () => listHomeRecommendedParts(HOME_RECOMMENDED_PART_LIMIT),
    staleTime: 60_000
  });
  const homeParts = homePartsQuery.data?.items ?? [];
  const displayItems: Array<(typeof homeParts)[number] | null> = homePartsQuery.isLoading
    ? Array.from({ length: HOME_RECOMMENDED_PART_LIMIT }, () => null)
    : homeParts;

  useEffect(() => {
    homeParts.forEach((item) => {
      const key = item.recommendationId;
      if (recordedImpressions.current.has(key)) {
        return;
      }
      recordedImpressions.current.add(key);
      void recordRecommendationEvent({
        eventType: 'IMPRESSION',
        sourceSurface: 'HOME_RECOMMENDED_PARTS',
        recommendationId: item.recommendationId,
        partId: item.part.id,
        category: item.part.category,
        rankPosition: item.rankPosition,
        idempotencyKey: `${eventSessionId.current}:impression:${item.recommendationId}`,
        eventPayload: {
          scoreSource: item.scoreSource,
          modelVersion: item.modelVersion,
          reasonTags: item.reasonTags
        }
      }).catch(() => undefined);
    });
  }, [homeParts]);

  function recordClick(item: (typeof homeParts)[number]) {
    void recordRecommendationEvent({
      eventType: 'CLICK',
      sourceSurface: 'HOME_RECOMMENDED_PARTS',
      recommendationId: item.recommendationId,
      partId: item.part.id,
      category: item.part.category,
      rankPosition: item.rankPosition,
      idempotencyKey: `${eventSessionId.current}:click:${item.recommendationId}`,
      eventPayload: {
        scoreSource: item.scoreSource,
        modelVersion: item.modelVersion,
        reasonTags: item.reasonTags
      }
    }).catch(() => undefined);
  }

  return (
    <section className="panel home-fit-panel home-parts-panel p-5 sm:p-6">
      <div className="mb-5 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <div className="text-xs font-black text-brand-blue">Part ranking</div>
          <h2 className="mt-1 text-xl font-black text-commerce-ink">인기 부품 랭킹</h2>
          <p className="mt-1 text-sm text-slate-500">최신 데이터를 반영하여 선정된 인기 부품입니다.</p>
        </div>
        <Link to="/self-quote" aria-label="셀프 견적 전체 보기" className="text-sm font-black text-brand-blue hover:underline">셀프 견적 전체 보기</Link>
      </div>
      <div className="home-parts-grid grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {displayItems.map((item, index) => {
          const matchedPart = item?.part ?? null;
          const partDetailPath = matchedPart && item ? homeRecommendedPartPath(item) : '.';
          const saleLabel = index === 0 ? 'BEST' : matchedPart?.benchmarkSummary ? 'PASS' : '추천';
          const reasonLabels = item ? homeReasonLabels(item.reasonTags ?? []) : [];

          return (
            <Link
              key={matchedPart?.id ?? `home-part-loading-${index}`}
              to={partDetailPath}
              aria-label={`인기 부품 ${index + 1}번 보기`}
              aria-disabled={matchedPart ? undefined : true}
              onClick={() => {
                if (matchedPart && item) {
                  recordClick(item);
                }
              }}
              className={`home-part-card group rounded-lg border border-commerce-line bg-white p-3 transition hover:-translate-y-0.5 hover:border-commerce-ink hover:shadow-product focus:outline-none focus:ring-4 focus:ring-blue-100 ${matchedPart ? '' : 'pointer-events-none animate-pulse cursor-wait opacity-70'}`}
            >
              <div className="mb-2 flex items-center justify-between">
                <span className="flex h-7 w-7 items-center justify-center rounded-full bg-commerce-ink text-xs font-black text-white">{index + 1}</span>
                <span className={`rounded px-2 py-1 text-[11px] font-black ${index === 0 ? 'bg-commerce-sale text-white' : 'bg-slate-100 text-slate-700'}`}>{saleLabel}</span>
              </div>
              <div className="overflow-hidden rounded-md border border-commerce-line bg-slate-50 text-brand-blue">
                {matchedPart ? (
                  <img
                    src={partImageUrl(matchedPart)}
                    alt={`${matchedPart.name} 제품 사진`}
                    onError={handlePartImageError}
                    className="home-part-image h-40 w-full object-contain p-3 sm:h-44 xl:h-40"
                  />
                ) : (
                  <div className="home-part-image grid h-40 place-items-center sm:h-44 xl:h-40">
                    <PackageCheck size={30} />
                  </div>
                )}
              </div>
              <div className="mt-3 text-xs font-black text-brand-blue">{matchedPart?.category ?? '추천'}</div>
              <h3 className="mt-1 min-h-10 text-sm font-black leading-5 text-commerce-ink">{matchedPart?.name ?? '추천 부품을 불러오는 중'}</h3>
              <p className="home-part-detail mt-1 text-xs text-slate-500">{matchedPart ? homePartDetail(matchedPart) : '내부 자산 랭킹 계산 중입니다.'}</p>
              {reasonLabels.length > 0 ? (
                <div className="home-part-tags mt-3 flex flex-wrap gap-1.5">
                  {reasonLabels.slice(0, 3).map((label) => (
                    <span key={label} className="rounded bg-blue-50 px-2 py-1 text-[11px] font-black text-brand-blue">
                      {label}
                    </span>
                  ))}
                </div>
              ) : null}
              <div className="mt-3 flex items-center justify-between gap-2">
                <span className="text-lg font-black text-commerce-ink">{matchedPart ? `${matchedPart.price.toLocaleString()}원` : '가격 확인 중'}</span>
              </div>
            </Link>
          );
        })}
      </div>
      {homePartsQuery.isError ? (
        <p className="mt-3 text-xs font-bold text-amber-600">추천 부품을 불러오지 못했습니다. 잠시 후 다시 확인해 주세요.</p>
      ) : null}
    </section>
  );
}

function homeRecommendedPartPath(item: HomeRecommendedPart) {
  const search = new URLSearchParams({
    recId: item.recommendationId,
    recSurface: 'HOME_RECOMMENDED_PARTS',
    rank: String(item.rankPosition)
  });
  return `/parts/${item.part.id}?${search.toString()}`;
}

function homeReasonLabels(tags: string[]) {
  const labels: Record<string, string> = {
    benchmark: '벤치마크 점수 포함',
    fps: 'FPS 성능 검증됨',
    toolReady: '호환성 검증 가능',
    image: '상품 정보 확인',
    freshPrice: '최근 가격 반영됨',
    userReaction: '유저 리뷰 포함',
    internalAsset: '자체 수집 데이터'
  };
  return tags.map((tag) => labels[tag]).filter((label): label is string => Boolean(label));
}

function homePartDetail(part: PartRow) {
  const score = part.benchmarkSummary?.score;
  if (score !== undefined && score !== null) {
    return `${part.manufacturer ?? part.category} · 벤치/스펙 점수 ${score} (참고용)`;
  }
  if (part.externalOffer?.supplierName) {
    return `${part.manufacturer ?? part.category} · ${part.externalOffer.supplierName} 기준`;
  }
  return `${part.manufacturer ?? part.category} · 내부 자산 추천`;
}
