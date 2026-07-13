import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { Sparkles, X } from 'lucide-react';
import {
  PART_CATEGORY_LABELS,
  type BuildGraphFocus,
  type BuildGraphResolveResponse,
  type PartCategory
} from '../../../quote/aiSelection';
import { openAiAssistant } from '../../../../lib/events';
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
import { FusedPlateArt } from './FusedPlateArt';
import { withObjectParticle } from './koreanParticle';

// 3뷰: 배치판(fused, 기본) / 실장도(motherboard, 구 평면도 복원) / 3D 등각(isometric).
export type SlotBoardVisualMode = 'fused' | 'motherboard' | 'isometric';

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
  aiFocusCategories?: PartCategory[];
  nextCategory?: PartCategory | null;
  visualMode?: SlotBoardVisualMode;
  onVisualModeChange?: (mode: SlotBoardVisualMode) => void;
  onClearSelection?: () => void;
  onClearAiFocus?: () => void;
  onSlotSelect: (category: PartCategory) => void;
  onRemoveItem: (partId: string) => void;
  onUpdateQuantity: (partId: string, quantity: number) => void;
  isRemovePending: boolean;
  isQuantityPending: boolean;
  graph?: BuildGraphResolveResponse;
  connectorAnchors?: ConnectorAnchors;
};

export function SlotBoard({
  items,
  selectedCategory,
  aiFocusCategories = [],
  nextCategory,
  visualMode = 'fused',
  onVisualModeChange,
  onClearSelection,
  onClearAiFocus,
  onSlotSelect,
  onRemoveItem,
  onUpdateQuantity,
  isRemovePending,
  isQuantityPending,
  graph,
  connectorAnchors
}: SlotBoardProps) {
  const statusByCategory = partStatusByCategory(graph);
  const boardProblem = slotBoardProblemBanner(graph);
  const [overlaysVisible, setOverlaysVisible] = useState(readSlotBoardOverlaysVisible);
  const [isMotherboardClosing, setIsMotherboardClosing] = useState(false);
  const [isRelationMapVisible, setIsRelationMapVisible] = useState(false);
  const motherboardCloseTimerRef = useRef<number | null>(null);
  // 카테고리별 장착 플래시를 보드 수준에서 계산해 카드(꽂힘 모션)와 관계선(draw-in·포트 점등)이 함께 반응한다.
  const flashingCategories = useAttachFlashByCategory(items);
  const isIsometric = visualMode === 'isometric';
  const isMotherboard = visualMode === 'motherboard';
  const aiFocusSet = new Set(aiFocusCategories);
  const hasAiFocus = aiFocusSet.size > 0;
  const aiFocusLabel = aiFocusCategories
    .map((category) => slotConfigFor(category)?.label ?? category)
    .join(' · ');
  const explainIssue = (category?: PartCategory, tool?: BuildGraphFocus['tool']) => {
    const categoryLabel = category ? slotConfigFor(category)?.label ?? category : '현재 견적';
    openAiAssistant({
      prefill: `${categoryLabel}에서 표시된 주의 또는 장착 문제를 종합 점수 기준으로 설명해줘`,
      autoSubmit: true,
      assessmentContext: {
        source: 'QUOTE_DRAFT_CURRENT',
        focusType: 'ISSUE',
        category,
        tool
      }
    });
  };

  useEffect(() => {
    if (isIsometric) {
      writeSlotBoardOverlaysVisible(overlaysVisible);
    }
  }, [isIsometric, overlaysVisible]);

  useEffect(() => () => {
    if (motherboardCloseTimerRef.current !== null) {
      window.clearTimeout(motherboardCloseTimerRef.current);
    }
  }, []);

  const toggleMotherboard = () => {
    if (!isMotherboard) {
      setIsRelationMapVisible(false);
      setIsMotherboardClosing(false);
      onVisualModeChange?.('motherboard');
      return;
    }
    if (isMotherboardClosing) {
      return;
    }
    setIsMotherboardClosing(true);
    motherboardCloseTimerRef.current = window.setTimeout(() => {
      onVisualModeChange?.('fused');
      setIsMotherboardClosing(false);
      motherboardCloseTimerRef.current = null;
    }, 420);
  };

  const toggleRelationMap = () => {
    if (isRelationMapVisible) {
      setIsRelationMapVisible(false);
      return;
    }
    if (isMotherboardClosing) {
      setIsMotherboardClosing(false);
    }
    onVisualModeChange?.('fused');
    setIsRelationMapVisible(true);
  };

  const handleVisualModeChange = (mode: SlotBoardVisualMode) => {
    setIsRelationMapVisible(false);
    onVisualModeChange?.(mode);
  };

  useEffect(() => {
    if (!onClearAiFocus) return undefined;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClearAiFocus();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClearAiFocus]);

  return (
    <div className="panel slot-board-panel relative flex h-full min-h-0 flex-col overflow-hidden">
      {/* 보드 헤더: 제목 + 호환 상태 범례(초록/노랑/빨강/회색) */}
      <div className="border-b border-commerce-line bg-gradient-to-b from-white to-slate-50 px-4 py-2.5">
        <div className="flex items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-2">
            <span className="shrink-0 text-xs font-black text-slate-700">구성 관계도 — 부품 간 호환 상태</span>
            {nextCategory && !hasAiFocus ? (
              <button
                type="button"
                data-testid="quote-next-guide"
                onClick={() => onSlotSelect(nextCategory)}
                className="min-w-0 truncate rounded-md border border-blue-100 bg-blue-50/70 px-2 py-1 text-[10px] font-black text-brand-blue transition hover:border-brand-blue/30 hover:bg-blue-50"
              >
                다음: {slotOrderNumber(nextCategory)}. {withObjectParticle(PART_CATEGORY_LABELS[nextCategory])} 선택해 주세요
              </button>
            ) : null}
          </div>
          {hasAiFocus ? (
            <span
              data-testid="slot-board-ai-focus-status"
              className="ml-auto inline-flex min-w-0 items-center gap-1.5 rounded-full border border-blue-200 bg-blue-50 px-2 py-1 text-[10px] font-black text-brand-blue"
            >
              <span className="truncate">{aiFocusLabel} 위치 강조 중</span>
              <button
                type="button"
                data-testid="slot-board-ai-focus-clear"
                aria-label="부품 위치 강조 해제"
                title="강조 해제"
                onClick={onClearAiFocus}
                className="grid h-4 w-4 shrink-0 place-items-center rounded-full transition hover:bg-blue-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue"
              >
                <X size={11} aria-hidden="true" />
              </button>
            </span>
          ) : null}
          <div className="ml-auto flex shrink-0 items-center gap-2">
            {isIsometric ? (
              <SlotBoardDisplaySwitch
                label="보드 정보 표시"
                checked={overlaysVisible}
                onToggle={() => setOverlaysVisible((visible) => !visible)}
                onText="정보 켜짐"
                offText="정보 꺼짐"
              />
            ) : null}
            <SlotBoardModeSegments value={visualMode} onChange={handleVisualModeChange} />
          </div>
        </div>
        <div className="mt-2 flex flex-wrap items-center justify-end gap-3 text-[10px] font-bold text-slate-500">
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
          aiFocusCategories={aiFocusCategories}
          nextCategory={nextCategory}
          onSlotSelect={onSlotSelect}
          onClearSelection={onClearSelection}
          onClearAiFocus={onClearAiFocus}
          onRemoveItem={onRemoveItem}
          isRemovePending={isRemovePending}
          graph={graph}
          statusByCategory={statusByCategory}
          flashingCategories={flashingCategories}
          overlaysVisible={overlaysVisible}
          connectorAnchors={connectorAnchors}
          onExplainIssue={explainIssue}
        />
      ) : isMotherboard ? (
        <MotherboardSlotBoardBody
          items={items}
          selectedCategory={selectedCategory}
          aiFocusCategories={aiFocusCategories}
          nextCategory={nextCategory}
          onSlotSelect={onSlotSelect}
          onClearAiFocus={onClearAiFocus}
          onRemoveItem={onRemoveItem}
          isRemovePending={isRemovePending}
          graph={graph}
          statusByCategory={statusByCategory}
          flashingCategories={flashingCategories}
          isClosing={isMotherboardClosing}
        />
      ) : isRelationMapVisible ? (
        <RelationMapBoardBody
          items={items}
          selectedCategory={selectedCategory}
          aiFocusCategories={aiFocusCategories}
          nextCategory={nextCategory}
          onSlotSelect={onSlotSelect}
          onRemoveItem={onRemoveItem}
          isRemovePending={isRemovePending}
          graph={graph}
          statusByCategory={statusByCategory}
          flashingCategories={flashingCategories}
        />
      ) : (
        <FusedSlotBoardBody
          items={items}
          selectedCategory={selectedCategory}
          aiFocusCategories={aiFocusCategories}
          nextCategory={nextCategory}
          onSlotSelect={onSlotSelect}
          onClearAiFocus={onClearAiFocus}
          onRemoveItem={onRemoveItem}
          onUpdateQuantity={onUpdateQuantity}
          isRemovePending={isRemovePending}
          isQuantityPending={isQuantityPending}
          graph={graph}
          statusByCategory={statusByCategory}
          boardProblem={boardProblem}
          flashingCategories={flashingCategories}
          onExplainIssue={explainIssue}
        />
      )}
      {(isMotherboard || isIsometric) && boardProblem ? (
        <SlotBoardProblemBanner
          problem={boardProblem}
          onExplain={() => explainIssue(undefined, boardProblem.tool)}
        />
      ) : null}
      {!isIsometric ? (
        <button
          type="button"
          aria-pressed={isMotherboard}
          onClick={toggleMotherboard}
          disabled={isMotherboardClosing}
          className={`absolute bottom-4 right-4 z-30 hidden rounded-lg border px-3.5 py-2 text-xs font-black shadow-lg transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue focus-visible:ring-offset-2 lg:block ${
            isMotherboard
              ? 'border-brand-blue bg-brand-blue text-white hover:bg-blue-700'
              : 'border-slate-200 bg-white text-slate-700 hover:border-brand-blue hover:text-brand-blue'
          }`}
        >
          {isMotherboard ? '실장도 접기' : '실장도 보기'}
        </button>
      ) : null}
      {isRelationMapVisible && !isIsometric && !isMotherboard ? (
        <RelationMapBanner
          problem={boardProblem}
          graph={graph}
          onExplain={() => explainIssue(undefined, boardProblem?.tool)}
          className="pointer-events-none absolute inset-x-4 bottom-4 z-[35] flex justify-center lg:bottom-5"
        />
      ) : null}
      {!isIsometric ? (
        <button
          type="button"
          data-testid="relation-map-open"
          aria-pressed={isRelationMapVisible}
          onClick={toggleRelationMap}
          className={`absolute bottom-4 left-4 z-30 hidden rounded-lg border px-3.5 py-2 text-xs font-black shadow-lg transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue focus-visible:ring-offset-2 lg:block ${
            isRelationMapVisible
              ? 'border-brand-blue bg-brand-blue text-white hover:bg-blue-700'
              : 'border-slate-200 bg-white text-slate-700 hover:border-brand-blue hover:text-brand-blue'
          }`}
        >
          {isRelationMapVisible ? '기본 관계도 보기' : '영향 지도 보기'}
        </button>
      ) : null}
    </div>
  );
}

// 상단 전환은 배치도·3D만 두고, 실장도는 보드 우측 하단의 별도 버튼으로 진입한다.
const SLOT_BOARD_MODE_OPTIONS: Array<{ mode: SlotBoardVisualMode; label: string }> = [
  { mode: 'fused', label: '배치도' },
  { mode: 'isometric', label: '3D' }
];

function SlotBoardModeSegments({
  value,
  onChange
}: {
  value: SlotBoardVisualMode;
  onChange?: (mode: SlotBoardVisualMode) => void;
}) {
  return (
    <div
      role="radiogroup"
      aria-label="보드 보기 방식"
      className="hidden items-center gap-0.5 rounded-full border border-slate-200 bg-white p-0.5 lg:inline-flex"
    >
      {SLOT_BOARD_MODE_OPTIONS.map((option) => {
        const active = value === option.mode;
        return (
          <button
            key={option.mode}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange?.(option.mode)}
            className={`rounded-full px-2 py-0.5 text-[10px] font-black transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue ${
              active ? 'bg-brand-blue text-white' : 'text-slate-500 hover:bg-slate-100 hover:text-slate-700'
            }`}
          >
            {option.label}
          </button>
        );
      })}
    </div>
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

function FusedSlotBoardBody({
  items,
  selectedCategory,
  aiFocusCategories,
  nextCategory,
  onSlotSelect,
  onClearAiFocus,
  onRemoveItem,
  onUpdateQuantity,
  isRemovePending,
  isQuantityPending,
  graph,
  statusByCategory,
  boardProblem,
  flashingCategories,
  onExplainIssue
}: {
  items: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  aiFocusCategories: PartCategory[];
  nextCategory?: PartCategory | null;
  onSlotSelect: (category: PartCategory) => void;
  onClearAiFocus?: () => void;
  onRemoveItem: (partId: string) => void;
  onUpdateQuantity: (partId: string, quantity: number) => void;
  isRemovePending: boolean;
  isQuantityPending: boolean;
  graph?: BuildGraphResolveResponse;
  statusByCategory: Map<string, 'PASS' | 'WARN' | 'FAIL'>;
  boardProblem: SlotBoardBannerProblem | null;
  flashingCategories: Set<PartCategory>;
  onExplainIssue: (category?: PartCategory, tool?: BuildGraphFocus['tool']) => void;
}) {
  return (
    // 보드 본체 — 배치도(기본): 실사 배치판(FusedPlateArt) 위에 부품 오버레이가 겹쳐진다(데스크톱 전용).
    // 모바일은 실장도와 같은 세로 카드 목록으로 폴백한다.
    <div
      data-testid="slot-board"
      data-visual-mode="fused"
      data-ai-focus-active={aiFocusCategories.length > 0 ? 'true' : 'false'}
      onClickCapture={() => {
        if (aiFocusCategories.length > 0) onClearAiFocus?.();
      }}
      className="slot-board-focus-scope slot-board-tray relative min-h-0 flex-1 flex-col gap-2 p-3 lg:block lg:overflow-hidden lg:p-0"
    >
      <FusedPlateArt
        items={items}
        selectedCategory={selectedCategory}
        aiFocusCategories={aiFocusCategories}
        statusByCategory={statusByCategory}
        flashingCategories={flashingCategories}
        onSlotSelect={onSlotSelect}
        onRemoveItem={onRemoveItem}
        onUpdateQuantity={onUpdateQuantity}
        isRemovePending={isRemovePending}
        isQuantityPending={isQuantityPending}
      />
      <SlotBoardProblemBanner
        problem={boardProblem}
        onExplain={() => onExplainIssue(undefined, boardProblem?.tool)}
      />
      <div data-testid="slot-board-mobile-slots" className="flex flex-col gap-2 lg:hidden">
        {SLOT_CONFIGS.map((slot) => (
          <MotherboardSlot
            key={slot.category}
            slot={slot}
            layout={slot.layout}
            items={items.filter((item) => item.category === slot.category)}
            problemStatus={statusByCategory.get(slot.category)}
            isSelected={aiFocusCategories.length === 0 && selectedCategory === slot.category}
            isAiSpotlighted={aiFocusCategories.includes(slot.category)}
            isAiDimmed={aiFocusCategories.length > 0 && !aiFocusCategories.includes(slot.category)}
            isNext={aiFocusCategories.length === 0 && nextCategory === slot.category}
            isFlashing={flashingCategories.has(slot.category)}
            onSelect={() => onSlotSelect(slot.category)}
            onRemoveItem={onRemoveItem}
            isRemovePending={isRemovePending}
          />
        ))}
      </div>
    </div>
  );
}

function RelationMapBoardBody({
  items,
  selectedCategory,
  aiFocusCategories,
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
  aiFocusCategories: PartCategory[];
  nextCategory?: PartCategory | null;
  onSlotSelect: (category: PartCategory) => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
  graph?: BuildGraphResolveResponse;
  statusByCategory: Map<string, 'PASS' | 'WARN' | 'FAIL'>;
  flashingCategories: Set<PartCategory>;
}) {
  const issueFocusCategory = firstProblemCategory(graph) ?? firstFilledCategory(items) ?? null;
  const focusCategory = selectedCategory ?? issueFocusCategory ?? 'GPU';
  const reasonByCategory = relationMapReasonsByCategory(graph);

  return (
    <div
      data-testid="slot-board"
      data-visual-mode="relation-map"
      className="relative min-h-0 flex-1 overflow-auto bg-white px-4 pb-4 pt-3"
    >
      <div className="w-full min-w-[660px]">
        <div className="mx-auto mt-2 w-full max-w-[1120px]">
          <div className="relative h-[560px] rounded-lg bg-white">
            <RelationMapEdges
              items={items}
              graph={graph}
              focusCategory={focusCategory}
              selectedCategory={selectedCategory}
            />
            {RELATION_MAP_NODE_ORDER.map((category) => {
              const slot = slotConfigFor(category);
              if (!slot) return null;
              const categoryItems = items.filter((item) => item.category === category);
              return (
                <RelationMapNode
                  key={category}
                  slot={slot}
                  items={categoryItems}
                  focusCategory={focusCategory}
                  selectedCategory={selectedCategory}
                  nextCategory={nextCategory}
                  status={statusByCategory.get(category)}
                  reason={reasonByCategory.get(category)}
                  isAiSpotlighted={aiFocusCategories.includes(category)}
                  isAiDimmed={aiFocusCategories.length > 0 && !aiFocusCategories.includes(category)}
                  isFlashing={flashingCategories.has(category)}
                  onSelect={() => onSlotSelect(category)}
                  onRemoveItem={onRemoveItem}
                  isRemovePending={isRemovePending}
                />
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}

const RELATION_MAP_NODE_ORDER: PartCategory[] = [
  'CPU',
  'COOLER',
  'RAM',
  'STORAGE',
  'MOTHERBOARD',
  'GPU',
  'PSU',
  'CASE'
];

const RELATION_MAP_LAYOUTS: Record<PartCategory, SlotConfig['layout']> = {
  CPU: { x: 4.8, y: 36.4, w: 26.4, h: 25.2 },
  COOLER: { x: 4.8, y: 68.4, w: 26.4, h: 25.2 },
  RAM: { x: 39.6, y: 6.4, w: 22.8, h: 25.2 },
  STORAGE: { x: 39.6, y: 68.4, w: 22.8, h: 25.2 },
  MOTHERBOARD: { x: 37.8, y: 36.4, w: 26.4, h: 25.2 },
  GPU: { x: 71, y: 37.6, w: 24, h: 25.2 },
  PSU: { x: 71, y: 5.2, w: 24, h: 25.2 },
  CASE: { x: 71, y: 68.4, w: 24, h: 25.2 }
};

type RelationMapLane = 'straight' | 'topRail' | 'bottomRail';
type RelationMapEdgeEmphasis = 'direct' | 'indirect';
const RELATION_MAP_TOP_RAIL_Y = 2.4;
const RELATION_MAP_BOTTOM_RAIL_Y = 97.2;

const RELATION_MAP_CANONICAL_EDGES: Array<{
  from: PartCategory;
  to: PartCategory;
  lane: RelationMapLane;
  emphasis?: RelationMapEdgeEmphasis;
  label?: string;
}> = [
  { from: 'MOTHERBOARD', to: 'CPU', lane: 'straight' },
  { from: 'MOTHERBOARD', to: 'RAM', lane: 'straight' },
  { from: 'MOTHERBOARD', to: 'STORAGE', lane: 'straight' },
  { from: 'MOTHERBOARD', to: 'GPU', lane: 'straight' },
  { from: 'GPU', to: 'PSU', lane: 'straight', emphasis: 'direct', label: 'GPU 전력' },
  { from: 'CPU', to: 'PSU', lane: 'topRail', emphasis: 'direct', label: 'CPU 전력' },
  { from: 'CPU', to: 'COOLER', lane: 'straight', emphasis: 'direct', label: '쿨러 호환' },
  { from: 'CASE', to: 'COOLER', lane: 'bottomRail', emphasis: 'direct', label: '쿨러 공간' },
  { from: 'CASE', to: 'GPU', lane: 'straight', emphasis: 'direct', label: 'GPU 장착' }
];

function RelationMapNode({
  slot,
  items,
  focusCategory,
  selectedCategory,
  nextCategory,
  status,
  reason,
  isAiSpotlighted,
  isAiDimmed,
  isFlashing,
  onSelect,
  onRemoveItem,
  isRemovePending
}: {
  slot: SlotConfig;
  items: QuoteDraftItem[];
  focusCategory: PartCategory;
  selectedCategory: PartCategory | null;
  nextCategory?: PartCategory | null;
  status?: 'PASS' | 'WARN' | 'FAIL';
  reason?: RelationMapReason;
  isAiSpotlighted: boolean;
  isAiDimmed: boolean;
  isFlashing: boolean;
  onSelect: () => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
}) {
  const layout = RELATION_MAP_LAYOUTS[slot.category];
  const filled = items.length > 0;
  const primaryItem = items[0];
  const isFocused = focusCategory === slot.category;
  const isSelected = selectedCategory === slot.category;
  const isNext = nextCategory === slot.category;
  const imageSrc = primaryItem?.externalOffer?.imageUrl || slot.glyph;
  const itemTitle = filled
    ? items.length > 1 ? `${primaryItem.name} 외 ${items.length - 1}개` : primaryItem.name
    : '부품 선택 필요';
  const fullNameTitle = filled ? items.map((item) => item.name).join('\n') : itemTitle;
  const statusLabel = relationMapNodeStatusLabel(filled, isFocused, status, reason?.label);
  const layoutVars: CSSProperties = {
    ['--rx' as string]: `${layout.x}%`,
    ['--ry' as string]: `${layout.y}%`,
    ['--rw' as string]: `${layout.w}%`,
    ['--rh' as string]: `${layout.h}%`
  };

  return (
    <div
      data-testid={`relation-map-node-${slot.category}`}
      data-selected={isSelected ? 'true' : 'false'}
      data-focus={isFocused ? 'true' : 'false'}
      data-mounted={filled ? 'true' : 'false'}
      data-status={status ?? 'NONE'}
      data-ai-spotlight={isAiSpotlighted ? 'true' : 'false'}
      data-ai-dimmed={isAiDimmed ? 'true' : 'false'}
      style={layoutVars}
      className={`group absolute left-[var(--rx)] top-[var(--ry)] z-20 h-[var(--rh)] w-[var(--rw)] rounded-md border bg-white text-left shadow-sm transition ${
        isFocused
          ? 'border-2 border-brand-blue shadow-md ring-2 ring-blue-100'
          : status === 'FAIL'
            ? 'border-2 border-red-400'
            : status === 'WARN'
              ? 'border-2 border-amber-300'
              : filled
                ? 'border-slate-200 hover:border-brand-blue/70'
                : isNext
                  ? 'border-brand-blue/50 bg-blue-50/40'
                  : 'border-slate-200'
      } ${isAiDimmed ? 'opacity-40' : ''} ${isFlashing ? 'slot-attach-flash' : ''}`}
    >
      {isFocused ? (
        <span className="absolute -left-3 -top-3 z-30 grid h-7 w-7 place-items-center rounded-full bg-brand-blue text-[13px] font-black text-white shadow-sm">
          {slotOrderNumber(slot.category)}
        </span>
      ) : status === 'FAIL' || status === 'WARN' ? (
        <span className={`absolute -left-3 -top-3 z-30 grid h-7 w-7 place-items-center rounded-full text-[13px] font-black text-white shadow-sm ${
          status === 'FAIL' ? 'bg-red-500' : 'bg-amber-500'
        }`}>
          {slotOrderNumber(slot.category)}
        </span>
      ) : null}
      <button
        type="button"
        onClick={onSelect}
        aria-label={`${slot.label} 선택`}
        aria-pressed={isSelected}
        title={fullNameTitle}
        className="flex h-full w-full flex-col justify-center gap-1 rounded-md px-3.5 py-2.5 text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue"
      >
        <span className="flex w-full min-w-0 items-center gap-3">
          <span className={`grid h-14 w-14 shrink-0 place-items-center overflow-hidden rounded ${
            filled ? 'border border-slate-100 bg-slate-50' : 'border border-slate-300 bg-slate-50 shadow-inner'
          }`}>
            <img
              src={imageSrc}
              alt=""
              aria-hidden="true"
              onError={(event) => {
                event.currentTarget.src = slot.glyph;
              }}
              className="h-full w-full object-contain p-1"
            />
          </span>
          <span className="min-w-0 flex-1">
            <span className="block text-[15px] font-black leading-tight text-slate-700">{slot.label}</span>
          </span>
        </span>
        <span
          title={fullNameTitle}
          className={`block w-full truncate pb-0.5 text-[13px] font-bold leading-5 ${filled ? 'text-commerce-ink' : 'text-slate-400'}`}
        >
          {itemTitle}
        </span>
        <span className="flex w-full justify-end">
          <span className={`max-w-full whitespace-nowrap rounded px-2 py-0.5 text-[12px] font-black ${
            isFocused
              ? 'bg-blue-50 text-brand-blue'
              : status === 'FAIL'
                ? 'bg-red-50 text-red-600'
                : status === 'WARN'
                  ? 'bg-amber-50 text-amber-700'
                  : filled
                    ? 'bg-slate-50 text-slate-500'
                    : 'bg-white text-slate-400'
          }`}>
            {statusLabel}
          </span>
        </span>
      </button>
      {filled && !isMultiItemCategory(slot.category) ? (
        <button
          type="button"
          aria-label={`${primaryItem.name} 견적에서 제거`}
          disabled={isRemovePending}
          onClick={(event) => {
            event.preventDefault();
            event.stopPropagation();
            onRemoveItem(primaryItem.partId);
          }}
          className="absolute right-1.5 top-1.5 z-30 rounded border border-slate-200 bg-white px-2 py-0.5 text-[12px] font-black text-slate-400 opacity-0 transition group-hover:opacity-100 focus-visible:opacity-100 hover:border-commerce-sale hover:text-commerce-sale disabled:cursor-wait"
        >
          빼기
        </button>
      ) : null}
    </div>
  );
}

function RelationMapEdges({
  items,
  graph,
  focusCategory,
  selectedCategory
}: {
  items: QuoteDraftItem[];
  graph?: BuildGraphResolveResponse;
  focusCategory: PartCategory;
  selectedCategory: PartCategory | null;
}) {
  const filledCategories = new Set(items.map((item) => item.category));
  const paths = RELATION_MAP_CANONICAL_EDGES.map((edge) => {
    const fromLayout = RELATION_MAP_LAYOUTS[edge.from];
    const toLayout = RELATION_MAP_LAYOUTS[edge.to];
    const status = relationStatusBetween(graph, filledCategories, edge.from, edge.to);
    const touchesFocus = edge.from === focusCategory || edge.to === focusCategory;
    const kind: 'selected' | 'direct' | 'indirect' | 'none' =
      edge.from === selectedCategory || edge.to === selectedCategory
        ? status === 'FAIL' || status === 'WARN' ? 'direct' : 'selected'
        : touchesFocus
          ? edge.emphasis === 'direct' ? 'direct' : 'indirect'
          : 'none';
    return {
      ...edge,
      fromLayout,
      toLayout,
      status,
      kind,
      path: relationMapPath(edge.lane, fromLayout, toLayout)
    };
  });

  return (
    <div data-testid="relation-map-edges" aria-hidden="true" className="pointer-events-none absolute inset-0 z-10">
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="h-full w-full">
        {paths.map((edge) => {
          const style = relationMapEdgeStyle(edge.kind, edge.status);
          return (
            <path
              key={`${edge.from}-${edge.to}`}
              d={edge.path}
              fill="none"
              stroke={style.stroke}
              strokeWidth={style.width}
              strokeDasharray={style.dash}
              strokeLinecap="round"
              strokeLinejoin="round"
              vectorEffect="non-scaling-stroke"
              opacity={style.opacity}
            />
          );
        })}
      </svg>
      {paths.filter((edge) => isProblemStatus(edge.status) && edge.label).map((edge) => {
        const label = relationMapLabelPoint(edge.lane, edge.fromLayout, edge.toLayout);
        return (
          <span
            key={`label-${edge.from}-${edge.to}`}
            className={`absolute -translate-x-1/2 -translate-y-1/2 rounded border px-1.5 py-0.5 text-[9px] font-black shadow-sm ${EDGE_LABEL_CLASSES[edge.status]}`}
            style={{ left: `${label.x}%`, top: `${label.y}%` }}
          >
            {edge.label}
          </span>
        );
      })}
    </div>
  );
}

function RelationMapBanner({
  problem,
  graph,
  onExplain,
  className = 'absolute inset-x-3 bottom-4 z-30 flex justify-center'
}: {
  problem: SlotBoardBannerProblem | null;
  graph?: BuildGraphResolveResponse;
  onExplain?: () => void;
  className?: string;
}) {
  const issues = relationMapIssues(graph);
  const count = issues.length;
  const status = problem?.status ?? issues[0]?.status;
  const hasProblem = Boolean(status);
  const message = hasProblem
    ? problem?.message ?? issues[0]?.summary ?? `현재 선택한 부품은 바로 적용할 수 없습니다. 문제 ${Math.max(1, count)}개 발생`
    : '현재 선택한 부품 기준으로 감지된 문제가 없습니다.';

  return (
    <div
      data-testid="relation-map-bottom-banner"
      className={className}
    >
      <div className={`pointer-events-auto flex max-w-[88%] flex-wrap items-center justify-center gap-2 rounded-md border bg-white px-2.5 py-1.5 shadow-sm ${
        hasProblem
          ? status === 'WARN'
            ? 'border-amber-500'
            : 'border-red-500'
          : 'border-emerald-500 text-emerald-600'
      }`}>
        <p className={`text-center text-[13.5px] font-semibold sm:text-[16px] ${status === 'WARN' ? 'text-amber-500' : status === 'FAIL' ? 'text-red-500' : 'text-emerald-600'}`}>{message}</p>
        {hasProblem && onExplain ? <ExplainIssueButton onClick={onExplain} /> : null}
      </div>
    </div>
  );
}

type RelationMapIssue = {
  status: SlotProblemStatus;
  title: string;
  summary: string;
  categories: PartCategory[];
};

type RelationMapReason = {
  status: SlotProblemStatus;
  label: string;
  detail: string;
};

function relationMapNodeStatusLabel(filled: boolean, isFocused: boolean, status?: 'PASS' | 'WARN' | 'FAIL', reasonLabel?: string) {
  if (isFocused) return '선택 부품';
  if (status === 'FAIL') return reasonLabel ?? '조정 필요';
  if (status === 'WARN') return reasonLabel ?? '주의';
  if (filled) return '문제없음';
  return '연결 없음';
}

function relationMapReasonsByCategory(graph?: BuildGraphResolveResponse) {
  const reasons = new Map<PartCategory, RelationMapReason>();
  if (!graph) {
    return reasons;
  }

  const addReason = (
    category: PartCategory | undefined,
    status: string,
    text?: string,
    fallbackLabel?: string,
    allowSameRankOverride = false
  ) => {
    if (!category || !isProblemStatus(status)) {
      return;
    }
    const detail = text?.trim() || fallbackLabel || (status === 'FAIL' ? '조정 필요' : '주의 필요');
    const label = compactRelationReasonLabel(detail, status, fallbackLabel);
    const current = reasons.get(category);
    if (
      !current ||
      problemStatusRank(status) > problemStatusRank(current.status) ||
      (allowSameRankOverride && problemStatusRank(status) === problemStatusRank(current.status) && isGenericRelationReasonLabel(current.label))
    ) {
      reasons.set(category, { status, label, detail });
    }
  };

  const problemDetailsByCategory = slotProblemDetailsByCategory(graph);
  problemDetailsByCategory.forEach((detail, category) => {
    addReason(category, detail.status, detail.reasons[0] ?? detail.title, detail.title, true);
  });

  if (reasons.size > 0) {
    return reasons;
  }

  // If the graph only contains broad tool-level results, keep the old category fallback.
  // Detailed node/edge/insight reasons above are preferred because they match the placement view.
  graph.toolResults.forEach((result) => {
    if (!isProblemStatus(result.status)) {
      return;
    }
    const categories = relationMapToolCategories(result.tool);
    const fallbackLabel = relationMapToolReasonLabel(result.tool);
    categories.forEach((category) => addReason(category, result.status, result.summary, fallbackLabel));
  });

  return reasons;
}

function relationMapToolCategories(tool: string): PartCategory[] {
  const normalizedTool = tool.toLowerCase();
  if (normalizedTool === 'power') return ['CPU', 'GPU', 'PSU'];
  if (normalizedTool === 'size') return ['GPU', 'CASE', 'COOLER'];
  if (normalizedTool === 'compatibility') return ['CPU', 'MOTHERBOARD', 'RAM', 'STORAGE'];
  if (normalizedTool === 'performance') return ['CPU', 'GPU', 'RAM'];
  return [];
}

function relationMapToolReasonLabel(tool: string) {
  const normalizedTool = tool.toLowerCase();
  if (normalizedTool === 'power') return '전력 부족';
  if (normalizedTool === 'size') return '길이 확인';
  if (normalizedTool === 'compatibility') return '호환 확인';
  if (normalizedTool === 'performance') return '성능 확인';
  return '확인 필요';
}

function compactRelationReasonLabel(text: string, status: SlotProblemStatus, fallbackLabel?: string) {
  const normalized = text.replace(/\s+/g, ' ').trim();
  if (!normalized) {
    return fallbackLabel ?? (status === 'FAIL' ? '조정 필요' : '주의');
  }
  if (/(전력|파워|정격|용량|W\b|와트)/i.test(normalized)) {
    return status === 'WARN' && /(여유|빠듯|낮|확인)/.test(normalized) ? '전력 확인' : '전력 부족';
  }
  if (/(길이|케이스|장착|간섭|크기|높이|공간)/.test(normalized)) {
    return /(초과|부족|불가|안\s*됨|어렵)/.test(normalized) || status === 'FAIL' ? '길이 초과' : '길이 확인';
  }
  if (/(소켓|칩셋)/.test(normalized)) {
    return status === 'FAIL' ? '소켓 불일치' : '소켓 확인';
  }
  if (/(메모리|램|RAM|DDR)/i.test(normalized)) {
    return status === 'FAIL' ? '메모리 불일치' : '메모리 확인';
  }
  if (/(쿨러|발열|온도|냉각)/.test(normalized)) {
    return status === 'FAIL' ? '쿨링 부족' : '쿨링 확인';
  }
  if (/(성능|병목|프레임|FPS|점수)/i.test(normalized)) {
    return '성능 확인';
  }
  if (/(가격|예산|비용)/.test(normalized)) {
    return '예산 확인';
  }
  if (fallbackLabel) {
    return fallbackLabel;
  }
  const compact = normalized.split(/[.:,·\-–—]/)[0]?.trim() || normalized;
  return compact.length > 8 ? `${compact.slice(0, 8)}…` : compact;
}

function isGenericRelationReasonLabel(label: string) {
  return label === '조정 필요' || label === '주의' || label === '확인 필요';
}

function firstFilledCategory(items: QuoteDraftItem[]): PartCategory | null {
  const firstItem = items.find((item) => slotConfigFor(item.category));
  return firstItem ? firstItem.category as PartCategory : null;
}

function firstProblemCategory(graph?: BuildGraphResolveResponse): PartCategory | null {
  if (!graph) {
    return null;
  }
  const categoryByNodeId = graphCategoryByNodeId(graph);
  const problemNode = graph.nodes.find((node) => isProblemStatus(node.status) && slotCategoryFromGraphCategory(node.category));
  if (problemNode) {
    return slotCategoryFromGraphCategory(problemNode.category) ?? null;
  }
  const problemEdge = graph.edges.find((edge) => isProblemStatus(edge.status));
  if (problemEdge) {
    return categoryByNodeId.get(problemEdge.source) ?? categoryByNodeId.get(problemEdge.target) ?? null;
  }
  const problemInsight = graph.insights.find((insight) => isProblemStatus(insight.status));
  if (problemInsight) {
    for (const nodeId of problemInsight.relatedNodeIds) {
      const category = categoryByNodeId.get(nodeId);
      if (category) return category;
    }
  }
  return null;
}

function relationStatusBetween(
  graph: BuildGraphResolveResponse | undefined,
  filledCategories: Set<string>,
  from: PartCategory,
  to: PartCategory
): SlotEdgeStatus {
  if (!filledCategories.has(from) || !filledCategories.has(to)) {
    return 'PENDING';
  }
  const graphEdge = findGraphEdge(graph, from, to);
  if (graphEdge) {
    return graphEdge.status;
  }
  const toolStatus = relationToolStatusBetween(graph, from, to);
  if (toolStatus) {
    return toolStatus;
  }
  return 'BASE';
}

function relationToolStatusBetween(
  graph: BuildGraphResolveResponse | undefined,
  from: PartCategory,
  to: PartCategory
): SlotEdgeStatus | undefined {
  if (!graph) {
    return undefined;
  }
  const tools = relationMapToolsForPair(from, to);
  if (tools.length === 0) {
    return undefined;
  }
  let status: 'PASS' | 'WARN' | 'FAIL' | undefined;
  graph.toolResults.forEach((result) => {
    const tool = result.tool.toLowerCase();
    if (!tools.includes(tool) || !isGraphStatus(result.status)) {
      return;
    }
    if (!status || graphStatusRank(result.status) > graphStatusRank(status)) {
      status = result.status;
    }
  });
  return status;
}

function relationMapToolsForPair(first: PartCategory, second: PartCategory) {
  const has = (left: PartCategory, right: PartCategory) =>
    (first === left && second === right) || (first === right && second === left);
  if (has('GPU', 'PSU') || has('CPU', 'PSU')) {
    return ['power'];
  }
  if (has('GPU', 'CASE') || has('COOLER', 'CASE')) {
    return ['size'];
  }
  if (
    has('CPU', 'MOTHERBOARD') ||
    has('MOTHERBOARD', 'RAM') ||
    has('MOTHERBOARD', 'STORAGE') ||
    has('MOTHERBOARD', 'GPU') ||
    has('CPU', 'COOLER')
  ) {
    return ['compatibility'];
  }
  return [];
}

function relationMapPath(lane: RelationMapLane, from: SlotConfig['layout'], to: SlotConfig['layout']) {
  if (lane === 'topRail') {
    const start = relationMapSideAnchor(from, 'top');
    const end = relationMapSideAnchor(to, 'top');
    return `M ${start.x} ${start.y} V ${RELATION_MAP_TOP_RAIL_Y} H ${end.x} V ${end.y}`;
  }
  if (lane === 'bottomRail') {
    const start = relationMapSideAnchor(from, 'bottom');
    const end = relationMapSideAnchor(to, 'bottom');
    return `M ${start.x} ${start.y} V ${RELATION_MAP_BOTTOM_RAIL_Y} H ${end.x} V ${end.y}`;
  }
  const start = relationMapAnchor(from, to);
  const end = relationMapAnchor(to, from);
  if (Math.abs(start.x - end.x) < 0.01) {
    return `M ${start.x} ${start.y} V ${end.y}`;
  }
  if (Math.abs(start.y - end.y) < 0.01) {
    return `M ${start.x} ${start.y} H ${end.x}`;
  }
  return `M ${start.x} ${start.y} H ${end.x} V ${end.y}`;
}

function relationMapSideAnchor(box: SlotConfig['layout'], side: 'top' | 'bottom') {
  const center = boxCenter(box);
  return {
    x: center.x,
    y: side === 'top' ? box.y : box.y + box.h
  };
}

function relationMapAnchor(box: SlotConfig['layout'], target: SlotConfig['layout']) {
  const center = boxCenter(box);
  const targetCenter = boxCenter(target);
  if (Math.abs(targetCenter.x - center.x) > Math.abs(targetCenter.y - center.y)) {
    return {
      x: targetCenter.x > center.x ? box.x + box.w : box.x,
      y: center.y
    };
  }
  return {
    x: center.x,
    y: targetCenter.y > center.y ? box.y + box.h : box.y
  };
}

function relationMapLabelPoint(lane: RelationMapLane, from: SlotConfig['layout'], to: SlotConfig['layout']) {
  if (lane === 'topRail') {
    const start = relationMapSideAnchor(from, 'top');
    const end = relationMapSideAnchor(to, 'top');
    return {
      x: start.x + (end.x - start.x) * 0.5,
      y: RELATION_MAP_TOP_RAIL_Y
    };
  }
  if (lane === 'bottomRail') {
    const start = relationMapSideAnchor(from, 'bottom');
    const end = relationMapSideAnchor(to, 'bottom');
    return {
      x: start.x + (end.x - start.x) * 0.5,
      y: RELATION_MAP_BOTTOM_RAIL_Y
    };
  }
  const start = relationMapAnchor(from, to);
  const end = relationMapAnchor(to, from);
  return {
    x: start.x + (end.x - start.x) * 0.5,
    y: start.y + (end.y - start.y) * 0.5
  };
}

function relationMapEdgeStyle(kind: 'selected' | 'direct' | 'indirect' | 'none', status: SlotEdgeStatus) {
  const style = EDGE_STROKES[status];
  if (kind === 'none') {
    return { stroke: style.stroke, dash: style.dash, width: 1.8, opacity: status === 'WARN' || status === 'FAIL' ? 0.85 : 0.5 };
  }
  if (kind === 'indirect') {
    return { stroke: style.stroke, dash: style.dash, width: 2.2, opacity: status === 'PENDING' ? 0.72 : 0.82 };
  }
  return { stroke: style.stroke, dash: style.dash, width: status === 'WARN' || status === 'FAIL' ? 3 : 2.6, opacity: 0.95 };
}

function relationMapIssues(graph?: BuildGraphResolveResponse): RelationMapIssue[] {
  if (!graph) {
    return [];
  }
  const issues: RelationMapIssue[] = [];
  const categoryByNodeId = graphCategoryByNodeId(graph);
  const addIssue = (status: string, title: string, summary: string, categories: Array<PartCategory | undefined>) => {
    if (!isProblemStatus(status)) {
      return;
    }
    const cleanSummary = summary.trim();
    const cleanTitle = title.trim() || (status === 'FAIL' ? '조정 필요' : '주의 필요');
    if (!cleanSummary) {
      return;
    }
    issues.push({
      status,
      title: cleanTitle,
      summary: cleanSummary,
      categories: categories.filter((category): category is PartCategory => Boolean(category))
    });
  };

  const toolIssues: RelationMapIssue[] = [];
  graph.toolResults.forEach((result) => {
    if (!isProblemStatus(result.status) || !result.summary.trim()) {
      return;
    }
    toolIssues.push({
      status: result.status,
      title: result.tool === 'power' ? '파워 용량 부족' : `${result.tool} 확인 필요`,
      summary: result.summary.trim(),
      categories: []
    });
  });
  if (toolIssues.length > 0) {
    return uniqueRelationMapIssues(toolIssues);
  }

  graph.toolResults.forEach((result) => {
    addIssue(result.status, result.tool === 'power' ? '파워 용량 부족' : `${result.tool} 확인 필요`, result.summary, []);
  });
  graph.nodes.forEach((node) => {
    addIssue(node.status, node.label, node.detail ?? '', [slotCategoryFromGraphCategory(node.category)]);
  });
  graph.edges.forEach((edge) => {
    addIssue(edge.status, edge.label, edge.summary || edge.label, [
      categoryByNodeId.get(edge.source),
      categoryByNodeId.get(edge.target)
    ]);
  });
  graph.insights.forEach((insight) => {
    addIssue(insight.status, insight.title, insight.description, insight.relatedNodeIds.map((nodeId) => categoryByNodeId.get(nodeId)));
  });

  return uniqueRelationMapIssues(issues);
}

function uniqueRelationMapIssues(issues: RelationMapIssue[]) {
  const seen = new Set<string>();
  return issues.filter((issue) => {
    const key = `${issue.status}:${issue.title}:${issue.summary}`;
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

function MotherboardSlotBoardBody({
  items,
  selectedCategory,
  aiFocusCategories,
  nextCategory,
  onSlotSelect,
  onClearAiFocus,
  onRemoveItem,
  isRemovePending,
  graph,
  statusByCategory,
  flashingCategories,
  isClosing
}: {
  items: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  aiFocusCategories: PartCategory[];
  nextCategory?: PartCategory | null;
  onSlotSelect: (category: PartCategory) => void;
  onClearAiFocus?: () => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
  graph?: BuildGraphResolveResponse;
  statusByCategory: Map<string, 'PASS' | 'WARN' | 'FAIL'>;
  flashingCategories: Set<PartCategory>;
  isClosing: boolean;
}) {
  return (
    // 보드 본체 — 실장도: 추상 메인보드 평면도의 실장 지점(소켓/DIMM/PCIe/M.2)에 부품이 꽂히고,
    // 보드에 안 꽂히는 부품은 케이스 좌상·파워 좌하·쿨러 상단(소켓 위)에 도킹된다.
    <div
      data-testid="slot-board"
      data-visual-mode="motherboard"
      data-closing={isClosing ? 'true' : 'false'}
      data-ai-focus-active={aiFocusCategories.length > 0 ? 'true' : 'false'}
      data-ai-focus-motherboard={aiFocusCategories.includes('MOTHERBOARD') ? 'true' : 'false'}
      onClickCapture={() => {
        if (aiFocusCategories.length > 0) onClearAiFocus?.();
      }}
      className="slot-board-focus-scope slot-board-motherboard-drawer slot-board-tray relative min-h-0 flex-1 flex-col gap-2 p-3 lg:block lg:overflow-hidden lg:p-0"
    >
      <BoardPlanArt />
      <SlotBoardEdges
        items={items}
        graph={graph}
        selectedCategory={aiFocusCategories.length > 0 ? null : selectedCategory}
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
          isSelected={aiFocusCategories.length === 0 && selectedCategory === slot.category}
          isAiSpotlighted={aiFocusCategories.includes(slot.category)}
          isAiDimmed={aiFocusCategories.length > 0 && !aiFocusCategories.includes(slot.category)}
          isNext={aiFocusCategories.length === 0 && nextCategory === slot.category}
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
  aiFocusCategories,
  nextCategory,
  onSlotSelect,
  onClearSelection,
  onClearAiFocus,
  onRemoveItem,
  isRemovePending,
  graph,
  statusByCategory,
  flashingCategories,
  overlaysVisible,
  connectorAnchors,
  onExplainIssue
}: {
  items: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  aiFocusCategories: PartCategory[];
  nextCategory?: PartCategory | null;
  onSlotSelect: (category: PartCategory) => void;
  onClearSelection?: () => void;
  onClearAiFocus?: () => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
  graph?: BuildGraphResolveResponse;
  statusByCategory: Map<string, 'PASS' | 'WARN' | 'FAIL'>;
  flashingCategories: Set<PartCategory>;
  overlaysVisible: boolean;
  connectorAnchors?: ConnectorAnchors;
  onExplainIssue: (category?: PartCategory, tool?: BuildGraphFocus['tool']) => void;
}) {
  const problemDetailsByCategory = slotProblemDetailsByCategory(graph);
  const [activeProblemCategory, setActiveProblemCategory] = useState<PartCategory | null>(null);
  const [hoveredCategory, setHoveredCategory] = useState<PartCategory | null>(null);
  const aiFocusSet = new Set(aiFocusCategories);
  const hasAiFocus = aiFocusSet.size > 0;
  const fallbackFocusCategory = hoveredCategory ?? selectedCategory;
  const focusCategories = hasAiFocus
    ? aiFocusSet
    : new Set<PartCategory>(fallbackFocusCategory ? [fallbackFocusCategory] : []);
  const isMotherboardSceneFocused = focusCategories.has('MOTHERBOARD');
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
      data-ai-focus-active={hasAiFocus ? 'true' : 'false'}
      data-ai-focus-motherboard={aiFocusSet.has('MOTHERBOARD') ? 'true' : 'false'}
      data-celebrating={celebrating ? 'true' : 'false'}
      onClickCapture={() => {
        if (hasAiFocus) onClearAiFocus?.();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget && selectedCategory) {
          onClearSelection?.();
        }
      }}
      className="slot-board-focus-scope relative min-h-0 flex-1 flex-col gap-2 bg-slate-50/60 p-3 lg:block lg:overflow-hidden lg:bg-[#f6fbff] lg:p-4"
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
        focusCategories={focusCategories}
        aiFocusCategories={aiFocusSet}
        onHoverChange={setHoveredCategory}
        onSlotSelect={onSlotSelect}
        onProblemOpen={openProblemDetail}
      />
      <IsoCardConnector
        selectedCategory={hasAiFocus ? null : selectedCategory}
        status={!hasAiFocus && selectedCategory ? statusByCategory.get(selectedCategory) ?? 'PENDING' : 'PENDING'}
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
          isSelected={!hasAiFocus && selectedCategory === slot.category}
          isAiSpotlighted={aiFocusSet.has(slot.category)}
          isAiDimmed={hasAiFocus && !aiFocusSet.has(slot.category)}
          isNext={!hasAiFocus && nextCategory === slot.category}
          isFlashing={flashingCategories.has(slot.category)}
          isHovered={!hasAiFocus && hoveredCategory === slot.category}
          cardsVisible={overlaysVisible || aiFocusSet.has(slot.category)}
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
          onExplain={() => onExplainIssue(activeProblem.category, toolForCategory(activeProblem.category))}
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
  isAiSpotlighted,
  isAiDimmed,
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
  isAiSpotlighted: boolean;
  isAiDimmed: boolean;
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
      data-ai-spotlight={isAiSpotlighted ? 'true' : 'false'}
      data-ai-dimmed={isAiDimmed ? 'true' : 'false'}
      data-mounted={filled ? 'true' : 'false'}
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
      {isAiSpotlighted && !filled ? (
        <span
          data-testid={`slot-ai-unmounted-${slot.category}`}
          className="pointer-events-none absolute bottom-1 right-1 z-30 rounded border border-blue-200 bg-blue-50/95 px-1.5 py-0.5 text-[9px] font-black text-brand-blue shadow-sm"
        >
          미장착
        </span>
      ) : null}
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
  focusCategories,
  aiFocusCategories,
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
  focusCategories: Set<PartCategory>;
  aiFocusCategories: Set<PartCategory>;
  onHoverChange: (category: PartCategory | null) => void;
  onSlotSelect: (category: PartCategory) => void;
  onProblemOpen: (category: PartCategory) => void;
}) {
  const partFocusCategories = new Set<PartCategory>(
    [...focusCategories].filter((category) => category !== 'MOTHERBOARD')
  );
  const motherboardSceneFocused = focusCategories.has('MOTHERBOARD');

  return (
    <div className="pointer-events-none absolute inset-2 z-[5] hidden lg:block">
      {SLOT_CONFIGS.map((slot) => {
        const isSpotlighted = partFocusCategories.has(slot.category);
        const isAiPartSpotlighted = aiFocusCategories.has(slot.category) && slot.category !== 'MOTHERBOARD';
        const isSelected = selectedCategory === slot.category && slot.category !== 'MOTHERBOARD';
        return (
          <IsoPart
            key={slot.category}
            slot={slot}
            items={items.filter((item) => item.category === slot.category)}
            isMounting={flashingCategories.has(slot.category)}
            status={statusByCategory.get(slot.category)}
            isHovered={aiFocusCategories.size === 0 && hoveredCategory === slot.category && slot.category !== 'MOTHERBOARD'}
            isDimmed={partFocusCategories.size > 0 ? !isSpotlighted : motherboardSceneFocused}
            isSpotlighted={isSpotlighted}
            isAiSpotlighted={isAiPartSpotlighted}
            isAiDimmed={aiFocusCategories.size > 0 && !isAiPartSpotlighted}
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
  isAiSpotlighted,
  isAiDimmed,
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
  isAiSpotlighted: boolean;
  isAiDimmed: boolean;
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
      data-ai-spotlight={isAiSpotlighted ? 'true' : 'false'}
      data-ai-dimmed={isAiDimmed ? 'true' : 'false'}
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

type SlotBoardBannerProblem = {
  status: SlotProblemStatus;
  message: string;
  tool?: BuildGraphFocus['tool'];
};

function SlotBoardProblemBanner({ problem, onExplain }: { problem: SlotBoardBannerProblem | null; onExplain?: () => void }) {
  if (!problem) {
    return null;
  }
  const isFail = problem.status === 'FAIL';

  return (
    <div className="pointer-events-none absolute inset-x-4 bottom-4 z-[35] flex justify-center lg:bottom-5">
      <div
        data-testid="slot-board-problem-banner"
        data-status={problem.status}
        className={`pointer-events-auto flex max-w-[88%] flex-wrap items-center justify-center gap-2 rounded-md border bg-white px-2.5 py-1.5 shadow-sm ${
          isFail
            ? 'border-red-500'
            : 'border-amber-500'
        }`}
      >
        <p className={`text-center text-[13.5px] font-semibold sm:text-[16px] ${isFail ? 'text-red-500' : 'text-amber-500'}`}>{problem.message}</p>
        {onExplain ? <ExplainIssueButton onClick={onExplain} /> : null}
      </div>
    </div>
  );
}

function ExplainIssueButton({ onClick }: { onClick: () => void }) {
  return (
    <button
      type="button"
      data-testid="slot-problem-ai-explain"
      onClick={(event) => {
        event.stopPropagation();
        onClick();
      }}
      className="inline-flex items-center gap-1 rounded border border-blue-200 bg-blue-50 px-2 py-1 text-[10px] font-black text-brand-blue transition hover:border-brand-blue hover:bg-blue-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-200"
    >
      <Sparkles size={11} aria-hidden="true" />
      AI에게 설명
    </button>
  );
}

function isoProblemMarkerPlacement(category: PartCategory) {
  if (category === 'CPU') {
    return { name: 'cpu-left', className: 'absolute left-[-2rem] top-[8%]' };
  }
  return { name: 'default', className: 'absolute -top-1 right-[6%]' };
}

function SlotProblemPopover({
  detail,
  onClose,
  onShowCandidates,
  onExplain
}: {
  detail: SlotProblemDetail;
  onClose: () => void;
  onShowCandidates: () => void;
  onExplain: () => void;
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
      <div className="mt-3 flex flex-wrap justify-end gap-2">
        <ExplainIssueButton onClick={onExplain} />
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
  isAiSpotlighted,
  isAiDimmed,
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
  isAiSpotlighted: boolean;
  isAiDimmed: boolean;
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
      data-ai-spotlight={isAiSpotlighted ? 'true' : 'false'}
      data-ai-dimmed={isAiDimmed ? 'true' : 'false'}
      data-mounted={filled ? 'true' : 'false'}
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
      {isAiSpotlighted && !filled ? (
        <span
          data-testid={`slot-ai-unmounted-${slot.category}`}
          className="pointer-events-none absolute bottom-1 right-1 z-30 rounded border border-blue-200 bg-blue-50/95 px-1.5 py-0.5 text-[9px] font-black text-brand-blue shadow-sm"
        >
          미장착
        </span>
      ) : null}
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
  const categoryByNodeId = new Map<string, PartCategory>();
  const promote = (category: PartCategory | undefined, status: string) => {
    if (!category || !isGraphStatus(status)) {
      return;
    }
    const current = statusMap.get(category);
    if (!current || graphStatusRank(status) > graphStatusRank(current)) {
      statusMap.set(category, status);
    }
  };
  graph?.nodes.forEach((node) => {
    const category = slotCategoryFromGraphCategory(node.category);
    if (category) {
      categoryByNodeId.set(node.id, category);
    }
    if (node.type === 'PART' && category) {
      promote(category, node.status);
    }
  });
  graph?.edges.forEach((edge) => {
    if (!isProblemStatus(edge.status)) {
      return;
    }
    promote(categoryByNodeId.get(edge.source), edge.status);
    promote(categoryByNodeId.get(edge.target), edge.status);
  });
  graph?.insights.forEach((insight) => {
    if (!isProblemStatus(insight.status)) {
      return;
    }
    insight.relatedNodeIds.forEach((nodeId) => promote(categoryByNodeId.get(nodeId), insight.status));
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

function slotBoardProblemBanner(graph?: BuildGraphResolveResponse): SlotBoardBannerProblem | null {
  if (!graph) {
    return null;
  }
  const reasons: SlotProblemReason[] = [];
  const addReason = (status: string, text?: string) => {
    if (!isProblemStatus(status)) {
      return;
    }
    const trimmed = text?.trim();
    if (trimmed) {
      reasons.push({ status, text: trimmed });
    }
  };

  graph.toolResults.forEach((result) => addReason(result.status, result.summary));
  graph.nodes.forEach((node) => addReason(node.status, node.detail));
  graph.edges.forEach((edge) => addReason(edge.status, edge.summary || edge.label));
  graph.insights.forEach((insight) => addReason(insight.status, insight.description || insight.title));

  const status: SlotProblemStatus | null = reasons.some((reason) => reason.status === 'FAIL')
    ? 'FAIL'
    : reasons.some((reason) => reason.status === 'WARN')
      ? 'WARN'
      : null;
  if (!status) {
    return null;
  }

  const message = uniqueProblemReasons(reasons.filter((reason) => reason.status === status))[0]
    ?? (status === 'FAIL' ? '현재 구성에서 장착 불가 항목이 있습니다.' : '현재 구성에서 주의 항목이 있습니다.');
  const matchedTool = graph.toolResults.find((result) => result.status === status)?.tool;
  return { status, message, tool: isBuildGraphTool(matchedTool) ? matchedTool : undefined };
}

function isBuildGraphTool(value: string | undefined): value is NonNullable<BuildGraphFocus['tool']> {
  return value === 'compatibility' || value === 'power' || value === 'size' || value === 'performance' || value === 'price';
}

function toolForCategory(category: PartCategory): BuildGraphFocus['tool'] {
  if (category === 'PSU') return 'power';
  if (category === 'CASE') return 'size';
  if (category === 'CPU' || category === 'GPU' || category === 'RAM' || category === 'STORAGE') return 'performance';
  return 'compatibility';
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

function isGraphStatus(status: string): status is 'PASS' | 'WARN' | 'FAIL' {
  return status === 'PASS' || status === 'WARN' || status === 'FAIL';
}

function graphStatusRank(status: 'PASS' | 'WARN' | 'FAIL') {
  if (status === 'FAIL') return 3;
  if (status === 'WARN') return 2;
  return 1;
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
