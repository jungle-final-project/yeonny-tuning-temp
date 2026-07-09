import { useEffect, useRef, useState, type CSSProperties } from 'react';
import type { BuildGraphResolveResponse, PartCategory } from '../../../quote/aiSelection';
import { partShortSpec } from '../../partDisplay';
import type { QuoteDraftItem } from '../../types';
import {
  FALLBACK_EDGES,
  SLOT_BOARD_ISO_CALLOUT_LAYOUTS,
  SLOT_BOARD_ISO_EDGES,
  SLOT_BOARD_ISO_SCENE,
  SLOT_BOARD_ISO_SCENE_HIGHLIGHT,
  SLOT_BOARD_ART_VIEWBOX,
  SLOT_CONFIGS,
  SLOT_ISO_ART,
  isMultiItemCategory,
  slotConfigFor,
  slotIsoCalloutLayout,
  slotOrderNumber,
  type SlotConfig,
  type SlotEdgeConfig
} from './slotBoardConfig';

export type SlotBoardVisualMode = 'motherboard' | 'isometric';

export type ConnectorAnchorPoint = { x: number; y: number };
export type ConnectorAnchors = Record<string, { card: ConnectorAnchorPoint; part: ConnectorAnchorPoint }>;

const SLOT_BOARD_OVERLAYS_VISIBLE_STORAGE_KEY = 'buildgraph.selfQuote.slotBoardOverlaysVisible';
const SLOT_BOARD_EDGES_VISIBLE_STORAGE_KEY = 'buildgraph.selfQuote.slotBoardEdgesVisible';
const SLOT_BOARD_CARDS_VISIBLE_STORAGE_KEY = 'buildgraph.selfQuote.slotBoardCardsVisible';
const SLOT_BOARD_LEGACY_DISPLAY_STORAGE_KEYS = [
  SLOT_BOARD_EDGES_VISIBLE_STORAGE_KEY,
  SLOT_BOARD_CARDS_VISIBLE_STORAGE_KEY
];

type SlotBoardProps = {
  items: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  nextCategory?: PartCategory | null;
  visualMode?: SlotBoardVisualMode;
  onVisualModeChange?: (mode: SlotBoardVisualMode) => void;
  onClearSelection?: () => void;
  onSlotSelect: (category: PartCategory) => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
  graph?: BuildGraphResolveResponse;
  connectorAnchors?: ConnectorAnchors;
};

export function SlotBoard({
  items,
  selectedCategory,
  nextCategory,
  visualMode = 'motherboard',
  onVisualModeChange,
  onClearSelection,
  onSlotSelect,
  onRemoveItem,
  isRemovePending,
  graph,
  connectorAnchors
}: SlotBoardProps) {
  const statusByCategory = partStatusByCategory(graph);
  const [overlaysVisible, setOverlaysVisible] = useState(readSlotBoardOverlaysVisible);
  // 카테고리별 장착 플래시를 보드 수준에서 계산해 카드(꽂힘 모션)와 관계선(draw-in·포트 점등)이 함께 반응한다.
  const flashingCategories = useAttachFlashByCategory(items);
  const isIsometric = visualMode === 'isometric';

  useEffect(() => {
    if (isIsometric) {
      writeSlotBoardOverlaysVisible(overlaysVisible);
    }
  }, [isIsometric, overlaysVisible]);

  return (
    <div className="panel slot-board-panel overflow-hidden">
      {/* 보드 헤더: 제목 + 호환 상태 범례(초록/노랑/빨강/회색) */}
      <div className="border-b border-commerce-line bg-gradient-to-b from-white to-slate-50 px-4 py-2.5">
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs font-black text-slate-700">구성 관계도 — 부품 간 호환 상태</span>
          <SlotBoardModeSwitch
            checked={isIsometric}
            onToggle={() => onVisualModeChange?.(isIsometric ? 'motherboard' : 'isometric')}
          />
        </div>
        <div className="mt-2 flex flex-wrap items-center justify-end gap-3 text-[10px] font-bold text-slate-500">
          {isIsometric ? (
            <SlotBoardDisplaySwitch
              label="보드 정보 표시"
              checked={overlaysVisible}
              onToggle={() => setOverlaysVisible((visible) => !visible)}
              onText="정보 켜짐"
              offText="정보 꺼짐"
            />
          ) : null}
          <span className="flex items-center gap-1.5">
            <svg width="20" height="4" viewBox="0 0 20 4" aria-hidden="true"><line x1="0" y1="2" x2="20" y2="2" stroke="#16a34a" strokeWidth="3" /></svg>
            호환 가능
          </span>
          <span className="flex items-center gap-1.5">
            <svg width="20" height="4" viewBox="0 0 20 4" aria-hidden="true"><line x1="0" y1="2" x2="20" y2="2" stroke="#d97706" strokeWidth="3" /></svg>
            주의
          </span>
          <span className="flex items-center gap-1.5">
            <svg width="20" height="4" viewBox="0 0 20 4" aria-hidden="true"><line x1="0" y1="2" x2="20" y2="2" stroke="#ef4444" strokeWidth="3" strokeDasharray="6 4" /></svg>
            장착 불가
          </span>
          <span className="flex items-center gap-1.5">
            <svg width="20" height="4" viewBox="0 0 20 4" aria-hidden="true"><line x1="0" y1="2" x2="20" y2="2" stroke="#94a3b8" strokeWidth="2" strokeDasharray="4 3" /></svg>
            미장착
          </span>
        </div>
      </div>
      {isIsometric ? (
        <IsometricSlotBoardBody
          items={items}
          selectedCategory={selectedCategory}
          nextCategory={nextCategory}
          onSlotSelect={onSlotSelect}
          onClearSelection={onClearSelection}
          onRemoveItem={onRemoveItem}
          isRemovePending={isRemovePending}
          graph={graph}
          statusByCategory={statusByCategory}
          flashingCategories={flashingCategories}
          overlaysVisible={overlaysVisible}
          connectorAnchors={connectorAnchors}
        />
      ) : (
        <MotherboardSlotBoardBody
          items={items}
          selectedCategory={selectedCategory}
          nextCategory={nextCategory}
          onSlotSelect={onSlotSelect}
          onRemoveItem={onRemoveItem}
          isRemovePending={isRemovePending}
          graph={graph}
          statusByCategory={statusByCategory}
          flashingCategories={flashingCategories}
        />
      )}
    </div>
  );
}

function SlotBoardModeSwitch({
  checked,
  onToggle
}: {
  checked: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-label="3D UI 보기"
      aria-checked={checked}
      onClick={onToggle}
      className={`hidden items-center gap-1.5 rounded-full border px-2 py-1 text-[10px] font-black transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue lg:inline-flex ${
        checked
          ? 'border-blue-200 bg-blue-50 text-brand-blue'
          : 'border-slate-200 bg-white text-slate-500 hover:border-slate-300'
      }`}
    >
      <span
        aria-hidden="true"
        className={`relative h-3.5 w-6 rounded-full transition ${checked ? 'bg-brand-blue' : 'bg-slate-300'}`}
      >
        <span
          className={`absolute top-0.5 h-2.5 w-2.5 rounded-full bg-white shadow-sm transition ${
            checked ? 'left-3' : 'left-0.5'
          }`}
        />
      </span>
      3D UI 보기
    </button>
  );
}

function SlotBoardDisplaySwitch({
  label,
  checked,
  onToggle,
  onText,
  offText
}: {
  label: string;
  checked: boolean;
  onToggle: () => void;
  onText: string;
  offText: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-label={label}
      aria-checked={checked}
      onClick={onToggle}
      className={`hidden items-center gap-1.5 rounded-full border px-2 py-1 text-[10px] font-black transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue lg:inline-flex ${
        checked
          ? 'border-blue-200 bg-blue-50 text-brand-blue'
          : 'border-slate-200 bg-white text-slate-500 hover:border-slate-300'
      }`}
    >
      <span
        aria-hidden="true"
        className={`relative h-3.5 w-6 rounded-full transition ${checked ? 'bg-brand-blue' : 'bg-slate-300'}`}
      >
        <span
          className={`absolute top-0.5 h-2.5 w-2.5 rounded-full bg-white shadow-sm transition ${
            checked ? 'left-3' : 'left-0.5'
          }`}
        />
      </span>
      {checked ? onText : offText}
    </button>
  );
}

function MotherboardSlotBoardBody({
  items,
  selectedCategory,
  nextCategory,
  onSlotSelect,
  onRemoveItem,
  isRemovePending,
  graph,
  statusByCategory,
  flashingCategories
}: {
  items: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  nextCategory?: PartCategory | null;
  onSlotSelect: (category: PartCategory) => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
  graph?: BuildGraphResolveResponse;
  statusByCategory: Map<string, 'PASS' | 'WARN' | 'FAIL'>;
  flashingCategories: Set<PartCategory>;
}) {
  return (
    // 보드 본체 — 실장도: 추상 메인보드 평면도의 실장 지점(소켓/DIMM/PCIe/M.2)에 부품이 꽂히고,
    // 보드에 안 꽂히는 부품은 케이스 좌상·파워 좌하·쿨러 상단(소켓 위)에 도킹된다.
    <div
      data-testid="slot-board"
      data-visual-mode="motherboard"
      className="slot-board-tray relative flex flex-col gap-2 p-3 lg:block lg:aspect-[16/10] lg:overflow-hidden lg:p-0"
    >
      <BoardPlanArt />
      <SlotBoardEdges
        items={items}
        graph={graph}
        selectedCategory={selectedCategory}
        hoveredCategory={null}
        flashingCategories={flashingCategories}
        visualMode="motherboard"
      />
      {SLOT_CONFIGS.map((slot) => (
        <MotherboardSlot
          key={slot.category}
          slot={slot}
          layout={slot.layout}
          items={items.filter((item) => item.category === slot.category)}
          problemStatus={statusByCategory.get(slot.category)}
          isSelected={selectedCategory === slot.category}
          isNext={nextCategory === slot.category}
          isFlashing={flashingCategories.has(slot.category)}
          onSelect={() => onSlotSelect(slot.category)}
          onRemoveItem={onRemoveItem}
          isRemovePending={isRemovePending}
        />
      ))}
    </div>
  );
}

function IsometricSlotBoardBody({
  items,
  selectedCategory,
  nextCategory,
  onSlotSelect,
  onClearSelection,
  onRemoveItem,
  isRemovePending,
  graph,
  statusByCategory,
  flashingCategories,
  overlaysVisible,
  connectorAnchors
}: {
  items: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  nextCategory?: PartCategory | null;
  onSlotSelect: (category: PartCategory) => void;
  onClearSelection?: () => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
  graph?: BuildGraphResolveResponse;
  statusByCategory: Map<string, 'PASS' | 'WARN' | 'FAIL'>;
  flashingCategories: Set<PartCategory>;
  overlaysVisible: boolean;
  connectorAnchors?: ConnectorAnchors;
}) {
  const problemDetailsByCategory = slotProblemDetailsByCategory(graph);
  const [activeProblemCategory, setActiveProblemCategory] = useState<PartCategory | null>(null);
  const [hoveredCategory, setHoveredCategory] = useState<PartCategory | null>(null);
  const focusCategory = hoveredCategory ?? selectedCategory;
  const isMotherboardSceneFocused = focusCategory === 'MOTHERBOARD' || selectedCategory === 'MOTHERBOARD';
  const celebrating = useCompletionCelebration(items, statusByCategory);
  const activeProblem = activeProblemCategory ? problemDetailsByCategory.get(activeProblemCategory) : undefined;

  useEffect(() => {
    if (activeProblemCategory && !problemDetailsByCategory.has(activeProblemCategory)) {
      setActiveProblemCategory(null);
    }
  }, [activeProblemCategory, problemDetailsByCategory]);

  useEffect(() => {
    if (!activeProblem) {
      return;
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setActiveProblemCategory(null);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [activeProblem]);

  const openProblemDetail = (category: PartCategory) => {
    if (problemDetailsByCategory.has(category)) {
      setActiveProblemCategory(category);
    }
  };

  const showReplacementCandidates = (category: PartCategory) => {
    setActiveProblemCategory(null);
    onSlotSelect(category);
  };

  return (
    <div
      data-testid="slot-board"
      data-visual-mode="isometric"
      data-celebrating={celebrating ? 'true' : 'false'}
      onClick={(event) => {
        if (event.target === event.currentTarget && selectedCategory) {
          onClearSelection?.();
        }
      }}
      className="relative flex flex-col gap-2 bg-slate-50/60 p-3 lg:block lg:aspect-[16/8.4] lg:overflow-hidden lg:bg-[#f6fbff] lg:p-4"
    >
      <div
        data-testid="slot-board-motherboard-art"
        aria-hidden="true"
        className="pointer-events-none absolute inset-2 z-0 hidden rounded-lg bg-contain bg-center bg-no-repeat lg:block"
        style={{ backgroundImage: `url(${SLOT_BOARD_ISO_SCENE})` }}
      />
      <div
        data-testid="slot-board-motherboard-highlight"
        data-active={isMotherboardSceneFocused ? 'true' : 'false'}
        aria-hidden="true"
        className="slot-board-iso-scene-highlight pointer-events-none absolute inset-2 z-[1] hidden rounded-lg bg-contain bg-center bg-no-repeat lg:block"
        style={{ backgroundImage: `url(${SLOT_BOARD_ISO_SCENE_HIGHLIGHT})` }}
      />
      <IsoPartLayer
        items={items}
        flashingCategories={flashingCategories}
        statusByCategory={statusByCategory}
        problemDetailsByCategory={problemDetailsByCategory}
        hoveredCategory={hoveredCategory}
        selectedCategory={selectedCategory}
        focusCategory={focusCategory}
        onHoverChange={setHoveredCategory}
        onSlotSelect={onSlotSelect}
        onProblemOpen={openProblemDetail}
      />
      <IsoCardConnector
        selectedCategory={selectedCategory}
        status={selectedCategory ? statusByCategory.get(selectedCategory) ?? 'PENDING' : 'PENDING'}
        anchors={connectorAnchors}
      />
      {SLOT_CONFIGS.map((slot) => (
        <IsometricSlotCard
          key={slot.category}
          slot={slot}
          layout={slotIsoCalloutLayout(slot)}
          items={items.filter((item) => item.category === slot.category)}
          problemStatus={statusByCategory.get(slot.category)}
          problemDetail={problemDetailsByCategory.get(slot.category)}
          isSelected={selectedCategory === slot.category}
          isNext={nextCategory === slot.category}
          isFlashing={flashingCategories.has(slot.category)}
          isHovered={hoveredCategory === slot.category}
          cardsVisible={overlaysVisible}
          onHoverChange={setHoveredCategory}
          onSelect={() => onSlotSelect(slot.category)}
          onProblemOpen={openProblemDetail}
          onRemoveItem={onRemoveItem}
          isRemovePending={isRemovePending}
        />
      ))}
      {activeProblem ? (
        <SlotProblemPopover
          detail={activeProblem}
          onClose={() => setActiveProblemCategory(null)}
          onShowCandidates={() => showReplacementCandidates(activeProblem.category)}
        />
      ) : null}
      {celebrating ? (
        <div
          data-testid="slot-board-celebration"
          aria-hidden="true"
          className="slot-board-celebration pointer-events-none absolute inset-0 z-[15] hidden lg:block"
        />
      ) : null}
    </div>
  );
}

function useCompletionCelebration(items: QuoteDraftItem[], statusByCategory: Map<string, 'PASS' | 'WARN' | 'FAIL'>) {
  const filledCount = new Set(items.map((item) => item.category)).size;
  const hasItems = items.length > 0;
  const isComplete =
    filledCount === SLOT_CONFIGS.length &&
    SLOT_CONFIGS.every((slot) => (statusByCategory.get(slot.category) ?? 'PASS') === 'PASS');
  const previousRef = useRef<{ hadItems: boolean; complete: boolean } | null>(null);
  const [celebrating, setCelebrating] = useState(false);

  useEffect(() => {
    const previous = previousRef.current;
    previousRef.current = { hadItems: hasItems, complete: isComplete };
    if (!previous || !previous.hadItems || previous.complete || !isComplete) {
      return;
    }
    setCelebrating(true);
    const timer = window.setTimeout(() => setCelebrating(false), 2000);
    return () => window.clearTimeout(timer);
  }, [hasItems, isComplete]);

  return celebrating;
}

function IsometricSlotCard({
  slot,
  layout,
  items,
  problemStatus,
  problemDetail,
  isSelected,
  isNext,
  isFlashing,
  isHovered,
  cardsVisible,
  onHoverChange,
  onSelect,
  onProblemOpen,
  onRemoveItem,
  isRemovePending
}: {
  slot: SlotConfig;
  layout: SlotConfig['layout'];
  items: QuoteDraftItem[];
  problemStatus?: 'PASS' | 'WARN' | 'FAIL';
  problemDetail?: SlotProblemDetail;
  isSelected: boolean;
  isNext: boolean;
  isFlashing: boolean;
  isHovered: boolean;
  cardsVisible: boolean;
  onHoverChange: (category: PartCategory | null) => void;
  onSelect: () => void;
  onProblemOpen: (category: PartCategory) => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
}) {
  const filled = items.length > 0;
  const primaryItem = items[0];
  const orderNumber = slotOrderNumber(slot.category);
  const slotStatus = filled ? problemStatus ?? 'PASS' : 'NONE';
  const layoutVars: CSSProperties = {
    ['--sx' as string]: `${layout.x}%`,
    ['--sy' as string]: `${layout.y}%`,
    ['--sw' as string]: `${layout.w}%`,
    ['--sh' as string]: `${layout.h}%`
  };
  const borderClass = isSelected
    ? 'border-2 border-brand-blue ring-2 ring-blue-100 shadow-lg'
    : slotStatus === 'FAIL'
      ? 'border-2 border-red-500 ring-2 ring-red-300'
      : slotStatus === 'WARN'
        ? 'border-2 border-amber-400 ring-2 ring-amber-300'
        : filled
          ? 'border-2 border-emerald-400 ring-2 ring-emerald-300 hover:border-emerald-500'
          : isNext
            ? 'border-2 border-brand-blue bg-blue-50/40 hover:border-blue-600'
            : 'border border-dashed border-slate-300 bg-white/75 hover:border-brand-blue';
  const surfaceClass = filled
    ? slotStatus === 'FAIL'
      ? 'bg-red-100'
      : slotStatus === 'WARN'
        ? 'bg-amber-100'
        : 'bg-emerald-100'
    : 'bg-white/95';
  const visibleName = filled
    ? items.length > 1 ? `${primaryItem.name} 외 ${items.length - 1}개` : primaryItem.name
    : '';
  const visibleSpec = filled ? partShortSpec(primaryItem) : '';

  return (
    <div
      data-testid={`slot-${slot.category}`}
      data-selected={isSelected ? 'true' : 'false'}
      data-status={slotStatus}
      data-flash={isFlashing ? 'true' : 'false'}
      style={layoutVars}
      data-next={isNext ? 'true' : 'false'}
      data-hovered={isHovered ? 'true' : 'false'}
      title={filled ? visibleName : undefined}
      onMouseEnter={() => onHoverChange(slot.category)}
      onMouseLeave={() => onHoverChange(null)}
      onClick={(event) => event.stopPropagation()}
      className={`group relative z-20 rounded-lg p-2 text-left transition backdrop-blur-[1px] lg:absolute lg:left-[var(--sx)] lg:top-[var(--sy)] lg:h-[var(--sh)] lg:w-[var(--sw)] ${surfaceClass} ${borderClass} ${
        isFlashing ? 'slot-attach-flash' : ''
      } ${isNext && !isSelected ? 'slot-empty-pulse slot-hint-shimmer' : ''} ${
        filled && !isSelected ? statusPulseClass(slotStatus) : ''
      } ${isHovered ? 'slot-card-hovered' : ''} ${cardsVisible ? '' : 'lg:hidden'}`}
    >
      <button
        type="button"
        aria-label={`${slot.label} 슬롯 열기`}
        aria-pressed={isSelected}
        onClick={onSelect}
        onFocus={() => onHoverChange(slot.category)}
        onBlur={() => onHoverChange(null)}
        className="absolute inset-0 z-0 h-full w-full rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue"
      />
      {filled ? (
        <img
          data-testid="slot-part-image"
          src={slot.glyph}
          alt=""
          aria-hidden="true"
          className="pointer-events-none absolute inset-0 z-[5] h-full w-full object-contain p-1"
        />
      ) : null}
      <div className="pointer-events-none relative z-10 flex h-full flex-col gap-1 overflow-hidden">
        <div className="flex items-start justify-between gap-1">
          <span className={`flex items-center gap-1 text-[10px] font-black text-slate-600 ${filled ? 'rounded bg-white/85 px-1 py-0.5' : ''}`}>
            <span
              data-testid={`slot-order-${slot.category}`}
              aria-hidden="true"
              className="inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full bg-brand-blue text-[9px] font-black leading-none text-white"
            >
              {orderNumber}
            </span>
            {!filled ? <img src={slot.glyph} alt="" aria-hidden="true" className="h-4 w-auto max-w-12 shrink-0 opacity-35" /> : null}
            {slot.label}
          </span>
          {slotStatus === 'FAIL' ? (
            <ProblemStatusBadge slot={slot} detail={problemDetail} tone="FAIL" onProblemOpen={onProblemOpen} />
          ) : slotStatus === 'WARN' ? (
            <ProblemStatusBadge slot={slot} detail={problemDetail} tone="WARN" onProblemOpen={onProblemOpen} />
          ) : filled ? (
            <span className="rounded border border-emerald-200 bg-emerald-50 px-1 py-0.5 text-[9px] font-black text-emerald-700">호환 가능</span>
          ) : isNext ? (
            <span className="rounded border border-blue-200 bg-blue-50 px-1 py-0.5 text-[9px] font-black text-brand-blue">다음 선택</span>
          ) : null}
        </div>
        {filled ? (
          <div className="mt-auto flex items-end justify-between gap-1">
            {visibleSpec ? (
              <span className="min-w-0 truncate rounded bg-white/85 px-1 py-0.5 text-[9px] font-bold text-slate-500">
                {visibleSpec}
              </span>
            ) : (
              <span />
            )}
            {slot.miniSlots ? <MiniSlotRow slot={slot} items={items} /> : null}
          </div>
        ) : (
          <div className="flex flex-1 items-center justify-start gap-1">
            <span className="text-[11px] font-black text-brand-blue">+ 부품 선택</span>
            {slot.miniSlots ? <MiniSlotRow slot={slot} items={items} /> : null}
          </div>
        )}
      </div>
      {filled && !isMultiItemCategory(slot.category) ? (
        <button
          type="button"
          aria-label={`${primaryItem.name} 견적에서 제거`}
          disabled={isRemovePending}
          onClick={(event) => {
            event.stopPropagation();
            onRemoveItem(primaryItem.partId);
          }}
          className="absolute right-1 top-6 z-20 rounded border border-commerce-line bg-white px-1.5 py-0.5 text-[9px] font-black text-slate-400 opacity-0 transition group-hover:opacity-100 focus-visible:opacity-100 hover:border-commerce-sale hover:text-commerce-sale disabled:cursor-wait"
        >
          빼기
        </button>
      ) : null}
    </div>
  );
}

function ProblemStatusBadge({
  slot,
  detail,
  tone,
  onProblemOpen
}: {
  slot: SlotConfig;
  detail?: SlotProblemDetail;
  tone: 'WARN' | 'FAIL';
  onProblemOpen: (category: PartCategory) => void;
}) {
  const label = tone === 'FAIL' ? '장착 불가' : '간섭 주의';
  const toneClass = tone === 'FAIL'
    ? 'border-red-200 bg-red-50 text-red-700 hover:border-red-300 hover:bg-red-100 focus-visible:ring-red-200'
    : 'border-amber-200 bg-amber-50 text-amber-700 hover:border-amber-300 hover:bg-amber-100 focus-visible:ring-amber-200';
  return (
    <button
      type="button"
      aria-label={`${slot.label} 문제 사유 보기`}
      data-testid={`slot-problem-badge-${slot.category}`}
      disabled={!detail}
      onClick={(event) => {
        event.stopPropagation();
        onProblemOpen(slot.category);
      }}
      className={`pointer-events-auto rounded border px-1 py-0.5 text-[9px] font-black transition focus:outline-none focus-visible:ring-2 disabled:cursor-default ${toneClass}`}
    >
      {label}
    </button>
  );
}

function IsoPartLayer({
  items,
  flashingCategories,
  statusByCategory,
  problemDetailsByCategory,
  hoveredCategory,
  selectedCategory,
  focusCategory,
  onHoverChange,
  onSlotSelect,
  onProblemOpen
}: {
  items: QuoteDraftItem[];
  flashingCategories: Set<PartCategory>;
  statusByCategory: Map<string, 'PASS' | 'WARN' | 'FAIL'>;
  problemDetailsByCategory: Map<PartCategory, SlotProblemDetail>;
  hoveredCategory: PartCategory | null;
  selectedCategory: PartCategory | null;
  focusCategory: PartCategory | null;
  onHoverChange: (category: PartCategory | null) => void;
  onSlotSelect: (category: PartCategory) => void;
  onProblemOpen: (category: PartCategory) => void;
}) {
  return (
    <div className="pointer-events-none absolute inset-2 z-[5] hidden lg:block">
      {SLOT_CONFIGS.map((slot) => {
        const isMotherboardFocused = focusCategory === 'MOTHERBOARD';
        const partFocusCategory = isMotherboardFocused ? null : focusCategory;
        const isSpotlighted = partFocusCategory ? slot.category === partFocusCategory : false;
        const isSelected = selectedCategory === slot.category && slot.category !== 'MOTHERBOARD';
        return (
          <IsoPart
            key={slot.category}
            slot={slot}
            items={items.filter((item) => item.category === slot.category)}
            isMounting={flashingCategories.has(slot.category)}
            status={statusByCategory.get(slot.category)}
            isHovered={hoveredCategory === slot.category && slot.category !== 'MOTHERBOARD'}
            isDimmed={isMotherboardFocused || (partFocusCategory ? !isSpotlighted : false)}
            isSpotlighted={isSpotlighted}
            isSelected={isSelected}
            problemDetail={problemDetailsByCategory.get(slot.category)}
            onHoverChange={onHoverChange}
            onSelect={() => onSlotSelect(slot.category)}
            onProblemOpen={onProblemOpen}
          />
        );
      })}
    </div>
  );
}

function IsoPart({
  slot,
  items,
  isMounting,
  status,
  isHovered,
  isDimmed,
  isSpotlighted,
  isSelected,
  problemDetail,
  onHoverChange,
  onSelect,
  onProblemOpen
}: {
  slot: SlotConfig;
  items: QuoteDraftItem[];
  isMounting: boolean;
  status?: 'PASS' | 'WARN' | 'FAIL';
  isHovered: boolean;
  isDimmed: boolean;
  isSpotlighted: boolean;
  isSelected: boolean;
  problemDetail?: SlotProblemDetail;
  onHoverChange: (category: PartCategory | null) => void;
  onSelect: () => void;
  onProblemOpen: (category: PartCategory) => void;
}) {
  const iso = SLOT_ISO_ART[slot.category];
  if (!iso || items.length === 0) {
    return null;
  }
  const slotStatus = status ?? 'PASS';
  const markerPlacement = isoProblemMarkerPlacement(slot.category);
  return (
    <div
      data-testid={`iso-part-${slot.category}`}
      data-mounting={isMounting ? 'true' : 'false'}
      data-status={slotStatus}
      data-hovered={isHovered ? 'true' : 'false'}
      data-dimmed={isDimmed ? 'true' : 'false'}
      data-spotlight={isSpotlighted ? 'true' : 'false'}
      data-selected={isSelected ? 'true' : 'false'}
      className="iso-part pointer-events-auto absolute cursor-pointer"
      style={{ left: `${iso.x}%`, top: `${iso.y}%`, width: `${iso.w}%`, zIndex: iso.z, ['--iso-z' as string]: iso.z }}
      onMouseEnter={() => onHoverChange(slot.category)}
      onMouseLeave={() => onHoverChange(null)}
      onClick={(event) => {
        event.stopPropagation();
        onSelect();
      }}
    >
      <div className="iso-part-shadow absolute inset-x-[14%] bottom-[4%] h-[14%] rounded-[50%] bg-slate-900/20 blur-[5px]" />
      <img src={iso.src} alt="" className={`iso-part-img relative w-full iso-part-img--${iso.mount}`} />
      <span aria-hidden="true" className={`iso-part-impact iso-part-impact--${iso.mount}`} />
      {problemDetail ? (
        <button
          type="button"
          aria-label={`${slot.label} 문제 사유 보기`}
          data-testid={`iso-part-marker-${slot.category}`}
          data-placement={markerPlacement.name}
          onClick={(event) => {
            event.stopPropagation();
            onProblemOpen(slot.category);
          }}
          className={`slot-problem-marker ${markerPlacement.className} z-10 flex h-4 w-4 items-center justify-center rounded-full border-2 border-white text-[10px] font-black leading-none text-white shadow-md transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue focus-visible:ring-offset-2 ${
            slotStatus === 'FAIL' ? 'bg-red-500' : 'bg-amber-400'
          }`}
        >
          !
          <span className="slot-problem-marker-hint pointer-events-none absolute left-1/2 top-full mt-1 -translate-x-1/2 whitespace-nowrap rounded border border-slate-200 bg-white px-1.5 py-0.5 text-[9px] font-black text-slate-700 shadow-sm">
            사유 보기
          </span>
        </button>
      ) : null}
    </div>
  );
}

type SlotProblemStatus = 'WARN' | 'FAIL';

type SlotProblemDetail = {
  category: PartCategory;
  categoryLabel: string;
  status: SlotProblemStatus;
  title: string;
  reasons: string[];
};

type SlotProblemReason = {
  status: SlotProblemStatus;
  text: string;
};

function isoProblemMarkerPlacement(category: PartCategory) {
  if (category === 'CPU') {
    return { name: 'cpu-left', className: 'absolute left-[-2rem] top-[8%]' };
  }
  return { name: 'default', className: 'absolute -top-1 right-[6%]' };
}

function SlotProblemPopover({
  detail,
  onClose,
  onShowCandidates
}: {
  detail: SlotProblemDetail;
  onClose: () => void;
  onShowCandidates: () => void;
}) {
  const placement = slotProblemPopoverPlacement(detail.category);
  const vars: CSSProperties = {
    ['--problem-x' as string]: `${placement.left}%`,
    ['--problem-y' as string]: `${placement.top}%`,
    ['--problem-width' as string]: placement.width
  };
  const toneClass = detail.status === 'FAIL'
    ? 'border-red-200 bg-red-50 text-red-700'
    : 'border-amber-200 bg-amber-50 text-amber-700';
  return (
    <section
      data-testid="slot-problem-popover"
      data-placement={placement.name}
      role="dialog"
      aria-label={`${detail.categoryLabel} 문제 사유`}
      style={vars}
      className="relative z-40 mt-2 w-full rounded-lg border border-slate-200 bg-white p-3 text-left shadow-lg lg:absolute lg:left-[var(--problem-x)] lg:top-[var(--problem-y)] lg:mt-0 lg:w-[var(--problem-width)]"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className={`rounded border px-2 py-0.5 text-[10px] font-black ${toneClass}`}>{detail.title}</span>
            <span className="text-[11px] font-black text-slate-500">{detail.categoryLabel}</span>
          </div>
          <h3 className="mt-1 text-sm font-black text-commerce-ink">왜 안 맞는지 확인해 보세요</h3>
        </div>
        <button
          type="button"
          aria-label="문제 사유 닫기"
          onClick={onClose}
          className="shrink-0 rounded border border-slate-200 bg-white px-2 py-1 text-[11px] font-black text-slate-500 transition hover:border-slate-300 hover:text-slate-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue"
        >
          닫기
        </button>
      </div>
      <ul className="mt-2 space-y-1.5">
        {detail.reasons.map((reason) => (
          <li key={reason} className="break-keep rounded bg-slate-50 px-2 py-1.5 text-[11px] font-bold leading-5 text-slate-700">
            {reason}
          </li>
        ))}
      </ul>
      <div className="mt-3 flex justify-end">
        <button
          type="button"
          onClick={onShowCandidates}
          className="rounded bg-brand-blue px-3 py-2 text-[11px] font-black text-white transition hover:bg-blue-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-200"
        >
          교체 후보 보기
        </button>
      </div>
    </section>
  );
}

function slotProblemPopoverPlacement(category: PartCategory) {
  const iso = SLOT_ISO_ART[category];
  if (!iso) {
    return { name: 'default', left: 4, top: 4, width: '300px' };
  }
  if (category === 'CPU') {
    return {
      name: 'cpu-left',
      left: clampNumber(iso.x - 32, 0, 74),
      top: clampNumber(iso.y + 1, 3, 70),
      width: '200px'
    };
  }
  return {
    name: 'default',
    left: clampNumber(iso.x > 62 ? iso.x - 23 : iso.x + iso.w + 1, 2, 74),
    top: clampNumber(iso.y + 1, 3, 70),
    width: '300px'
  };
}

// 추상 메인보드 평면도 — 그래파이트 트레이 위의 실제 PCB처럼 어두운 기판 + 밝은 트레이스/소켓.
// 팔레트: 기판 #232c3a / 오목부 #1c2431 / 트레이스·테두리 #47566b / 하이라이트 #5b6b83.
// 좌표는 SLOT_CONFIGS의 실장 지점과 같은 상수 계보(viewBox 160×100, % = x/1.6)다 —
// 실장 지점을 옮기면 이 아트의 소켓/슬롯도 함께 옮겨야 한다.
function BoardPlanArt() {
  return (
    <svg
      viewBox={SLOT_BOARD_ART_VIEWBOX}
      preserveAspectRatio="none"
      aria-hidden="true"
      className="pointer-events-none absolute inset-0 z-0 hidden h-full w-full lg:block"
    >
      {/* 기판 + 나사홀 — 상단(쿨러)·좌측(케이스/파워) 여백 회랑을 남기고 우측에 배치 */}
      <rect x="56" y="20" width="98" height="77" rx="3" fill="#232c3a" stroke="#3f4c60" strokeWidth="0.8" />
      {[[60, 24], [105, 24], [150, 24], [60, 93], [105, 93], [150, 93]].map(([x, y], index) => (
        <circle key={index} cx={x} cy={y} r="1" fill="#141a24" stroke="#54637a" strokeWidth="0.4" />
      ))}
      {/* 우측 IO 포트 블록 */}
      {[28, 37, 46].map((y) => (
        <rect key={y} x="146.5" y={y} width="6.5" height="7" rx="1" fill="#2c3646" stroke="#4b5a70" strokeWidth="0.5" />
      ))}
      {/* CPU 소켓 (핫스팟: 78..104 × 30..56) */}
      <rect x="78" y="30" width="26" height="26" rx="1.5" fill="#2b3545" stroke="#54637a" strokeWidth="0.9" />
      <rect x="82.5" y="34.5" width="17" height="17" rx="1" fill="#1c2431" stroke="#47566b" strokeWidth="0.6" />
      {[[79.5, 31.5], [102.5, 31.5], [79.5, 54.5], [102.5, 54.5]].map(([x, y], index) => (
        <circle key={index} cx={x} cy={y} r="0.9" fill="#5b6b83" />
      ))}
      {/* DIMM 4슬롯 (핫스팟: 110..136 × 24..60) */}
      {[111.5, 118, 124.5, 131].map((x) => (
        <g key={x}>
          <rect x={x} y="26" width="4.4" height="32" rx="0.8" fill="#1c2431" stroke="#4b5a70" strokeWidth="0.7" />
          <line x1={x + 2.2} y1="28.5" x2={x + 2.2} y2="55.5" stroke="#3a4557" strokeWidth="1.4" />
          <rect x={x + 0.6} y="24.6" width="3.2" height="1.6" rx="0.4" fill="#3a4557" />
          <rect x={x + 0.6} y="57.8" width="3.2" height="1.6" rx="0.4" fill="#3a4557" />
        </g>
      ))}
      {/* PCIe x16 (핫스팟: 60..124 × 62..78) + 보조 x4 */}
      <rect x="61" y="70" width="61" height="4.5" rx="0.8" fill="#1c2431" stroke="#4b5a70" strokeWidth="0.7" />
      <line x1="74" y1="70.6" x2="74" y2="73.9" stroke="#5b6b83" strokeWidth="0.6" />
      <rect x="61" y="78.5" width="30" height="3.2" rx="0.8" fill="#1c2431" stroke="#47566b" strokeWidth="0.6" />
      {/* M.2 (핫스팟: 128..152 × 80..93) */}
      <rect x="129" y="84" width="20" height="5" rx="0.8" fill="#1c2431" stroke="#4b5a70" strokeWidth="0.7" />
      <circle cx="147.5" cy="86.5" r="1" fill="#141a24" stroke="#54637a" strokeWidth="0.5" />
      {/* 칩셋 방열판 */}
      <rect x="130" y="66" width="13" height="11" rx="1.2" fill="#2c3646" stroke="#4b5a70" strokeWidth="0.7" />
      <rect x="132.5" y="68.5" width="8" height="6" rx="0.8" fill="none" stroke="#54637a" strokeWidth="0.5" />
      {/* 메인보드 명판 (핫스팟: 58..92 × 84..95) */}
      <rect x="60" y="85" width="32" height="9.5" rx="1.2" fill="#1c2431" stroke="#54637a" strokeWidth="0.7" strokeDasharray="1.6 1.2" />
    </svg>
  );
}

function MotherboardSlot({
  slot,
  layout,
  items,
  problemStatus,
  isSelected,
  isNext,
  isFlashing,
  onSelect,
  onRemoveItem,
  isRemovePending
}: {
  slot: SlotConfig;
  layout: SlotConfig['layout'];
  items: QuoteDraftItem[];
  problemStatus?: 'PASS' | 'WARN' | 'FAIL';
  isSelected: boolean;
  isNext: boolean;
  isFlashing: boolean;
  onSelect: () => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
}) {
  const filled = items.length > 0;
  const primaryItem = items[0];
  // 문제 상태는 장착된 슬롯에만 표시한다. 숨기지 않고 강조한다.
  const slotStatus = filled ? problemStatus ?? 'PASS' : 'NONE';
  // 보드 위 실장 지점은 비어 있을 때 카드 대신 평면도의 소켓/슬롯 그림이 빈 상태를 말한다(데스크톱).
  const isBoardMount = slot.mount === 'board';
  // "메인보드에 꽂힌다"는 느낌: 장착 순간 카드가 보드 바깥에서 기판 중심 방향으로 밀려 들어온다.
  const outwardX = layout.x + layout.w / 2 - MOTHERBOARD_BOARD_CENTER.x;
  const outwardY = layout.y + layout.h / 2 - MOTHERBOARD_BOARD_CENTER.y;
  const outwardLength = Math.hypot(outwardX, outwardY) || 1;
  const layoutVars: CSSProperties = {
    ['--sx' as string]: `${layout.x}%`,
    ['--sy' as string]: `${layout.y}%`,
    ['--sw' as string]: `${layout.w}%`,
    ['--sh' as string]: `${layout.h}%`,
    ['--plug-dx' as string]: `${(outwardX / outwardLength) * 26}px`,
    ['--plug-dy' as string]: `${(outwardY / outwardLength) * 26}px`
  };
  const borderClass = isSelected
    ? 'border-2 border-brand-blue ring-2 ring-blue-100 shadow-lg'
    : slotStatus === 'FAIL'
      ? 'border-2 border-red-500 ring-2 ring-red-300'
      : slotStatus === 'WARN'
        ? 'border-2 border-amber-400 ring-2 ring-amber-300'
        : filled
          ? 'border-2 border-emerald-400 ring-2 ring-emerald-300 hover:border-emerald-500'
          : isNext
            ? `border-2 border-brand-blue bg-blue-50/40 hover:border-blue-600${isBoardMount ? ' lg:bg-blue-50/25' : ''}`
            : isBoardMount
              // 데스크톱 실장 지점: 아트의 빈 소켓/슬롯이 보이도록 투명 — hover에만 윤곽을 드러낸다.
              ? 'border border-dashed border-slate-300 bg-white/75 hover:border-brand-blue lg:border-transparent lg:bg-transparent lg:hover:border-brand-blue/60 lg:hover:bg-blue-50/20'
              : 'border border-dashed border-slate-300 bg-white/75 hover:border-brand-blue';
  // 장착된 박스는 상태색으로 칠한다 — 정상=초록, 간섭 주의=주황, 장착 불가=빨강.
  // 어두운 트레이 위에서도 색이 확실히 읽히도록 틴트를 진하게(-100) 준다. 상품 이미지는 중앙을 덮어 그대로 보인다.
  const surfaceClass = filled
    ? slotStatus === 'FAIL'
      ? 'bg-red-100'
      : slotStatus === 'WARN'
        ? 'bg-amber-100'
        : 'bg-emerald-100'
    : isBoardMount
      ? 'bg-white/95 lg:bg-transparent lg:backdrop-blur-0'
      : 'bg-white/95';
  const visibleName = filled
    ? items.length > 1 ? `${primaryItem.name} 외 ${items.length - 1}개` : primaryItem.name
    : '';
  const visibleSpec = filled ? partShortSpec(primaryItem) : '';

  return (
    <div
      data-testid={`slot-${slot.category}`}
      data-selected={isSelected ? 'true' : 'false'}
      data-status={slotStatus}
      data-flash={isFlashing ? 'true' : 'false'}
      style={layoutVars}
      data-next={isNext ? 'true' : 'false'}
      title={filled ? visibleName : undefined}
      className={`group relative z-20 rounded-lg p-2 text-left transition backdrop-blur-[1px] lg:absolute lg:left-[var(--sx)] lg:top-[var(--sy)] lg:h-[var(--sh)] lg:w-[var(--sw)] ${surfaceClass} ${borderClass} ${
        isFlashing ? 'slot-attach-flash slot-plug-in' : ''
      } ${isNext && !isSelected ? 'slot-empty-pulse slot-hint-shimmer' : ''} ${
        filled && !isSelected ? statusPulseClass(slotStatus) : ''
      }`}
    >
      <button
        type="button"
        aria-label={`${slot.label} 슬롯 열기`}
        aria-pressed={isSelected}
        onClick={onSelect}
        className="absolute inset-0 z-0 h-full w-full rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue"
      />
      {/* 장착 시: 에셋이 박스를 가득 채우고 크롬(카테고리·뱃지·요약)은 위에 얇게 얹는다. */}
      {filled ? (
        <img
          data-testid="slot-part-image"
          src={slot.glyph}
          alt=""
          aria-hidden="true"
          className="pointer-events-none absolute inset-0 z-[5] h-full w-full object-contain p-1"
        />
      ) : null}
      <div className="pointer-events-none relative z-10 flex h-full flex-col gap-1 overflow-hidden">
        {/* 카드 헤더: 카테고리명 + 상태 배지 — 장착 시 에셋 위에 뜨는 칩 형태 */}
        <div className="flex items-start justify-between gap-1">
          {/* 빈 보드 실장 슬롯은 데스크톱에서 어두운 트레이 위에 놓이므로 라벨을 밝게 뒤집는다. */}
          <span className={`flex items-center gap-1 text-[10px] font-black text-slate-600 ${filled ? 'rounded bg-white/85 px-1 py-0.5' : isBoardMount ? 'lg:text-slate-100' : ''}`}>
            {!filled ? <img src={slot.glyph} alt="" aria-hidden="true" className={`h-4 w-auto max-w-12 shrink-0 opacity-35 ${isBoardMount ? 'lg:opacity-60' : ''}`} /> : null}
            {slot.label}
          </span>
          {slotStatus === 'FAIL' ? (
            <span className="rounded border border-red-200 bg-red-50 px-1 py-0.5 text-[9px] font-black text-red-700">장착 불가</span>
          ) : slotStatus === 'WARN' ? (
            <span className="rounded border border-amber-200 bg-amber-50 px-1 py-0.5 text-[9px] font-black text-amber-700">간섭 주의</span>
          ) : filled ? (
            <span className="rounded border border-emerald-200 bg-emerald-50 px-1 py-0.5 text-[9px] font-black text-emerald-700">호환 가능</span>
          ) : isNext ? (
            <span className="rounded border border-blue-200 bg-blue-50 px-1 py-0.5 text-[9px] font-black text-brand-blue">다음 선택</span>
          ) : null}
        </div>
        {filled ? (
          <div className="mt-auto flex items-end justify-between gap-1">
            {visibleSpec ? (
              <span className="min-w-0 truncate rounded bg-white/85 px-1 py-0.5 text-[9px] font-bold text-slate-500">
                {visibleSpec}
              </span>
            ) : (
              <span />
            )}
            {slot.miniSlots ? <MiniSlotRow slot={slot} items={items} /> : null}
          </div>
        ) : (
          <div className="flex flex-1 items-center justify-start gap-1">
            <span className={`text-[11px] font-black text-brand-blue ${isBoardMount ? 'lg:text-sky-300' : ''}`}>+ 부품 선택</span>
            {slot.miniSlots ? <MiniSlotRow slot={slot} items={items} /> : null}
          </div>
        )}
      </div>
      {filled && !isMultiItemCategory(slot.category) ? (
        <button
          type="button"
          aria-label={`${primaryItem.name} 견적에서 제거`}
          disabled={isRemovePending}
          onClick={() => onRemoveItem(primaryItem.partId)}
          className="absolute right-1 top-6 z-20 rounded border border-commerce-line bg-white px-1.5 py-0.5 text-[9px] font-black text-slate-400 opacity-0 transition group-hover:opacity-100 focus-visible:opacity-100 hover:border-commerce-sale hover:text-commerce-sale disabled:cursor-wait"
        >
          빼기
        </button>
      ) : null}
    </div>
  );
}

// 장착/교체로 어떤 카테고리의 구성이 바뀌면 그 카테고리를 잠깐 flash 상태로 켠다.
// 카드 꽂힘 모션과 관계선 draw-in·포트 점등이 같은 신호를 공유한다.
// 애니메이션 자체는 CSS가 담당하고 prefers-reduced-motion에서는 CSS에서 꺼진다.
function useAttachFlashByCategory(items: QuoteDraftItem[]) {
  const signatures = new Map<string, string>();
  for (const item of items) {
    signatures.set(item.category, `${signatures.get(item.category) ?? ''}${item.partId}:${item.quantity}|`);
  }
  const signatureText = [...signatures.entries()].map(([category, value]) => `${category}=${value}`).sort().join(';');
  const previousRef = useRef<Map<string, string> | null>(null);
  const [flashing, setFlashing] = useState<Set<PartCategory>>(new Set());

  useEffect(() => {
    const previous = previousRef.current;
    previousRef.current = signatures;
    if (previous === null) {
      return;
    }
    const changed = new Set<PartCategory>();
    for (const [category, signature] of signatures) {
      if (previous.get(category) !== signature) {
        changed.add(category as PartCategory);
      }
    }
    if (changed.size === 0) {
      return;
    }
    setFlashing(changed);
    const timer = window.setTimeout(() => setFlashing(new Set()), 900);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signatureText]);

  return flashing;
}

type SlotEdgeStatus = 'PASS' | 'WARN' | 'FAIL' | 'PENDING' | 'BASE';

type ResolvedSlotEdge = {
  config: SlotEdgeConfig;
  status: SlotEdgeStatus;
  label: string;
  summary?: string;
};

// 호환 상태 색 체계: 정상 = 초록, 주의 = 노랑, 불가 = 빨강, 미장착 = 회색 (전 화면 공통 규칙)
const EDGE_STROKES: Record<SlotEdgeStatus, { stroke: string; dash?: string }> = {
  PASS: { stroke: '#16a34a' },
  WARN: { stroke: '#d97706' },
  FAIL: { stroke: '#ef4444', dash: '6 4' },
  PENDING: { stroke: '#94a3b8', dash: '4 4' },
  BASE: { stroke: '#16a34a' }
};

const EDGE_LABEL_CLASSES: Record<SlotEdgeStatus, string> = {
  PASS: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  WARN: 'border-amber-200 bg-amber-50 text-amber-700',
  FAIL: 'border-red-200 bg-red-50 text-red-700',
  PENDING: 'border-slate-200 bg-white text-slate-400',
  BASE: 'border-slate-200 bg-white text-slate-600'
};

// 기본 topology 관계선은 graph API 없이 항상 렌더링되고,
// graph 응답이 있으면 카테고리 쌍이 일치하는 edge의 라벨/상태만 덧입힌다.
function SlotBoardEdges({
  items,
  graph,
  selectedCategory,
  hoveredCategory,
  visualMode,
  flashingCategories
}: {
  items: QuoteDraftItem[];
  graph?: BuildGraphResolveResponse;
  selectedCategory: PartCategory | null;
  hoveredCategory: PartCategory | null;
  visualMode: SlotBoardVisualMode;
  flashingCategories: Set<PartCategory>;
}) {
  const filledCategories = new Set(items.map((item) => item.category));
  const edgeConfigs = visualMode === 'isometric' ? SLOT_BOARD_ISO_EDGES : FALLBACK_EDGES;
  const edges: ResolvedSlotEdge[] = edgeConfigs.map((config) => {
    if (!filledCategories.has(config.from) || !filledCategories.has(config.to)) {
      return { config, status: 'PENDING', label: config.label, summary: '부품 선택 후 계산됩니다.' };
    }
    const graphEdge = findGraphEdge(graph, config.from, config.to);
    if (graphEdge) {
      return {
        config,
        status: graphEdge.status,
        label: graphEdge.label || config.label,
        summary: graphEdge.summary
      };
    }
    return { config, status: 'BASE', label: config.label };
  });

  return (
    <div data-testid="slot-board-edges" aria-hidden="true" className="pointer-events-none absolute inset-0 z-10 hidden lg:block">
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="h-full w-full">
        {edges.map((edge) => {
          const { path, start, end } = edgeGeometry(edge.config, visualMode);
          if (!path) {
            // 실장 관계(implied)는 꽂혀 있는 그림이 관계 자체다 — 상태 표시는 라벨 레이어가 담당한다.
            return null;
          }
          const style = EDGE_STROKES[edge.status];
          const isHighlighted =
            selectedCategory === edge.config.from ||
            selectedCategory === edge.config.to ||
            hoveredCategory === edge.config.from ||
            hoveredCategory === edge.config.to;
          const isSpoke = edge.config.from === 'MOTHERBOARD' || edge.config.to === 'MOTHERBOARD';
          // 장착 순간 관련 관계선이 부품→포트 방향으로 그려지고(draw-in) 포트가 점등된다.
          const isDrawing = flashingCategories.has(edge.config.from) || flashingCategories.has(edge.config.to);
          // 24핀처럼 보드에 꽂히는 연결선의 보드 쪽 끝은 포트 패드로 그린다.
          const hubAnchor = isSpoke ? (edge.config.to === 'MOTHERBOARD' ? end : start) : null;
          const partAnchor = isSpoke ? (edge.config.to === 'MOTHERBOARD' ? start : end) : null;
          return (
            <g key={`${edge.config.from}-${edge.config.to}`}>
              <path
                d={path}
                fill="none"
                stroke={style.stroke}
                strokeWidth={isHighlighted ? 4.5 : 3}
                pathLength={isDrawing ? 1 : undefined}
                strokeDasharray={isDrawing ? '1' : style.dash}
                strokeOpacity={isHighlighted ? 1 : 0.6}
                strokeLinecap="round"
                strokeLinejoin="round"
                vectorEffect="non-scaling-stroke"
                className={isDrawing ? 'slot-edge-draw' : undefined}
              />
              {hubAnchor && partAnchor ? (
                <>
                  <rect
                    x={hubAnchor.x - 1.1}
                    y={hubAnchor.y - 0.9}
                    width={2.2}
                    height={1.8}
                    rx={0.4}
                    fill="#ffffff"
                    stroke={style.stroke}
                    strokeOpacity={isHighlighted ? 1 : 0.7}
                    vectorEffect="non-scaling-stroke"
                    className={isDrawing ? 'slot-port-pulse' : undefined}
                  />
                  <circle cx={partAnchor.x} cy={partAnchor.y} r={0.7} fill={style.stroke} fillOpacity={isHighlighted ? 1 : 0.6} />
                </>
              ) : (
                <>
                  <circle cx={start.x} cy={start.y} r={0.7} fill={style.stroke} fillOpacity={isHighlighted ? 1 : 0.6} />
                  <circle cx={end.x} cy={end.y} r={0.7} fill={style.stroke} fillOpacity={isHighlighted ? 1 : 0.6} />
                </>
              )}
            </g>
          );
        })}
      </svg>
      {edges.map((edge) => {
        const { label } = edgeGeometry(edge.config, visualMode);
        const isHighlighted =
          selectedCategory === edge.config.from ||
          selectedCategory === edge.config.to ||
          hoveredCategory === edge.config.from ||
          hoveredCategory === edge.config.to;
        // 정상/미장착 선은 라벨 대신 상태 점만 — "PCIe x16" 같은 전문용어를 평상시에 노출하지 않는다.
        // 문제가 있을 때만(WARN/FAIL) 서버가 내려준 사용자 언어 사유를 그대로 보여준다.
        const hasProblem = edge.status === 'WARN' || edge.status === 'FAIL';
        return (
          <span
            key={`label-${edge.config.from}-${edge.config.to}`}
            data-testid={`slot-edge-${edge.config.from}-${edge.config.to}`}
            data-status={edge.status}
            title={edge.summary ?? edge.label}
            style={{ left: `${label.x}%`, top: `${label.y}%`, opacity: isHighlighted ? 1 : 0.85 }}
            className={
              hasProblem
                ? `absolute -translate-x-1/2 -translate-y-1/2 whitespace-nowrap rounded-full border px-1.5 py-0.5 text-[9px] font-black shadow-sm ${EDGE_LABEL_CLASSES[edge.status]}`
                : `absolute h-2 w-2 -translate-x-1/2 -translate-y-1/2 rounded-full border shadow-sm ${EDGE_LABEL_CLASSES[edge.status]}`
            }
          >
            {hasProblem ? edge.label : null}
          </span>
        );
      })}
    </div>
  );
}

function isFiniteConnectorPoint(point: ConnectorAnchorPoint | undefined): point is ConnectorAnchorPoint {
  return Boolean(point) && Number.isFinite(point?.x) && Number.isFinite(point?.y);
}

// 선택된 카드에서 대응하는 3D 부품 글리프까지 잇는 tether 한 줄. 부품↔부품 관계선이 아니라
// "이 카드가 이 부품"임을 눈으로 잇는 선이라, 선택된 슬롯 하나만 그린다(3D 전용).
function IsoCardConnector({
  selectedCategory,
  status,
  anchors
}: {
  selectedCategory: PartCategory | null;
  status: SlotEdgeStatus;
  anchors?: ConnectorAnchors;
}) {
  if (!selectedCategory) {
    return null;
  }
  const iso = SLOT_ISO_ART[selectedCategory];
  const cardLayout = SLOT_BOARD_ISO_CALLOUT_LAYOUTS[selectedCategory];
  if (!iso || !cardLayout) {
    return null;
  }
  // 관리자가 /admin/build-graph-layouts에서 배치한 앵커가 있으면 그걸 쓰고,
  // 없거나 값이 이상하면(fetch 실패 포함) 기존 자동 계산으로 폴백한다.
  const adminAnchor = anchors?.[selectedCategory];
  const adminAnchorValid = Boolean(adminAnchor) && isFiniteConnectorPoint(adminAnchor?.card) && isFiniteConnectorPoint(adminAnchor?.part);
  // 부품 앵커: 글리프 높이는 이미지 비율이라 알 수 없어, 가로 중심 + 폭의 0.35만큼 아래로 잡아 글리프 안쪽에 안착시킨다.
  const partPoint: Point = adminAnchorValid
    ? { x: adminAnchor!.part.x, y: adminAnchor!.part.y }
    : { x: iso.x + iso.w / 2, y: iso.y + iso.w * 0.35 };
  const cardBox: Box = { x: cardLayout.x, y: cardLayout.y, w: cardLayout.w, h: cardLayout.h };
  const cardCenter = boxCenter(cardBox);
  const cardAnchor: Point = adminAnchorValid ? { x: adminAnchor!.card.x, y: adminAnchor!.card.y } : boxAnchorToward(cardBox, partPoint);
  // 엘보(90도 1회): 긴 축을 먼저 진행해 꺾임이 부품 쪽에 가깝게 놓이도록 한다.
  const dx = Math.abs(partPoint.x - cardCenter.x);
  const dy = Math.abs(partPoint.y - cardCenter.y);
  const elbow: Point = dx >= dy ? { x: partPoint.x, y: cardAnchor.y } : { x: cardAnchor.x, y: partPoint.y };
  const path = `M ${cardAnchor.x} ${cardAnchor.y} L ${elbow.x} ${elbow.y} L ${partPoint.x} ${partPoint.y}`;
  const stroke = EDGE_STROKES[status].stroke;
  return (
    <div
      data-testid="iso-card-connector"
      data-category={selectedCategory}
      data-anchor-source={adminAnchorValid ? 'admin' : 'auto'}
      aria-hidden="true"
      className="pointer-events-none absolute inset-2 z-[12] hidden lg:block"
    >
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="h-full w-full">
        <path
          key={selectedCategory}
          d={path}
          fill="none"
          stroke={stroke}
          strokeWidth={2.5}
          strokeDasharray="0.02 0.015"
          strokeLinecap="round"
          strokeLinejoin="round"
          pathLength={1}
          vectorEffect="non-scaling-stroke"
          className="iso-card-connector-draw"
        />
        <circle cx={partPoint.x} cy={partPoint.y} r={0.9} fill={stroke} />
        <circle cx={cardAnchor.x} cy={cardAnchor.y} r={0.9} fill={stroke} />
      </svg>
    </div>
  );
}

type Box = { x: number; y: number; w: number; h: number };
type Point = { x: number; y: number };

// 보드 기판 사각형(아트 56..154 × 20..97)의 실제 중심 — 실장 상태 점·bow 바깥 방향·꽂힘 모션의 기준.
const MOTHERBOARD_BOARD_CENTER: Point = { x: 65.6, y: 58.5 };
const ISOMETRIC_BOARD_CENTER: Point = { x: 51, y: 56.5 };

function boxCenter(box: Box): Point {
  return { x: box.x + box.w / 2, y: box.y + box.h / 2 };
}

// 카드 중심에서 target 방향으로 나가는 광선이 카드 테두리와 만나는 점 — 선이 카드 밖에서 시작하게 한다.
function boxAnchorToward(box: Box, target: Point): Point {
  const center = boxCenter(box);
  const dx = target.x - center.x;
  const dy = target.y - center.y;
  if (dx === 0 && dy === 0) {
    return center;
  }
  const scaleX = dx === 0 ? Number.POSITIVE_INFINITY : (box.w / 2) / Math.abs(dx);
  const scaleY = dy === 0 ? Number.POSITIVE_INFINITY : (box.h / 2) / Math.abs(dy);
  const scale = Math.min(scaleX, scaleY);
  return { x: center.x + dx * scale, y: center.y + dy * scale };
}

// 실장도 지오메트리: 도킹 부품의 연결선은 직선 또는 곡선(bow), 실장 관계(implied)는 선을 그리지
// 않고 실장 지점 옆의 상태 표시 좌표만 계산한다. 라벨은 실제 선 위의 t 지점에 둔다.
function edgeGeometry(config: SlotEdgeConfig, visualMode: SlotBoardVisualMode = 'motherboard') {
  const geometry = edgeGeometryOnLine(config, visualMode);
  if (!geometry.path || (!config.labelDx && !config.labelDy)) {
    return geometry;
  }
  return {
    ...geometry,
    label: { x: geometry.label.x + (config.labelDx ?? 0), y: geometry.label.y + (config.labelDy ?? 0) }
  };
}

function edgeGeometryOnLine(config: SlotEdgeConfig, visualMode: SlotBoardVisualMode) {
  const fromSlot = slotConfigFor(config.from);
  const toSlot = slotConfigFor(config.to);
  const a: Box = fromSlot ? slotLayoutForVisualMode(fromSlot, visualMode) : { x: 0, y: 0, w: 0, h: 0 };
  const b: Box = toSlot ? slotLayoutForVisualMode(toSlot, visualMode) : { x: 0, y: 0, w: 0, h: 0 };
  const ac = boxCenter(a);
  const bc = boxCenter(b);
  const boardCenter = visualMode === 'isometric' ? ISOMETRIC_BOARD_CENTER : MOTHERBOARD_BOARD_CENTER;

  if (config.implied) {
    // 실장 관계: 부품 쪽 실장 지점의 안쪽(보드 중앙 방향) 가장자리에 상태 점/문제 라벨을 붙인다.
    const partBox = config.from === 'MOTHERBOARD' ? b : a;
    const anchor = boxAnchorToward(partBox, boardCenter);
    const center = boxCenter(partBox);
    const direction = { x: anchor.x - center.x, y: anchor.y - center.y };
    const length = Math.hypot(direction.x, direction.y) || 1;
    const point = { x: anchor.x + (direction.x / length) * 1.8, y: anchor.y + (direction.y / length) * 1.8 };
    return { path: '', start: point, end: point, label: point };
  }

  const labelT = config.labelT ?? 0.5;
  if (!config.bow) {
    const start = boxAnchorToward(a, bc);
    const end = boxAnchorToward(b, ac);
    return {
      path: `M ${start.x} ${start.y} L ${end.x} ${end.y}`,
      start,
      end,
      label: { x: start.x + (end.x - start.x) * labelT, y: start.y + (end.y - start.y) * labelT }
    };
  }

  // 크로스 곡선: 두 중심의 중점을 보드 중앙 기준 바깥/안쪽으로 bow만큼 밀어 제어점을 만든다.
  const mid = { x: (ac.x + bc.x) / 2, y: (ac.y + bc.y) / 2 };
  const outward = { x: mid.x - boardCenter.x, y: mid.y - boardCenter.y };
  const outwardLength = Math.hypot(outward.x, outward.y) || 1;
  const control = {
    x: mid.x + (outward.x / outwardLength) * config.bow,
    y: mid.y + (outward.y / outwardLength) * config.bow
  };
  const start = boxAnchorToward(a, control);
  const end = boxAnchorToward(b, control);
  // 2차 베지어의 t 지점 — 라벨을 곡선 위에 정확히 얹는다.
  const inv = 1 - labelT;
  return {
    path: `M ${start.x} ${start.y} Q ${control.x} ${control.y} ${end.x} ${end.y}`,
    start,
    end,
    label: {
      x: inv * inv * start.x + 2 * inv * labelT * control.x + labelT * labelT * end.x,
      y: inv * inv * start.y + 2 * inv * labelT * control.y + labelT * labelT * end.y
    }
  };
}

function slotLayoutForVisualMode(slot: SlotConfig, visualMode: SlotBoardVisualMode) {
  return visualMode === 'isometric' ? slotIsoCalloutLayout(slot) : slot.layout;
}

// 장착된 슬롯의 상태색 히어로 펄스 — 파란 "다음 선택" 펄스처럼 상태색 링으로 숨쉬게 한다.
function statusPulseClass(status: 'PASS' | 'WARN' | 'FAIL' | 'NONE') {
  if (status === 'FAIL') return 'slot-fail-pulse';
  if (status === 'WARN') return 'slot-warn-pulse';
  if (status === 'PASS') return 'slot-pass-pulse';
  return '';
}

function partStatusByCategory(graph?: BuildGraphResolveResponse) {
  const statusMap = new Map<string, 'PASS' | 'WARN' | 'FAIL'>();
  graph?.nodes.forEach((node) => {
    const category = slotCategoryFromGraphCategory(node.category);
    if (node.type === 'PART' && category) {
      statusMap.set(category, node.status);
    }
  });
  return statusMap;
}

function slotProblemDetailsByCategory(graph?: BuildGraphResolveResponse) {
  const details = new Map<PartCategory, { status: SlotProblemStatus; reasons: SlotProblemReason[] }>();
  if (!graph) {
    return new Map<PartCategory, SlotProblemDetail>();
  }

  const categoryByNodeId = graphCategoryByNodeId(graph);
  const ensure = (category: PartCategory, status: SlotProblemStatus, promoteStatus: boolean) => {
    const current = details.get(category);
    if (!current) {
      const next = { status, reasons: [] };
      details.set(category, next);
      return next;
    }
    if (promoteStatus) {
      current.status = worstProblemStatus(current.status, status);
    }
    return current;
  };
  const addReason = (category: PartCategory, status: SlotProblemStatus, text?: string, promoteStatus = true) => {
    const trimmed = text?.trim();
    const detail = ensure(category, status, promoteStatus);
    if (trimmed) {
      detail.reasons.push({ status, text: trimmed });
    }
  };

  graph.nodes.forEach((node) => {
    const category = slotCategoryFromGraphCategory(node.category);
    if (category && isProblemStatus(node.status)) {
      addReason(category, node.status, node.detail);
    }
  });

  graph.edges.forEach((edge) => {
    if (!isProblemStatus(edge.status)) {
      return;
    }
    const sourceCategory = categoryByNodeId.get(edge.source);
    const targetCategory = categoryByNodeId.get(edge.target);
    if (sourceCategory) {
      addReason(sourceCategory, edge.status, edge.summary || edge.label, false);
    }
    if (targetCategory) {
      addReason(targetCategory, edge.status, edge.summary || edge.label, false);
    }
  });

  graph.insights.forEach((insight) => {
    if (!isProblemStatus(insight.status)) {
      return;
    }
    const status = insight.status;
    insight.relatedNodeIds.forEach((nodeId) => {
      const category = categoryByNodeId.get(nodeId);
      if (category) {
        addReason(category, status, insight.description || insight.title, false);
      }
    });
  });

  const result = new Map<PartCategory, SlotProblemDetail>();
  details.forEach((detail, category) => {
    result.set(category, {
      category,
      categoryLabel: slotConfigFor(category)?.label ?? category,
      status: detail.status,
      title: detail.status === 'FAIL' ? '장착 불가' : '간섭 주의',
      reasons: uniqueProblemReasons(detail.reasons)
    });
  });
  return result;
}

function findGraphEdge(graph: BuildGraphResolveResponse | undefined, from: PartCategory, to: PartCategory) {
  if (!graph) {
    return undefined;
  }
  const categoryByNodeId = graphCategoryByNodeId(graph);
  return graph.edges.find((edge) => {
    const sourceCategory = categoryByNodeId.get(edge.source);
    const targetCategory = categoryByNodeId.get(edge.target);
    return (sourceCategory === from && targetCategory === to) || (sourceCategory === to && targetCategory === from);
  });
}

function graphCategoryByNodeId(graph: BuildGraphResolveResponse) {
  const categoryByNodeId = new Map<string, PartCategory>();
  graph.nodes.forEach((node) => {
    const category = slotCategoryFromGraphCategory(node.category);
    if (category) {
      categoryByNodeId.set(node.id, category);
    }
  });
  return categoryByNodeId;
}

function slotCategoryFromGraphCategory(category: unknown): PartCategory | undefined {
  if (typeof category !== 'string' || !slotConfigFor(category)) {
    return undefined;
  }
  return category as PartCategory;
}

function isProblemStatus(status: string): status is SlotProblemStatus {
  return status === 'WARN' || status === 'FAIL';
}

function worstProblemStatus(left: SlotProblemStatus, right: SlotProblemStatus) {
  return left === 'FAIL' || right === 'FAIL' ? 'FAIL' : 'WARN';
}

function uniqueProblemReasons(reasons: SlotProblemReason[]) {
  const sorted = [...reasons].sort((left, right) => problemStatusRank(right.status) - problemStatusRank(left.status));
  const unique: string[] = [];
  const seen = new Set<string>();
  sorted.forEach((reason) => {
    const key = reason.text.replace(/\s+/g, ' ').trim();
    if (!key || seen.has(key)) {
      return;
    }
    seen.add(key);
    unique.push(reason.text);
  });
  return unique.length > 0 ? unique : ['현재 견적과의 호환 검증에서 문제가 감지되었습니다.'];
}

function problemStatusRank(status: SlotProblemStatus) {
  return status === 'FAIL' ? 2 : 1;
}

function readSlotBoardOverlaysVisible() {
  const storedValue = readSlotBoardBooleanPreference(SLOT_BOARD_OVERLAYS_VISIBLE_STORAGE_KEY);
  if (storedValue !== null) {
    return storedValue;
  }
  const legacyValues = SLOT_BOARD_LEGACY_DISPLAY_STORAGE_KEYS
    .map((storageKey) => readSlotBoardBooleanPreference(storageKey))
    .filter((value): value is boolean => value !== null);
  return legacyValues.includes(false) ? false : true;
}

function writeSlotBoardOverlaysVisible(visible: boolean) {
  writeSlotBoardBooleanPreference(SLOT_BOARD_OVERLAYS_VISIBLE_STORAGE_KEY, visible);
  SLOT_BOARD_LEGACY_DISPLAY_STORAGE_KEYS.forEach((storageKey) => {
    writeSlotBoardBooleanPreference(storageKey, visible);
  });
}

function readSlotBoardBooleanPreference(storageKey: string): boolean | null {
  if (typeof window === 'undefined') {
    return null;
  }
  try {
    const storedValue = window.localStorage.getItem(storageKey);
    if (storedValue === 'true') {
      return true;
    }
    if (storedValue === 'false') {
      return false;
    }
    return null;
  } catch {
    return null;
  }
}

function writeSlotBoardBooleanPreference(storageKey: string, visible: boolean) {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage.setItem(storageKey, visible ? 'true' : 'false');
  } catch {
    // localStorage가 차단된 환경에서는 표시 기본값만 유지한다.
  }
}

function clampNumber(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

// "32GB(16Gx2)" 같은 킷 상품은 스틱 2개를 차지한다. moduleCount 없는 상품은 단품(1)으로 본다.
function itemStickCount(item: QuoteDraftItem): number {
  const moduleCount = Number(item.attributes?.moduleCount);
  return item.quantity * (Number.isFinite(moduleCount) && moduleCount >= 1 ? moduleCount : 1);
}

function MiniSlotRow({ slot, items }: { slot: SlotConfig; items: QuoteDraftItem[] }) {
  const total = slot.miniSlots ?? 0;
  const fillCount = slot.miniFillBy === 'quantity'
    ? items.reduce((sum, item) => sum + itemStickCount(item), 0)
    : items.length;
  const overflow = Math.max(0, fillCount - total);

  return (
    <span className="flex items-center gap-1" aria-label={`${slot.label} 시각 슬롯 ${Math.min(fillCount, total)}/${total}`}>
      {Array.from({ length: total }).map((_, index) => (
        <span
          key={index}
          data-mini-slot-filled={index < fillCount ? 'true' : 'false'}
          className={`h-2.5 w-2.5 rounded-sm ${index < fillCount ? 'bg-brand-blue' : 'border border-dashed border-slate-300 bg-white'}`}
        />
      ))}
      {overflow > 0 ? <span className="text-[10px] font-black text-slate-500">+{overflow}</span> : null}
    </span>
  );
}
