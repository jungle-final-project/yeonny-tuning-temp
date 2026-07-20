import { useInfiniteQuery } from '@tanstack/react-query';
import { Eye, Heart, Search, SlidersHorizontal, X } from 'lucide-react';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router-dom';
import { handlePartImageError, partImageUrl, specRows } from '../../partDisplay';
import { listParts } from '../../partsApi';
import type { PartRow, PartSearchParams, QuoteDraftItem } from '../../types';
import { openAiAssistant } from '../../../../lib/events';
import { AI_PART_PICKS_CHANGED_EVENT, clearAiPartPicks, readAiPartPicks } from '../../../quote/aiSelection';
import { DraftQuantityStepper } from './DraftQuantityStepper';
import { FLOATING_CONTROL_STRIP_HEIGHT, isMultiItemCategory, type SlotConfig } from './slotBoardConfig';
import { useBoardDrag, useIsDesktop } from './useBoardDrag';

// CPU·GPU만 벤치마크 점수가 있어 교체 성능 비교가 의미 있다 — 그 외 카테고리는 버튼을 숨긴다.
const PERF_COMPARABLE = new Set(['CPU', 'GPU']);

const CANDIDATE_PAGE_SIZE = 20;

// 챗봇 추천을 찾으러 당겨 올 최대 장수(20건×10 = 200건). 카테고리 하나가 이보다 크면
// 추천이 목록에 없을 수 있지만, 열자마자 수십 번 요청하는 편보다 낫다.
const AI_PICK_AUTOLOAD_PAGE_LIMIT = 10;

// 데스크톱 패널 초기 위치·크기: 보드 스테이지 좌측에 떠 있던 기존 배치를 재현하되,
// 헤더 제거 리디자인 이후 스테이지 좌상단에 사는 플로팅 컨트롤(문제 칩·다음 가이드·AI 강조)을
// 가리지 않도록 그 아래(+56px)에서 시작한다. 패널은 body 포탈이라 z-index로는 스트립을 못 이긴다.
// 포탈(document.body) + position:fixed라 보드 밖으로도 드래그할 수 있다.
const PANEL_TOP_OFFSET_FOR_FLOATING_CONTROLS = FLOATING_CONTROL_STRIP_HEIGHT;

function panelInitialRect() {
  const fallback = { left: 24, top: 96, width: 420, height: 560 };
  if (typeof document === 'undefined') {
    return fallback;
  }
  const stage = document.querySelector('[data-testid="slot-board-body-stage"]')
    ?? document.querySelector('[data-testid="slot-board"]');
  const rect = stage?.getBoundingClientRect();
  if (!rect || rect.width === 0) {
    return fallback;
  }
  return {
    left: rect.left + 12,
    top: rect.top + PANEL_TOP_OFFSET_FOR_FLOATING_CONTROLS,
    width: Math.min(Math.min(520, Math.max(360, rect.width * 0.52)), rect.width) - 24,
    height: Math.max(320, rect.height - PANEL_TOP_OFFSET_FOR_FLOATING_CONTROLS - 12)
  };
}

type SlotCandidatePanelProps = {
  slot: SlotConfig;
  draftItems: QuoteDraftItem[];
  onClose: () => void;
  onAddPart: (part: PartRow) => Promise<unknown>;
  onRemoveItem: (partId: string) => void;
  onUpdateQuantity: (partId: string, quantity: number) => void;
  isMutating: boolean;
  placement?: 'board-overlay';
  /**
   * 패널을 열 때 미리 채워둘 검색어. AI가 "'9800X3D'를 하나로 특정하지 못했으니 목록에서 확인해 주세요"라고
   * 답하며 보낼 때, 그 상품명으로 걸러진 목록에 도착해야 안내가 말이 된다.
   */
  initialSearch?: string;
};

export function SlotCandidatePanel({
  slot,
  draftItems,
  onClose,
  onAddPart,
  onRemoveItem,
  onUpdateQuantity,
  isMutating,
  placement = 'board-overlay',
  initialSearch = ''
}: SlotCandidatePanelProps) {
  const [sort, setSort] = useState<PartSearchParams['sort']>('price_asc');
  const [searchInput, setSearchInput] = useState(initialSearch);
  const [q, setQ] = useState(initialSearch);
  const [manufacturer, setManufacturer] = useState('');
  const [manufacturerOptions, setManufacturerOptions] = useState<string[]>([]);
  const [minPriceInput, setMinPriceInput] = useState('');
  const [maxPriceInput, setMaxPriceInput] = useState('');
  const [minPrice, setMinPrice] = useState<number | undefined>(undefined);
  const [maxPrice, setMaxPrice] = useState<number | undefined>(undefined);
  const [hideFail, setHideFail] = useState(false);
  const [onlyWishlist, setOnlyWishlist] = useState(false);
  // 필터는 기본 접힘 — 제목 바로 아래 검색, 그 밑은 바로 후보가 되도록 자리를 비운다.
  const [filtersOpen, setFiltersOpen] = useState(false);
  // 챗봇이 이 카테고리로 추천해 열었다면 그 순서가 남아 있다. 슬롯을 바꾸면 다시 읽는다.
  const [aiPickedPartIds, setAiPickedPartIds] = useState<string[]>(() => readAiPartPicks(slot.category));
  // 챗봇에게 추천을 받아 열린 창이면 추천만 보여준다 — 전체 카탈로그를 함께 깔면
  // "추천 창"이 아니라 "부품 목록 창"으로 읽힌다. 전체는 눌러야 펼쳐진다.
  const [showAllCandidates, setShowAllCandidates] = useState(false);
  useEffect(() => {
    const sync = () => {
      setAiPickedPartIds(readAiPartPicks(slot.category));
      // 새 추천이 오면 다시 추천만 보여준다(전체를 펼쳐 둔 채였어도).
      setShowAllCandidates(false);
    };
    sync();
    // 패널이 이미 그 카테고리로 열려 있으면 챗봇이 다시 추천해도 주소가 안 바뀐다 —
    // 리마운트도 카테고리 변경도 없으므로 이 신호로만 새 순서를 안다.
    window.addEventListener(AI_PART_PICKS_CHANGED_EVENT, sync);
    return () => window.removeEventListener(AI_PART_PICKS_CHANGED_EVENT, sync);
  }, [slot.category]);
  const activeFilterCount = [manufacturer, minPriceInput, maxPriceInput].filter(Boolean).length
    + (onlyWishlist ? 1 : 0)
    + (hideFail ? 1 : 0);
  // 데스크톱 패널: 포탈+fixed로 띄워 헤더 드래그(보드 밖 허용, 화면 이탈만 방지)·꼭지점 리사이즈.
  // 모바일 바텀시트는 고정. 한 번 옮겨두면 슬롯을 바꾸거나 닫았다 열어도 그 자리를 지키고,
  // 핸들 더블클릭으로만 초기 위치·크기로 되돌린다.
  const isDesktop = useIsDesktop();
  const [initialRect, setInitialRect] = useState(() => panelInitialRect());
  const {
    targetRef: panelRef,
    dragStyle,
    isDragging,
    startDrag: startPanelDrag,
    resetDrag,
    rememberedSize
  } = useBoardDrag<HTMLElement>({
    persistKey: 'slot-candidate-panel',
    anchor: { left: initialRect.left, top: initialRect.top },
    // 자리와 크기는 새로고침·다음 방문까지 남긴다. 부품 카테고리별로 쪼개지 않는다 —
    // 사용자는 "부품 목록 창"을 하나로 인식하고, CPU에서 맞춘 자리를 케이스에서도 그대로 쓴다.
    remember: 'local',
    rememberSize: true
  });
  // URL로 페이지와 함께 마운트되면 첫 렌더 시점엔 보드가 아직 DOM에 없다 —
  // 커밋 직후(페인트 전) 실제 스테이지 위치로 다시 잰다.
  useLayoutEffect(() => {
    setInitialRect(panelInitialRect());
  }, []);
  const applyInitialSize = () => {
    const el = panelRef.current;
    if (el && typeof window !== 'undefined' && window.matchMedia('(min-width: 1024px)').matches) {
      el.style.width = `${initialRect.width}px`;
      el.style.height = `${initialRect.height}px`;
    }
  };
  const resetPanelPlacement = () => {
    resetDrag();
    applyInitialSize();
  };
  const [wishlist, setWishlist] = useState<Set<string>>(() => readWishlist());
  const [quickViewPart, setQuickViewPart] = useState<PartRow | null>(null);
  const [commitError, setCommitError] = useState<string | null>(null);

  function toggleWishlist(partId: string) {
    setWishlist((prev) => {
      const next = new Set(prev);
      if (next.has(partId)) {
        next.delete(partId);
      } else {
        next.add(partId);
      }
      writeWishlist(next);
      return next;
    });
  }

  // 검색어 디바운스(입력마다 요청하지 않는다) — 300ms 후 확정 검색어(q)를 갱신한다.
  useEffect(() => {
    const timer = setTimeout(() => setQ(searchInput.trim()), 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  // 가격 범위 디바운스 — 숫자만 추출해 300ms 후 확정한다.
  // 최소가가 최대가보다 크면(입력 실수) 두 값을 서로 바꿔 확정해, 서버 오류 대신 의도한 범위로 조회한다.
  useEffect(() => {
    const timer = setTimeout(() => {
      const parse = (value: string) => {
        const digits = value.replace(/[^0-9]/g, '');
        return digits ? Number(digits) : undefined;
      };
      const parsedMin = parse(minPriceInput);
      const parsedMax = parse(maxPriceInput);
      const shouldSwap = parsedMin !== undefined && parsedMax !== undefined && parsedMin > parsedMax;
      setMinPrice(shouldSwap ? parsedMax : parsedMin);
      setMaxPrice(shouldSwap ? parsedMin : parsedMax);
    }, 300);
    return () => clearTimeout(timer);
  }, [minPriceInput, maxPriceInput]);

  // 다른 슬롯(카테고리)으로 넘어가면 검색·필터를 초기화한다.
  // 이 이펙트는 마운트 때도 돌기 때문에, AI가 넘겨준 검색어는 빈 값이 아니라 여기서 다시 심어야 살아남는다.
  useEffect(() => {
    setSearchInput(initialSearch);
    setQ(initialSearch);
    setManufacturer('');
    setManufacturerOptions([]);
    setMinPriceInput('');
    setMaxPriceInput('');
    setMinPrice(undefined);
    setMaxPrice(undefined);
    setHideFail(false);
    setOnlyWishlist(false);
    setQuickViewPart(null);
    setCommitError(null);
  }, [slot.category, initialSearch]);
  const isMulti = isMultiItemCategory(slot.category);
  const selectedPartIds = new Set(draftItems.map((item) => item.partId));
  // 교체 성능 비교: CPU·GPU 슬롯에 현재 부품이 있으면, 후보로 바꿨을 때 성능 변화를 챗봇에 물어본다.
  const currentPart = draftItems[0];
  const canComparePerf = PERF_COMPARABLE.has(slot.category) && Boolean(currentPart);

  // RAM/SSD는 기존 구성에 후보를 더하는 ADD 기준, 단일 슬롯은 서버 기본 REPLACE 기준으로 평가한다.
  const compatibilityMode = isMulti ? 'ADD' as const : undefined;
  const { data, isLoading, isError, isFetchingNextPage, hasNextPage, fetchNextPage } = useInfiniteQuery({
    queryKey: ['parts', 'slot-candidates', slot.category, sort, q, manufacturer, minPrice, maxPrice, compatibilityMode ?? 'REPLACE'],
    queryFn: ({ pageParam }) => listParts({
      category: slot.category,
      page: pageParam,
      size: CANDIDATE_PAGE_SIZE,
      sort,
      q: q || undefined,
      manufacturer: manufacturer || undefined,
      minPrice,
      maxPrice,
      compatibilitySource: 'QUOTE_DRAFT_CURRENT',
      compatibilityMode
    }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.page + 1) * lastPage.size < lastPage.total ? lastPage.page + 1 : undefined
  });

  const pages = useMemo(() => data?.pages ?? [], [data]);
  // 챗봇 추천은 목록에 실려 있어야 위로 올릴 수 있다. 그런데 목록은 기본 가격순 20건씩이라
  // 비싼 추천(최상위 GPU 등)은 첫 장에 없다 — 그대로 두면 "부품 목록에 띄웠어요"가 거짓말이 된다.
  // 추천이 다 실릴 때까지만 다음 장을 당겨 오고, 못 찾아도 정해진 장수에서 멈춘다(무한 당김 방지).
  const loadedPartIds = useMemo(() => new Set(pages.flatMap((page) => page.items.map((item) => item.id))), [pages]);
  useEffect(() => {
    if (aiPickedPartIds.length === 0 || q) return;
    if (!hasNextPage || isFetchingNextPage || pages.length >= AI_PICK_AUTOLOAD_PAGE_LIMIT) return;
    if (aiPickedPartIds.every((partId) => loadedPartIds.has(partId))) return;
    void fetchNextPage();
  }, [aiPickedPartIds, q, hasNextPage, isFetchingNextPage, pages.length, loadedPartIds, fetchNextPage]);
  // 멘토 피드백: 비호환 후보를 숨기지 않는다 — 전부 보여주되 FAIL은 회색 비활성 + 사유를 표시해
  // 사용자가 "왜 안 되는지"를 알 수 있게 한다.
  const visibleParts = useMemo(() => pages.flatMap((page) => page.items), [pages]);

  // 제조사 필터 옵션: 공개 목록 API가 없어 로드된 후보에서 누적 수집한다(필터로 좁혀도 줄지 않게 누적).
  useEffect(() => {
    setManufacturerOptions((prev) => {
      const next = new Set(prev);
      let grew = false;
      for (const part of visibleParts) {
        const name = part.manufacturer;
        if (name && !next.has(name)) {
          next.add(name);
          grew = true;
        }
      }
      return grew ? [...next].sort((a, b) => a.localeCompare(b, 'ko')) : prev;
    });
  }, [visibleParts]);

  // 표시 필터(client-side): '장착 불가 숨기기'(FAIL 제외)와 '찜만 보기'(찜한 부품만).
  // 기본은 전부 표시(멘토 룰). 서버 검색·정렬·필터는 그대로 두고 렌더 단계에서만 좁힌다.
  const renderedParts = useMemo(() => {
    let list = hideFail ? visibleParts.filter((part) => part.compatibility?.status !== 'FAIL') : visibleParts;
    if (onlyWishlist) {
      list = list.filter((part) => wishlist.has(part.id));
    }
    // 챗봇이 골라 준 후보만 그 순서대로 보여준다. 사용자가 검색어를 치는 순간 그만둔다 —
    // 그때부터는 "챗봇이 추천한 것"이 아니라 "내가 찾는 것"이 목록의 주인이다.
    if (aiPickedPartIds.length > 0 && !q) {
      const rank = new Map(aiPickedPartIds.map((partId, index) => [partId, index]));
      if (!showAllCandidates) {
        return list
          .filter((part) => rank.has(part.id))
          .sort((left, right) => (rank.get(left.id) ?? 0) - (rank.get(right.id) ?? 0));
      }
      // 전체를 펼쳐도 추천은 맨 위에 남긴다 — 어디로 갔는지 찾게 만들지 않는다.
      list = [...list].sort((left, right) => {
        const leftRank = rank.get(left.id) ?? Number.MAX_SAFE_INTEGER;
        const rightRank = rank.get(right.id) ?? Number.MAX_SAFE_INTEGER;
        return leftRank - rightRank;
      });
    }
    return list;
  }, [visibleParts, hideFail, onlyWishlist, wishlist, aiPickedPartIds, q, showAllCandidates]);

  // 추천만 보여주는 중인가 — 헤더 문구와 '전체 목록' 버튼의 조건이자, 무한스크롤을 멈추는 조건이다.
  const showingAiPicksOnly = aiPickedPartIds.length > 0 && !q && !showAllCandidates;
  // 추천 중 아직 목록에 실리지 않은 개수(다음 장을 당겨 오는 중이면 0으로 수렴한다).
  const missingPickCount = showingAiPicksOnly
    ? aiPickedPartIds.filter((partId) => !loadedPartIds.has(partId)).length
    : 0;

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const sentinelRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const sentinel = sentinelRef.current;
    // 추천만 보여주는 동안은 목록이 짧아 센티넬이 늘 보인다 — 그대로 두면 화면에 뜨지도 않을
    // 전체 카탈로그를 뒤에서 계속 당겨 온다. 추천을 찾는 데 필요한 만큼은 위 이펙트가 당긴다.
    if (!sentinel || !hasNextPage || showingAiPicksOnly) {
      return;
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting) && !isFetchingNextPage) {
        void fetchNextPage();
      }
    });
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage, showingAiPicksOnly]);

  const commitPart = async (part: PartRow) => {
    setCommitError(null);
    try {
      await onAddPart(part);
    } catch {
      setCommitError('부품을 견적에 반영하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    }
  };

  const panelContent = (
    <>
      <div aria-hidden="true" onClick={onClose} className="fixed inset-0 z-30 bg-slate-900/40 lg:hidden" />
      <section
        ref={panelRef}
        data-testid="slot-candidate-panel"
        data-placement={placement}
        role="dialog"
        aria-label={`${slot.label} 부품 목록`}
        style={isDesktop
          ? {
              // 사용자가 맞춰 둔 크기가 있으면 그걸 쓴다(화면 밖으로 커지는 건 max-w/h 클래스가 막는다).
              width: rememberedSize?.width ?? initialRect.width,
              height: rememberedSize?.height ?? initialRect.height,
              ...dragStyle
            }
          : dragStyle}
        className="panel slot-candidate-panel slot-panel-in fixed inset-x-0 bottom-0 z-40 flex max-h-[72vh] flex-col overflow-hidden rounded-t-xl border-t border-commerce-line shadow-2xl lg:inset-auto lg:z-[55] lg:max-h-[92vh] lg:min-h-[280px] lg:w-auto lg:min-w-[320px] lg:max-w-[92vw] lg:rounded-xl lg:border lg:border-commerce-line lg:shadow-xl lg:[resize:both]"
      >
      <div
        data-testid="slot-candidate-panel-handle"
        title="드래그해서 옮기고, 더블클릭하면 원래 자리로 돌아옵니다"
        onPointerDown={startPanelDrag}
        onDoubleClick={resetPanelPlacement}
        className={`slot-candidate-panel__header flex items-start justify-between gap-3 border-b border-commerce-line px-4 py-3 ${isDragging ? 'lg:cursor-grabbing' : 'lg:cursor-grab'} select-none lg:touch-none`}
      >
        {/* 제목 아래에는 아무것도 두지 않는다 — 바로 검색이 오도록 비워 후보 목록을 위로 끌어올린다. */}
        <div className="min-w-0">
          <h2 className="text-base font-black text-commerce-ink">{slot.label} 부품 목록</h2>
        </div>
        <div className="flex items-center gap-2">
          <label className="flex items-center rounded-md border border-commerce-line bg-white px-2 py-1">
            <span className="sr-only">후보 정렬 기준</span>
            <select
              aria-label="후보 정렬 기준"
              value={sort}
              onChange={(event) => {
                setSort(event.target.value as PartSearchParams['sort']);
                // 정렬을 직접 바꿨으면 목록의 주인은 사용자다 — AI 추천 고정을 놓는다.
                // 놓지 않으면 '가격 높은순'을 골라도 맨 위 몇 줄만 그 정렬을 안 따라
                // 정렬이 고장 난 것처럼 보인다.
                if (aiPickedPartIds.length > 0) {
                  clearAiPartPicks();
                }
              }}
              className="bg-transparent text-xs font-bold text-slate-700 outline-none"
            >
              <option value="compatibility">호환 가능 우선</option>
              <option value="price_asc">가격 낮은순</option>
              <option value="price_desc">가격 높은순</option>
              <option value="name">이름순</option>
            </select>
          </label>
          {/* 필터는 정렬 옆에 둔다 — 접혀 있을 때 줄 하나를 통째로 쓰던 걸 없애 후보를 위로 끌어올린다. */}
          <button
            type="button"
            data-testid="candidate-filters-toggle"
            aria-expanded={filtersOpen}
            aria-controls="candidate-filter-controls"
            onClick={() => setFiltersOpen((open) => !open)}
            className="flex items-center gap-1.5 rounded-md border border-commerce-line bg-white px-2 py-1 text-xs font-bold text-slate-600 transition hover:border-commerce-ink hover:text-commerce-ink"
          >
            <SlidersHorizontal size={13} aria-hidden="true" />
            필터
            {activeFilterCount > 0 ? (
              <span data-testid="candidate-filters-active-count" className="rounded-full bg-brand-blue px-1.5 text-[10px] font-black text-white">{activeFilterCount}</span>
            ) : null}
          </button>
          <button
            type="button"
            aria-label="후보 패널 닫기"
            onClick={onClose}
            className="grid h-8 w-8 place-items-center rounded-md border border-commerce-line bg-white text-slate-600 hover:border-commerce-ink hover:text-commerce-ink"
          >
            <X size={15} />
          </button>
        </div>
      </div>

      {/* 검색: 이름·제조사로 후보를 좁힌다(디바운스 300ms, 호환 검사·정렬은 그대로 유지). */}
      <div data-testid="candidate-panel-search" className="slot-candidate-panel__search shrink-0 border-b border-commerce-line px-4 py-2.5">
        <div className="relative">
          <Search size={14} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            type="text"
            inputMode="search"
            data-testid="candidate-search"
            aria-label={`${slot.label} 부품 검색`}
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            placeholder={`${slot.label} 이름·제조사 검색`}
            className="h-9 w-full rounded-md border border-commerce-line bg-white pl-8 pr-8 text-xs font-bold text-commerce-ink placeholder:font-semibold placeholder:text-slate-400 focus:border-brand-blue focus:outline-none focus:ring-2 focus:ring-blue-100"
          />
          {searchInput ? (
            <button
              type="button"
              aria-label="검색어 지우기"
              onClick={() => setSearchInput('')}
              className="absolute right-1.5 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center rounded text-slate-400 hover:bg-slate-100 hover:text-slate-600"
            >
              <X size={13} />
            </button>
          ) : null}
        </div>
      </div>

      {/* 필터: 제조사·가격대(기존 GET /api/parts 파라미터 재사용) + 장착 불가 숨기기(client-side, 기본 꺼짐).
          기본 접힘 — 대부분은 검색과 목록만 쓰므로, 펼쳐 두면 후보가 그만큼 아래로 밀린다.
          걸어 둔 필터가 있으면 접힌 상태에서도 개수를 배지로 보여 준다(숨겨진 필터가 결과를 줄이는 것을 모르면 안 된다). */}
      {/* 토글 버튼은 헤더의 정렬 옆으로 옮겼다 — 접혀 있으면 이 행 자체가 사라진다. */}
      {filtersOpen ? (
      <div data-testid="candidate-panel-filters" className="slot-candidate-panel__filters shrink-0 border-b border-commerce-line px-4 py-2.5">
        <div
          id="candidate-filter-controls"
          className="slot-candidate-panel__filter-controls flex flex-wrap items-center gap-x-2 gap-y-2"
        >
          <select
            aria-label="제조사 필터"
            data-testid="candidate-manufacturer"
            value={manufacturer}
            onChange={(event) => setManufacturer(event.target.value)}
            className="h-8 max-w-[150px] rounded-md border border-commerce-line bg-white px-2 text-xs font-bold text-slate-700 outline-none focus:border-brand-blue"
          >
            <option value="">전체 제조사</option>
            {manufacturerOptions.map((name) => (
              <option key={name} value={name}>{name}</option>
            ))}
          </select>
          <div className="flex items-center gap-1">
            <input
              inputMode="numeric"
              aria-label="최소 가격"
              data-testid="candidate-min-price"
              value={minPriceInput}
              onChange={(event) => setMinPriceInput(event.target.value)}
              placeholder="최소"
              className="h-8 w-[68px] rounded-md border border-commerce-line bg-white px-2 text-right text-xs font-bold text-commerce-ink outline-none placeholder:font-semibold placeholder:text-slate-400 focus:border-brand-blue"
            />
            <span className="text-xs font-bold text-slate-400">~</span>
            <input
              inputMode="numeric"
              aria-label="최대 가격"
              data-testid="candidate-max-price"
              value={maxPriceInput}
              onChange={(event) => setMaxPriceInput(event.target.value)}
              placeholder="최대"
              className="h-8 w-[68px] rounded-md border border-commerce-line bg-white px-2 text-right text-xs font-bold text-commerce-ink outline-none placeholder:font-semibold placeholder:text-slate-400 focus:border-brand-blue"
            />
            <span className="text-xs font-bold text-slate-400">원</span>
          </div>
          <label className="ml-auto flex cursor-pointer select-none items-center gap-1.5 text-xs font-bold text-slate-600">
            <input
              type="checkbox"
              data-testid="candidate-only-wishlist"
              checked={onlyWishlist}
              onChange={(event) => setOnlyWishlist(event.target.checked)}
              className="h-3.5 w-3.5 accent-rose-500"
            />
            찜만
          </label>
          <label className="flex cursor-pointer select-none items-center gap-1.5 text-xs font-bold text-slate-600">
            <input
              type="checkbox"
              data-testid="candidate-hide-fail"
              checked={hideFail}
              onChange={(event) => setHideFail(event.target.checked)}
              className="h-3.5 w-3.5 accent-brand-blue"
            />
            장착 불가 숨기기
          </label>
        </div>
      </div>
      ) : null}

      {/* 담은 부품은 후보 피드와 독립된 관리 행으로 유지한다. RAM/SSD 수량과 제거를 여기서 바로 조작한다. */}
      {draftItems.length > 0 ? (
        <div data-testid="candidate-panel-selected" className="slot-candidate-panel__selected shrink-0 border-b border-commerce-line px-4 py-3">
          <div className="slot-candidate-panel__selected-label mb-1.5 text-[11px] font-black text-slate-500">담은 {slot.label} {draftItems.length}개</div>
          <div className="slot-candidate-panel__selected-items space-y-1.5">
            {draftItems.map((item) => (
              <div key={item.partId} className="slot-candidate-panel__selected-item flex items-center justify-between gap-2 rounded-md border border-commerce-line bg-white px-2.5 py-1.5 text-xs">
                <div className="min-w-0">
                  <Link to={`/parts/${item.partId}`} className="block truncate font-black text-commerce-ink hover:text-brand-blue hover:underline">{item.name}</Link>
                  <div className="text-[11px] text-slate-500">수량 {item.quantity} · {item.lineTotal.toLocaleString()}원</div>
                </div>
                <div className="flex shrink-0 items-center gap-1.5">
                  {isMulti ? (
                    <DraftQuantityStepper item={item} disabled={isMutating} onChange={onUpdateQuantity} />
                  ) : null}
                  <button
                    type="button"
                    aria-label={`${item.name} 견적에서 제거`}
                    disabled={isMutating}
                    onClick={() => onRemoveItem(item.partId)}
                    className="rounded-md border border-commerce-line bg-white px-2 py-1 font-black text-slate-600 hover:border-commerce-sale hover:text-commerce-sale disabled:cursor-wait"
                  >
                    제거
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {commitError ? (
        <div data-testid="candidate-commit-error" className="mx-4 mt-3 shrink-0 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs font-bold text-red-700">
          {commitError}
        </div>
      ) : null}

      {/* 챗봇에게 추천을 받아 열린 창은 추천만 보여준다 — 전체 카탈로그가 함께 깔리면
          "추천 창"이 아니라 "부품 목록 창"으로 읽힌다. 전체는 눌러야 펼쳐진다. */}
      {aiPickedPartIds.length > 0 && !q ? (
        <div data-testid="candidate-ai-picks-banner" className="shrink-0 border-b border-commerce-line bg-blue-50/60 px-4 py-2.5">
          <div className="flex items-center justify-between gap-2">
            <div className="min-w-0 text-xs font-black text-brand-blue">
              챗봇이 고른 {slot.label} 추천 {aiPickedPartIds.length}개
              {missingPickCount > 0 ? <span className="ml-1 font-bold text-slate-500">· 불러오는 중…</span> : null}
            </div>
            <button
              type="button"
              data-testid="candidate-toggle-all"
              onClick={() => setShowAllCandidates((open) => !open)}
              className="shrink-0 rounded-md border border-commerce-line bg-white px-2 py-1 text-xs font-bold text-slate-600 transition hover:border-commerce-ink hover:text-commerce-ink"
            >
              {showAllCandidates ? '추천만 보기' : `전체 ${slot.label} 목록 보기`}
            </button>
          </div>
        </div>
      ) : null}

      {/* 후보 목록만 스크롤하고 보드 자체의 크기에는 영향을 주지 않는다. */}
      <div data-testid="slot-candidate-list" className="slot-candidate-list min-h-0 flex-1 overflow-y-auto p-4">
        {isLoading ? (
          <div className="rounded-md border border-commerce-line p-4 text-sm text-slate-500">후보 목록을 불러오는 중입니다.</div>
        ) : null}
        {isError && pages.length === 0 ? (
          <div className="rounded-md border border-orange-200 bg-orange-50 p-4 text-sm text-orange-700">후보 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.</div>
        ) : null}

        <div className="space-y-2">
          {renderedParts.map((part) => {
            const isSelected = selectedPartIds.has(part.id);
            const isWishlisted = wishlist.has(part.id);
            const isFail = part.compatibility?.status === 'FAIL';
            const failReason = isFail
              ? part.compatibility?.summary || part.compatibility?.statusLabel || '현재 견적과 호환되지 않습니다'
              : null;
            const actionLabel = isMulti || draftItems.length === 0 ? `${part.name} 담기` : `${part.name} 교체`;
            const actionText = isMulti || draftItems.length === 0 ? '담기' : '교체';
            return (
              <article
                key={part.id}
                data-testid="slot-candidate-card"
                data-compat={part.compatibility?.status ?? 'NONE'}
                data-recommended={part.recommendation?.recommended ? 'true' : 'false'}
                className={`flex items-center gap-3 rounded-md border p-2.5 ${
                  isFail ? 'border-red-200 bg-red-50/40' : 'border-commerce-line bg-white'
                }`}
              >
                {/* 제품 사진·이름을 누르면 상세 페이지로 이동한다(담기와 구분되는 진입점 — 살아있던 상세 페이지 재연결). */}
                <Link to={`/parts/${part.id}`} aria-label={`${part.name} 상세 페이지 보기`} className="shrink-0">
                  <img
                    data-testid="candidate-part-image"
                    src={partImageUrl(part)}
                    alt={`${part.name} 제품 사진`}
                    loading="lazy"
                    decoding="async"
                    onError={(event) => handlePartImageError(event, part.category)}
                    className="h-[72px] w-[72px] rounded-md border border-commerce-line bg-slate-50 object-contain p-1 transition hover:opacity-90"
                  />
                </Link>
                <div className="min-w-0 flex-1 text-xs">
                  <Link to={`/parts/${part.id}`} className="line-clamp-2 font-black leading-4 text-commerce-ink hover:text-brand-blue hover:underline">
                    {part.name}
                  </Link>
                  <div className="mt-0.5 text-[11px] text-slate-500">
                    {part.manufacturer ?? '-'}
                    {part.externalOffer?.supplierName ? ` · ${part.externalOffer.supplierName}` : ''}
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-1.5">
                    {part.recommendation?.recommended ? (
                      <span
                        data-testid="candidate-recommendation-badge"
                        className="rounded-full border border-orange-200 bg-orange-50 px-1.5 py-0.5 text-[10px] font-black text-orange-700"
                      >
                        추천 {part.recommendation.rank}
                      </span>
                    ) : null}
                    <span className="font-black text-commerce-ink">{part.price.toLocaleString()}원</span>
                    {part.compatibility?.status === 'WARN' ? (
                      <span className="rounded border border-amber-100 bg-amber-50 px-1.5 py-0.5 text-[10px] font-black text-amber-700">간섭 주의</span>
                    ) : null}
                    {isFail ? (
                      <span className="rounded border border-red-200 bg-red-50 px-1.5 py-0.5 text-[10px] font-black text-red-700">장착 불가</span>
                    ) : null}
                  </div>
                  {part.recommendation?.recommended && part.recommendation.reasons[0] ? (
                    <div data-testid="candidate-recommendation-reason" className="mt-0.5 line-clamp-1 text-[10px] font-semibold text-orange-700">
                      {part.recommendation.reasons[0]}
                    </div>
                  ) : null}
                  {part.compatibility?.status === 'WARN' && part.compatibility.summary ? (
                    <div className="mt-0.5 line-clamp-1 text-[10px] text-slate-500">{part.compatibility.summary}</div>
                  ) : null}
                  {failReason ? (
                    <div className="mt-0.5 line-clamp-2 text-[10px] font-bold text-red-600">{failReason}</div>
                  ) : null}
                </div>
                <div className="flex shrink-0 flex-col items-stretch gap-1.5">
                  {/* 빠른보기(상세 스펙 모달) + 찜(로컬 저장) */}
                  <div className="flex items-center justify-end gap-1">
                    <button
                      type="button"
                      aria-label={`${part.name} 빠른보기`}
                      data-testid="candidate-quick-view"
                      onClick={() => setQuickViewPart(part)}
                      className="grid h-7 w-7 place-items-center rounded-md border border-commerce-line bg-white text-slate-500 hover:border-commerce-ink hover:text-commerce-ink"
                    >
                      <Eye size={14} />
                    </button>
                    <button
                      type="button"
                      aria-label={isWishlisted ? `${part.name} 찜 해제` : `${part.name} 찜하기`}
                      aria-pressed={isWishlisted}
                      data-testid="candidate-wishlist"
                      onClick={() => toggleWishlist(part.id)}
                      className={`grid h-7 w-7 place-items-center rounded-md border bg-white transition ${
                        isWishlisted ? 'border-rose-200 text-rose-500' : 'border-commerce-line text-slate-400 hover:text-rose-500'
                      }`}
                    >
                      <Heart size={14} className={isWishlisted ? 'fill-rose-500' : ''} />
                    </button>
                  </div>
                  {/* 비호환(FAIL)도 담을 수 있다 — 왜 안 되는지 보드에서 빨강으로 보고 교체하는 UX(구매는 여전히 차단). */}
                  <button
                    type="button"
                    aria-label={isFail ? `${actionLabel} (장착 불가 — 담아서 확인)` : actionLabel}
                    disabled={isMutating || isSelected}
                    onClick={() => void commitPart(part)}
                    className={`rounded-md px-2.5 py-2 text-xs font-black transition disabled:cursor-not-allowed ${
                      isSelected
                        ? 'border border-commerce-line bg-slate-50 text-slate-400 disabled:opacity-60'
                        : isFail
                          ? 'border border-red-300 bg-white text-red-600 hover:bg-red-50 disabled:opacity-60'
                          : 'bg-commerce-ink text-white hover:bg-slate-700 disabled:opacity-60'
                    }`}
                  >
                    {isSelected ? '장착됨' : actionText}
                  </button>
                  {/* 교체 성능 비교: 현재 부품 → 후보 시뮬레이션을 챗봇에 프리필(읽기 전용, 드래프트 무변경). */}
                  {canComparePerf && !isSelected ? (
                    <button
                      type="button"
                      data-testid="candidate-perf-compare"
                      aria-label={`현재 ${currentPart.name}을(를) ${part.name}(으)로 바꾸면 성능 비교`}
                      onClick={() => openAiAssistant({
                        prefill: `현재 ${currentPart.name}을(를) ${part.name}(으)로 바꾸면 성능이 어떻게 달라져?`,
                        autoSubmit: true
                      })}
                      className="rounded-md border border-brand-blue/30 bg-blue-50 px-2.5 py-1 text-[10px] font-black text-brand-blue transition hover:bg-blue-100"
                    >
                      성능 비교
                    </button>
                  ) : null}
                </div>
              </article>
            );
          })}
        </div>

        {!isLoading && renderedParts.length === 0 && !hasNextPage ? (
          <div className="mt-2 rounded-md border border-dashed border-slate-300 p-4 text-center text-xs font-bold text-slate-500">
            {q
              ? `'${q}' 검색 결과가 없습니다.`
              : onlyWishlist
                ? '찜한 부품이 없습니다. 하트를 눌러 찜해 보세요.'
                : hideFail && visibleParts.length > 0
                  ? '장착 가능한 후보가 없습니다. 필터를 조정해 보세요.'
                  : '표시할 후보가 없습니다.'}
          </div>
        ) : null}

        <div ref={sentinelRef} aria-hidden="true" className="h-1" />
        {hasNextPage ? (
          <button
            type="button"
            disabled={isFetchingNextPage}
            onClick={() => fetchNextPage()}
            className="mt-3 w-full rounded-md border border-commerce-line bg-white px-3 py-2 text-xs font-black text-slate-700 hover:border-commerce-ink disabled:cursor-wait disabled:text-slate-400"
          >
            {isFetchingNextPage ? '후보 불러오는 중' : '후보 더 보기'}
          </button>
        ) : null}
      </div>
      </section>
      {quickViewPart ? (
        <PartQuickView
          part={quickViewPart}
          isWishlisted={wishlist.has(quickViewPart.id)}
          onToggleWishlist={() => toggleWishlist(quickViewPart.id)}
          onClose={() => setQuickViewPart(null)}
        />
      ) : null}
    </>
  );

  // 데스크톱은 body 포탈 — overflow-hidden인 보드 스테이지를 벗어나 화면 어디로든 옮길 수 있다.
  return isDesktop ? createPortal(panelContent, document.body) : panelContent;
}

const WISHLIST_KEY = 'buildgraph.wishlist';

function readWishlist(): Set<string> {
  try {
    const raw = localStorage.getItem(WISHLIST_KEY);
    const parsed = raw ? (JSON.parse(raw) as unknown) : [];
    return new Set(Array.isArray(parsed) ? parsed.filter((id): id is string => typeof id === 'string') : []);
  } catch {
    return new Set();
  }
}

function writeWishlist(set: Set<string>) {
  try {
    localStorage.setItem(WISHLIST_KEY, JSON.stringify([...set]));
  } catch {
    // localStorage 접근 불가(프라이빗 모드 등)면 무시한다.
  }
}

// 부품 빠른보기: 로드된 후보 데이터로 상세 스펙·가격·구매처를 모달로 즉시 보여준다(추가 요청 없음).
function PartQuickView({
  part,
  isWishlisted,
  onToggleWishlist,
  onClose
}: {
  part: PartRow;
  isWishlisted: boolean;
  onToggleWishlist: () => void;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const rows = specRows(part);
  const status = part.compatibility?.status;

  return (
    <div
      className="fixed inset-0 z-[60] flex items-end justify-center bg-slate-900/50 sm:items-center sm:p-4"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-label={`${part.name} 상세`}
        data-testid="part-quick-view"
        onClick={(event) => event.stopPropagation()}
        className="max-h-[85vh] w-full max-w-md overflow-y-auto rounded-t-xl bg-white p-4 shadow-2xl sm:rounded-xl"
      >
        <div className="flex items-start justify-between gap-3">
          <h3 className="text-base font-black leading-5 text-commerce-ink">{part.name}</h3>
          <button type="button" aria-label="빠른보기 닫기" onClick={onClose} className="grid h-8 w-8 shrink-0 place-items-center rounded-md border border-commerce-line bg-white text-slate-600 hover:border-commerce-ink">
            <X size={15} />
          </button>
        </div>
        <div className="mt-3 flex gap-3">
          <img src={partImageUrl(part)} alt={`${part.name} 제품 사진`} onError={(event) => handlePartImageError(event, part.category)} className="h-20 w-20 shrink-0 rounded-md border border-commerce-line bg-slate-50 object-contain p-1" />
          <div className="min-w-0 flex-1 text-xs">
            <div className="text-[11px] font-bold text-slate-500">{part.manufacturer ?? '-'}</div>
            <div className="mt-1 text-lg font-black text-commerce-ink">{part.price.toLocaleString()}원</div>
            <div className="mt-1 flex flex-wrap items-center gap-1.5">
              {status === 'WARN' ? <span className="rounded border border-amber-100 bg-amber-50 px-1.5 py-0.5 text-[10px] font-black text-amber-700">간섭 주의</span> : null}
              {status === 'FAIL' ? <span className="rounded border border-red-200 bg-red-50 px-1.5 py-0.5 text-[10px] font-black text-red-700">장착 불가</span> : null}
            </div>
            {status !== 'PASS' && part.compatibility?.summary ? (
              <div className="mt-1 text-[11px] leading-4 text-slate-500">{part.compatibility.summary}</div>
            ) : null}
          </div>
        </div>
        {rows.length > 0 ? (
          <dl className="mt-3 grid grid-cols-2 gap-1.5">
            {rows.map((row) => (
              <div key={row.label} className="rounded-md border border-commerce-line bg-slate-50 px-2 py-1.5">
                <dt className="text-[10px] font-bold text-slate-400">{row.label}</dt>
                <dd className="text-[11px] font-black text-commerce-ink">{row.value}</dd>
              </div>
            ))}
          </dl>
        ) : (
          <p className="mt-3 rounded-md border border-dashed border-slate-200 p-3 text-center text-[11px] font-bold text-slate-400">등록된 상세 스펙이 없습니다.</p>
        )}
        <div className="mt-4 flex items-center gap-2">
          <button
            type="button"
            data-testid="quick-view-wishlist"
            aria-pressed={isWishlisted}
            onClick={onToggleWishlist}
            className={`inline-flex min-h-9 flex-1 items-center justify-center gap-1.5 rounded-md border px-3 text-xs font-black transition ${
              isWishlisted ? 'border-rose-200 bg-rose-50 text-rose-700' : 'border-commerce-line bg-white text-slate-700 hover:text-rose-600'
            }`}
          >
            <Heart size={14} className={isWishlisted ? 'fill-rose-500 text-rose-500' : ''} /> {isWishlisted ? '찜 해제' : '찜하기'}
          </button>
          {part.externalOffer?.offerUrl ? (
            <a
              href={part.externalOffer.offerUrl}
              target="_blank"
              rel="noreferrer noopener"
              className="inline-flex min-h-9 flex-1 items-center justify-center gap-1.5 rounded-md bg-commerce-ink px-3 text-xs font-black text-white hover:bg-slate-700"
            >
              구매처에서 보기
            </a>
          ) : null}
        </div>
      </div>
    </div>
  );
}
