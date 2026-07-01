import { useEffect, useState } from 'react';
import { useQueries } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import {
  Activity,
  Bot,
  Cpu,
  Database,
  HardDrive,
  Heart,
  Monitor,
  PackageCheck,
  SearchCheck,
  ShieldCheck,
  ShoppingCart,
  Star,
  Zap,
  type LucideIcon
} from 'lucide-react';
import { Screen } from '../../../components/ui';
import { AUTH_CHANGED_EVENT } from '../../../lib/api';
import { partImageUrl } from '../../parts/partDisplay';
import { applyAiBuildToQuoteDraft, getPart, listParts } from '../../parts/partsApi';
import type { PartRow } from '../../parts/types';
import { AiBuildAssistant } from '../components/AiBuildAssistant';
import {
  AI_ASSISTANT_SESSION_CHANGED_EVENT,
  clearLegacyAiStorage,
  normalizeAiRecommendedBuild,
  readAssistantSession,
  saveSelectedAiBuild,
  type AiAssistantSession,
  type AiRecommendedBuild,
  type PartCategory
} from '../aiSelection';

type QuickCategory = {
  label: string;
  detail: string;
  to: string;
  icon: LucideIcon;
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

type PopularPart = {
  rank: number;
  label: string;
  category: string;
  searchQuery: string;
  sale: string;
  detail: string;
  icon: LucideIcon;
};

type RecommendationTab = 'popular' | 'ai';

const promoSlides = [
  {
    src: '/assets/home-banners/pc-build-festa.png',
    alt: 'PC Build Festa 프리미엄 PC 완성 광고'
  },
  {
    src: '/assets/home-banners/summer-upgrade.png',
    alt: '여름 PC 업그레이드 특가 광고'
  },
  {
    src: '/assets/home-banners/ai-workstation.png',
    alt: 'AI 작업용 PC 특가전 광고'
  }
];

const quickCategories: QuickCategory[] = [
  { label: 'CPU', detail: '작업 성능 기준', to: '/self-quote?category=CPU', icon: Cpu },
  { label: '메인보드', detail: '소켓/확장성 확인', to: '/self-quote?category=MOTHERBOARD', icon: PackageCheck },
  { label: 'RAM', detail: '개발/멀티태스킹', to: '/self-quote?category=RAM', icon: Database },
  { label: 'GPU', detail: 'QHD/AI 실습 기준', to: '/self-quote?category=GPU', icon: Monitor },
  { label: 'SSD', detail: '프로젝트 저장공간', to: '/self-quote?category=STORAGE', icon: HardDrive },
  { label: '파워', detail: '피크 전력 여유율', to: '/self-quote?category=PSU', icon: Zap },
  { label: '케이스', detail: '그래픽카드 장착', to: '/self-quote?category=CASE', icon: ShoppingCart },
  { label: '쿨러', detail: '발열/소음 여유', to: '/self-quote?category=COOLER', icon: Activity }
];

const featuredBuilds: FeaturedBuild[] = [
  {
    id: 'home-featured-qhd-gaming',
    name: 'QHD 게이밍 추천팩',
    tag: 'SALE 12%',
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
    tag: '검증 통과',
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
  }
];

const popularPartDeals: PopularPart[] = [
  { rank: 1, label: 'RTX 5070 QHD 그래픽카드', category: 'GPU', searchQuery: 'RTX 5070', sale: 'SALE', detail: 'QHD 고주사율 후보', icon: Monitor },
  { rank: 2, label: 'Ryzen 7 작업용 CPU', category: 'CPU', searchQuery: 'Ryzen 7', sale: 'BEST', detail: '게임/개발 균형형', icon: Cpu },
  { rank: 3, label: 'DDR5 32GB 메모리', category: 'RAM', searchQuery: 'DDR5 32GB', sale: 'LOW', detail: '멀티태스킹 표준', icon: Database },
  { rank: 4, label: 'ATX 3.1 850W 파워', category: 'PSU', searchQuery: 'ATX 3.1 850W', sale: 'PASS', detail: '전력 여유 확보', icon: Zap }
];

export function HomePage() {
  const navigate = useNavigate();
  const [assistantSession, setAssistantSession] = useState<AiAssistantSession>(() => readAssistantSession());
  const [recommendationTab, setRecommendationTab] = useState<RecommendationTab>(() => readAssistantSession().latestBuilds.length > 0 ? 'ai' : 'popular');
  const [applyingBuildId, setApplyingBuildId] = useState<string | null>(null);
  const [applyingFeaturedBuildId, setApplyingFeaturedBuildId] = useState<string | null>(null);
  const [applyError, setApplyError] = useState<string | null>(null);
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
      staleTime: 60_000
    }))
  });
  const aiBuildCaseQueries = useQueries({
    queries: assistantSession.latestBuilds.map((build) => {
      const caseItem = build.items.find((item) => item.category === 'CASE');
      return {
        queryKey: ['parts', 'home-ai-build-case', build.id, caseItem?.partId],
        queryFn: () => caseItem ? getPart(caseItem.partId) : Promise.resolve(null),
        enabled: Boolean(caseItem),
        staleTime: 60_000
      };
    })
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

  async function selectAiBuild(build: AiRecommendedBuild) {
    if (applyingBuildId || applyingFeaturedBuildId) return;
    const normalizedBuild = normalizeAiRecommendedBuild(build);
    saveSelectedAiBuild(normalizedBuild);
    setApplyError(null);
    setApplyingBuildId(normalizedBuild.id);
    try {
      await applyAiBuildToQuoteDraft({
        buildId: normalizedBuild.id,
        conflictPolicy: 'REPLACE',
        items: normalizedBuild.items.map((item) => ({
          partId: item.partId,
          category: item.category,
          quantity: item.quantity
        }))
      });
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
      await applyAiBuildToQuoteDraft({
        buildId: build.id,
        conflictPolicy: 'REPLACE',
        items: buildParts.map(({ search, part }) => ({
          partId: part.id,
          category: search.category,
          quantity: 1
        }))
      });
      navigate('/self-quote');
    } catch {
      setApplyError('추천상품 견적을 셀프견적 장바구니에 담지 못했습니다. 백엔드 실행 상태를 확인해 주세요.');
    } finally {
      setApplyingFeaturedBuildId(null);
    }
  }

  return (
    <Screen>
      <div className="space-y-7 pb-12">
        <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px]">
          <PromoBanner />
          <QuickCategoryPanel />
        </section>

        <section className="panel p-5 sm:p-6">
          <div className="mb-5 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <div className="text-xs font-black text-brand-blue">Recommended builds</div>
              <h2 className="mt-1 text-xl font-black text-commerce-ink">추천상품</h2>
              <p className="mt-1 text-sm text-slate-500">처음에는 인기상품을 보여주고, 챗봇 질문 후에는 최신 AI 추천상품 3개를 비교합니다.</p>
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
            <div className="grid gap-3 md:grid-cols-3">
              {featuredBuilds.map((build, index) => {
                const buildParts = featuredBuildPartQueries[index]?.data ?? [];
                const casePart = buildParts.find((item) => item.part.category === 'CASE')?.part;
                const assetTotalPrice = buildParts.length === build.partSearches.length
                  ? buildParts.reduce((sum, item) => sum + item.part.price, 0)
                  : null;

                return (
                  <FeaturedBuildCard
                    key={build.id}
                    build={build}
                    buildParts={buildParts}
                    casePart={casePart}
                    assetTotalPrice={assetTotalPrice}
                    isApplying={applyingFeaturedBuildId === build.id}
                    onSelect={selectFeaturedBuild}
                  />
                );
              })}
            </div>
          ) : (
            <div data-testid="home-ai-recommendations">
              {assistantSession.latestBuilds.length > 0 ? (
                <>
                  <div className="grid gap-3 md:grid-cols-3">
                    {assistantSession.latestBuilds.map((build, index) => (
                      <AiRecommendationCard
                        key={build.id}
                        build={build}
                        casePart={aiBuildCaseQueries[index]?.data ?? undefined}
                        isApplying={applyingBuildId === build.id}
                        onSelect={selectAiBuild}
                      />
                    ))}
                  </div>
                </>
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
        </section>

        <section className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_360px]">
          <PopularPartsSection />
          <WorkflowPanel />
        </section>
      </div>
      <AiBuildAssistant surface="home" />
    </Screen>
  );
}

function AiRecommendationCard({
  build,
  casePart,
  isApplying,
  onSelect
}: {
  build: AiRecommendedBuild;
  casePart?: PartRow | null;
  isApplying: boolean;
  onSelect: (build: AiRecommendedBuild) => void;
}) {
  const hasWarnings = Boolean(build.warnings && build.warnings.length > 0);
  return (
    <button
      type="button"
      onClick={() => onSelect(build)}
      disabled={isApplying}
      aria-label={`${build.title} 셀프 견적으로 적용`}
      className={`group rounded-lg border border-commerce-line bg-gradient-to-br ${aiBuildTone(build)} p-4 text-left transition hover:-translate-y-0.5 hover:border-commerce-ink hover:shadow-product focus:outline-none focus:ring-4 focus:ring-blue-100 disabled:cursor-wait disabled:opacity-70`}
    >
      <div className="mb-3 flex min-h-8 items-start justify-between gap-3">
        <h3 className="min-w-0 truncate text-base font-black text-commerce-ink">{build.title}</h3>
        <div className="flex shrink-0 items-center gap-2">
          <span className={`rounded px-2 py-1 text-[11px] font-black ${hasWarnings ? 'bg-amber-100 text-amber-700' : 'bg-commerce-sale text-white'}`}>
            {isApplying ? '적용 중' : hasWarnings ? '검증 확인' : 'AI 추천'}
          </span>
          {isApplying ? (
            <ShoppingCart size={17} className="text-commerce-sale" />
          ) : (
            <Heart size={17} className="text-slate-400 group-hover:text-commerce-sale" />
          )}
        </div>
      </div>
      <div className="mb-4 overflow-hidden rounded-md border border-commerce-line bg-slate-100">
        {casePart ? (
          <img
            src={partImageUrl(casePart)}
            alt={`${casePart.name} 제품 사진`}
            className="h-[29.9rem] w-full object-cover transition duration-300 group-hover:scale-[1.02]"
          />
        ) : (
          <div className="grid h-[29.9rem] place-items-center bg-slate-50 text-xs font-black text-slate-400">
            케이스 사진 준비 중
          </div>
        )}
      </div>
      <div className="flex flex-wrap items-end gap-2">
        <span className="text-xl font-black tracking-tight text-commerce-sale">{build.totalPrice.toLocaleString()}원</span>
        <span className="text-xs font-bold text-slate-400">{build.budgetLabel ?? build.tierLabel}</span>
      </div>
      <div className={`mt-3 flex items-center gap-2 text-xs font-black ${hasWarnings ? 'text-amber-600' : 'text-commerce-green'}`}>
        <PackageCheck size={15} />
        {isApplying ? '셀프 견적 적용 중' : hasWarnings ? 'Tool 확인 필요' : '호환성 통과'}
      </div>
    </button>
  );
}

function PromoBanner() {
  const [activeIndex, setActiveIndex] = useState(0);

  useEffect(() => {
    const timer = window.setInterval(() => {
      setActiveIndex((current) => (current + 1) % promoSlides.length);
    }, 4_000);

    return () => window.clearInterval(timer);
  }, []);

  return (
    <section className="self-start overflow-hidden rounded-lg border border-commerce-line bg-slate-950 shadow-product" aria-label="홈 광고 배너">
      <div className="relative aspect-[1460/720] min-h-[280px]">
        <div
          className="flex h-full transition-transform duration-700 ease-out"
          style={{ transform: `translateX(-${activeIndex * 100}%)` }}
        >
          {promoSlides.map((slide) => (
            <img
              key={slide.src}
              src={slide.src}
              alt={slide.alt}
              className="h-full w-full shrink-0 bg-slate-950 object-contain"
              draggable={false}
            />
          ))}
        </div>
        <div className="absolute bottom-4 left-1/2 flex -translate-x-1/2 gap-2">
          {promoSlides.map((slide, index) => (
            <button
              key={slide.src}
              type="button"
              aria-label={`${index + 1}번 광고 보기`}
              aria-current={activeIndex === index ? 'true' : undefined}
              onClick={() => setActiveIndex(index)}
              className={`h-2.5 rounded-full transition-all ${activeIndex === index ? 'w-7 bg-white' : 'w-2.5 bg-white/40 hover:bg-white/70'}`}
            />
          ))}
        </div>
      </div>
    </section>
  );
}

function QuickCategoryPanel() {
  return (
    <aside className="panel p-5 sm:p-6">
      <div className="mb-5 flex items-start justify-between gap-3">
        <div>
          <div className="text-xs font-black text-brand-blue">Part shortcut</div>
          <h2 className="mt-1 text-xl font-black text-commerce-ink">부품 바로가기</h2>
          <p className="mt-1 text-sm leading-6 text-slate-500">셀프 견적 카테고리로 바로 이동합니다.</p>
        </div>
        <Link to="/self-quote" aria-label="셀프 견적 전체 보기" className="text-xs font-black text-brand-blue hover:underline">
          전체
        </Link>
      </div>
      <div className="grid grid-cols-2 gap-2">
        {quickCategories.map((item) => (
          <Link
            key={item.label}
            aria-label={item.label}
            to={item.to}
            className="min-h-[88px] rounded-lg border border-commerce-line bg-slate-50 p-3 transition hover:-translate-y-0.5 hover:border-commerce-ink hover:bg-white hover:shadow-product focus:outline-none focus:ring-4 focus:ring-blue-100"
          >
            <div className="flex items-center gap-2 text-sm font-black text-commerce-ink">
              <item.icon size={17} className="text-brand-blue" />
              {item.label}
            </div>
            <div className="mt-2 text-[11px] leading-4 text-slate-500">{item.detail}</div>
          </Link>
        ))}
      </div>
    </aside>
  );
}

function FeaturedBuildCard({
  build,
  buildParts,
  casePart,
  assetTotalPrice,
  isApplying,
  onSelect
}: {
  build: FeaturedBuild;
  buildParts: FeaturedBuildResolvedPart[];
  casePart?: PartRow;
  assetTotalPrice: number | null;
  isApplying: boolean;
  onSelect: (build: FeaturedBuild, buildParts: FeaturedBuildResolvedPart[]) => void;
}) {
  return (
    <button
      type="button"
      onClick={() => onSelect(build, buildParts)}
      disabled={isApplying}
      aria-label={`${build.name} 셀프견적에 담기`}
      className={`group rounded-lg border border-commerce-line bg-gradient-to-br ${build.tone} p-4 text-left transition hover:-translate-y-0.5 hover:border-commerce-ink hover:shadow-product focus:outline-none focus:ring-4 focus:ring-blue-100 disabled:cursor-wait disabled:opacity-70`}
    >
      <div className="mb-3 flex min-h-8 items-start justify-between gap-3">
        <h3 className="min-w-0 truncate text-base font-black text-commerce-ink">{build.name}</h3>
        <div className="flex shrink-0 items-center gap-2">
          <span className="rounded bg-commerce-sale px-2 py-1 text-[11px] font-black text-white">{build.tag}</span>
          <Heart size={17} className="text-slate-400 group-hover:text-commerce-sale" />
        </div>
      </div>
      <div className="mb-4 overflow-hidden rounded-md border border-commerce-line bg-slate-100">
        {casePart ? (
          <img
            src={partImageUrl(casePart)}
            alt={`${casePart.name} 제품 사진`}
            className="h-[29.9rem] w-full object-cover transition duration-300 group-hover:scale-[1.02]"
          />
        ) : (
          <div className="grid h-[29.9rem] place-items-center bg-slate-50 text-xs font-black text-slate-400">
            케이스 사진 준비 중
          </div>
        )}
      </div>
      <div className="flex flex-wrap items-end gap-2">
        {assetTotalPrice !== null ? (
          <span className="text-xl font-black tracking-tight text-commerce-sale">{assetTotalPrice.toLocaleString()}원</span>
        ) : (
          <span className="text-sm font-black text-slate-400">가격 계산 중</span>
        )}
      </div>
      <div className="mt-3 flex items-center gap-2 text-xs font-black text-commerce-green">
        <PackageCheck size={15} />
        {isApplying ? '견적 담는 중' : '호환성 통과'}
      </div>
    </button>
  );
}

function aiBuildTone(build: AiRecommendedBuild) {
  if (build.warnings && build.warnings.length > 0) {
    return 'from-amber-50 via-white to-white';
  }
  if (build.tier === 'performance') {
    return 'from-indigo-50 via-white to-white';
  }
  if (build.tier === 'budget') {
    return 'from-emerald-50 via-white to-white';
  }
  return 'from-blue-50 via-white to-white';
}

function PopularPartsSection() {
  const popularPartQueries = useQueries({
    queries: popularPartDeals.map((part) => ({
      queryKey: ['parts', 'home-popular-ranking', part.category, part.searchQuery],
      queryFn: () => listParts({ category: part.category, q: part.searchQuery, page: 0, size: 1, sort: 'price_desc' }),
      staleTime: 60_000
    }))
  });

  return (
    <section className="panel p-5 sm:p-6">
      <div className="mb-5 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <div className="text-xs font-black text-brand-blue">Part ranking</div>
          <h2 className="mt-1 text-xl font-black text-commerce-ink">인기 부품 랭킹</h2>
          <p className="mt-1 text-sm text-slate-500">셀프 견적에서 자주 비교하는 내부 자산입니다.</p>
        </div>
        <Link to="/self-quote" aria-label="셀프 견적 전체 보기" className="text-sm font-black text-brand-blue hover:underline">셀프 견적 전체 보기</Link>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {popularPartDeals.map((part, index) => {
          const matchedPart = popularPartQueries[index]?.data?.items[0];
          const partDetailPath = matchedPart ? `/parts/${matchedPart.id}` : '.';

          return (
            <Link
              key={part.label}
              to={partDetailPath}
              aria-label={`인기 부품 ${part.rank}번 보기`}
              aria-disabled={matchedPart ? undefined : true}
              className={`group rounded-lg border border-commerce-line bg-white p-4 transition hover:-translate-y-0.5 hover:border-commerce-ink hover:shadow-product focus:outline-none focus:ring-4 focus:ring-blue-100 ${matchedPart ? '' : 'pointer-events-none cursor-wait opacity-70'}`}
            >
              <div className="mb-3 flex items-center justify-between">
                <span className="flex h-7 w-7 items-center justify-center rounded-full bg-commerce-ink text-xs font-black text-white">{part.rank}</span>
                <span className={`rounded px-2 py-1 text-[11px] font-black ${part.sale === 'SALE' ? 'bg-commerce-sale text-white' : 'bg-slate-100 text-slate-700'}`}>{part.sale}</span>
              </div>
              <div className="grid h-56 w-full place-items-center overflow-hidden rounded-md border border-commerce-line bg-slate-50 text-brand-blue">
                {matchedPart ? (
                  <img
                    src={partImageUrl(matchedPart)}
                    alt={`${matchedPart.name} 제품 사진`}
                    className="block h-full w-full object-contain p-3"
                  />
                ) : (
                  <part.icon size={30} />
                )}
              </div>
              <div className="mt-3 text-xs font-black text-brand-blue">{part.category}</div>
              <h3 className="mt-1 min-h-10 text-sm font-black leading-5 text-commerce-ink">{matchedPart?.name ?? part.label}</h3>
              <p className="mt-1 text-xs text-slate-500">{part.detail}</p>
              <div className="mt-3 flex items-center justify-between gap-2">
                <span className="text-lg font-black text-commerce-ink">{matchedPart ? `${matchedPart.price.toLocaleString()}원` : '가격 확인 중'}</span>
                <div className="flex items-center gap-1 text-[11px] font-bold text-amber-600">
                  <Star size={12} fill="currentColor" />
                  인기
                </div>
              </div>
            </Link>
          );
        })}
      </div>
    </section>
  );
}

function WorkflowPanel() {
  const rows = [
    { icon: Bot, title: '챗봇 추천', body: '우하단 AI 챗봇에서 3개 조합을 탭으로 비교합니다.' },
    { icon: ShoppingCart, title: '셀프 견적 이동', body: '선택한 AI 조합을 실제 견적 장바구니에 한 번에 적용합니다.' },
    { icon: SearchCheck, title: 'Tool 검증', body: '수동 장바구니의 기존 검증 진입점은 그대로 유지합니다.' },
    { icon: ShieldCheck, title: '목표가 알림', body: '구매는 결제 없이 구매처 이동 CTA로만 연결합니다.' }
  ];

  return (
    <aside className="panel p-5 sm:p-6">
      <div className="mb-5">
        <div className="text-xs font-black text-brand-blue">Scenario</div>
        <h2 className="mt-1 text-xl font-black text-commerce-ink">추천부터 알림까지</h2>
        <p className="mt-1 break-keep text-sm leading-6 text-slate-500">홈 추천은 챗봇 API와 셀프 견적 batch 적용 흐름으로 이어집니다.</p>
      </div>
      <div className="space-y-3">
        {rows.map((row) => (
          <div key={row.title} className="flex gap-3 rounded-lg border border-commerce-line bg-slate-50 p-3">
            <div className="grid h-9 w-9 shrink-0 place-items-center rounded-md bg-white text-brand-blue">
              <row.icon size={18} />
            </div>
            <div>
              <div className="text-sm font-black text-commerce-ink">{row.title}</div>
              <div className="mt-1 break-keep text-xs leading-5 text-slate-500">{row.body}</div>
            </div>
          </div>
        ))}
      </div>
    </aside>
  );
}
