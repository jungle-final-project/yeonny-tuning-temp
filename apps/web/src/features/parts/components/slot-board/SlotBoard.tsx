import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import type { BuildGraphResolveResponse, PartCategory } from '../../../quote/aiSelection';
import { partImageUrl, partShortSpec } from '../../partDisplay';
import type { QuoteDraftItem } from '../../types';
import {
  FALLBACK_EDGES,
  SLOT_BOARD_BG,
  SLOT_CONFIGS,
  isMultiItemCategory,
  isSlotBoardPercentPosition,
  isSlotCategory,
  slotConfigFor,
  slotLayoutWithPosition,
  type SlotBoardPosition,
  type SlotConfig,
  type SlotEdgeConfig
} from './slotBoardConfig';

type SlotBoardProps = {
  items: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  onSlotSelect: (category: PartCategory) => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
  graph?: BuildGraphResolveResponse;
};

export function SlotBoard({ items, selectedCategory, onSlotSelect, onRemoveItem, isRemovePending, graph }: SlotBoardProps) {
  const statusByCategory = partStatusByCategory(graph);
  const slotPositions = useMemo(() => slotPositionsFromGraph(graph), [graph]);
  return (
    <div className="panel overflow-hidden">
      {/* 보드 헤더: 제목 + 범례 */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-commerce-line px-4 py-2.5">
        <span className="text-xs font-black text-slate-700">메인보드 구성도 (의존성 그래프)</span>
        <div className="flex items-center gap-3 text-[10px] font-bold text-slate-500">
          <span className="flex items-center gap-1.5">
            <svg width="20" height="4" viewBox="0 0 20 4" aria-hidden="true"><line x1="0" y1="2" x2="20" y2="2" stroke="#0d9488" strokeWidth="2" /></svg>
            연결됨
          </span>
          <span className="flex items-center gap-1.5">
            <svg width="20" height="4" viewBox="0 0 20 4" aria-hidden="true"><line x1="0" y1="2" x2="20" y2="2" stroke="#94a3b8" strokeWidth="2" strokeDasharray="4 3" /></svg>
            비연결
          </span>
          <span className="flex items-center gap-1.5">
            <span className="inline-block h-3 w-3 rounded-full border border-amber-300 bg-amber-50 text-center text-[8px] font-black leading-3 text-amber-700">!</span>
            간섭 주의
          </span>
        </div>
      </div>
      {/* 보드 본체 */}
      <div
        data-testid="slot-board"
        data-visual-mode="motherboard"
        className="relative flex flex-col gap-2 bg-slate-50/60 p-3 lg:block lg:aspect-[16/8.4] lg:overflow-hidden lg:bg-[#f8fbff] lg:p-4"
        style={{ ['--slot-board-bg' as string]: `url(${SLOT_BOARD_BG})` }}
      >
        <div
          data-testid="slot-board-motherboard-art"
          aria-hidden="true"
          className="pointer-events-none absolute inset-2 hidden rounded-lg bg-[url('/slot-board/backgrounds/topology-board-bg.svg')] bg-contain bg-center bg-no-repeat opacity-[0.82] lg:block"
        />
        <BoardHardwareLabels />
        <SlotBoardEdges items={items} graph={graph} slotPositions={slotPositions} selectedCategory={selectedCategory} />
        {SLOT_CONFIGS.map((slot) => (
          <BoardSlot
            key={slot.category}
            slot={slot}
            layout={slotLayoutWithPosition(slot, slotPositions[slot.category])}
            items={items.filter((item) => item.category === slot.category)}
            problemStatus={statusByCategory.get(slot.category)}
            isSelected={selectedCategory === slot.category}
            onSelect={() => onSlotSelect(slot.category)}
            onRemoveItem={onRemoveItem}
            isRemovePending={isRemovePending}
          />
        ))}
      </div>
    </div>
  );
}

function BoardSlot({
  slot,
  layout,
  items,
  problemStatus,
  isSelected,
  onSelect,
  onRemoveItem,
  isRemovePending
}: {
  slot: SlotConfig;
  layout: SlotConfig['layout'];
  items: QuoteDraftItem[];
  problemStatus?: 'PASS' | 'WARN' | 'FAIL';
  isSelected: boolean;
  onSelect: () => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
}) {
  const filled = items.length > 0;
  const primaryItem = items[0];
  const lineTotal = items.reduce((sum, item) => sum + item.lineTotal, 0);
  // 문제 상태는 장착된 슬롯에만 표시한다. 숨기지 않고 강조한다.
  const slotStatus = filled ? problemStatus ?? 'PASS' : 'NONE';
  const isFlashing = useAttachFlash(items);
  const layoutVars: CSSProperties = {
    ['--sx' as string]: `${layout.x}%`,
    ['--sy' as string]: `${layout.y}%`,
    ['--sw' as string]: `${layout.w}%`,
    ['--sh' as string]: `${layout.h}%`
  };
  const borderClass = isSelected
    ? 'border-2 border-brand-blue ring-2 ring-blue-100 shadow-lg'
    : slotStatus === 'FAIL'
      ? 'border-2 border-red-500 ring-2 ring-red-50'
      : slotStatus === 'WARN'
        ? 'border-2 border-amber-400 ring-2 ring-amber-50'
        : filled
          ? 'border border-slate-200 hover:border-slate-400 shadow-sm'
          : 'border border-dashed border-slate-300 bg-white/75 hover:border-brand-blue';
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
      className={`group relative z-20 rounded-lg bg-white/95 p-2 text-left transition backdrop-blur-[1px] lg:absolute lg:left-[var(--sx)] lg:top-[var(--sy)] lg:h-[var(--sh)] lg:w-[var(--sw)] ${borderClass} ${
        isFlashing ? 'slot-attach-flash' : ''
      } ${!filled && !isSelected ? 'slot-empty-pulse' : ''}`}
    >
      <button
        type="button"
        aria-label={`${slot.label} 슬롯 열기`}
        aria-pressed={isSelected}
        onClick={onSelect}
        className="absolute inset-0 z-0 h-full w-full rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue"
      />
      <div className="pointer-events-none relative z-10 flex h-full flex-col gap-1 overflow-hidden">
        {/* 카드 헤더: 아이콘 + 카테고리명 + 상태 배지 */}
        <div className="flex items-center justify-between gap-1">
          <span className="flex items-center gap-1 text-[10px] font-black text-slate-600">
            <img src={slot.glyph} alt="" aria-hidden="true" className={`h-4 w-4 shrink-0 ${filled ? 'opacity-70' : 'opacity-35'}`} />
            {slot.label}
          </span>
          {slotStatus === 'FAIL' ? (
            <span className="rounded border border-red-200 bg-red-50 px-1 py-0.5 text-[9px] font-black text-red-700">안 맞음</span>
          ) : slotStatus === 'WARN' ? (
            <span className="rounded border border-amber-200 bg-amber-50 px-1 py-0.5 text-[9px] font-black text-amber-700">간섭 주의</span>
          ) : filled ? (
            <span className="rounded border border-emerald-200 bg-emerald-50 px-1 py-0.5 text-[9px] font-black text-emerald-700">정상</span>
          ) : null}
        </div>
        {/* 카드 본체 */}
        {filled ? (
          <>
            <div className="flex min-h-0 flex-1 gap-2 overflow-hidden">
              <img
                data-testid="slot-part-image"
                src={partImageUrl(primaryItem)}
                alt=""
                aria-hidden="true"
                className="hidden h-12 w-14 shrink-0 rounded border border-slate-200 bg-slate-50 object-contain lg:block"
              />
              <div className="min-w-0 flex-1">
                <div className="line-clamp-2 text-[11px] font-black leading-[1.32] text-commerce-ink">
                  {visibleName}
                </div>
                {visibleSpec ? (
                  <div className="mt-0.5 line-clamp-1 text-[10px] font-bold leading-4 text-slate-500">
                    {visibleSpec}
                  </div>
                ) : null}
              </div>
            </div>
            <div className="mt-auto flex items-end justify-between gap-1">
              <span className="text-[11px] font-black text-brand-blue">{lineTotal.toLocaleString()}원</span>
              <div className="flex items-center gap-1">
                {slot.miniSlots ? <MiniSlotRow slot={slot} items={items} /> : null}
                {!isMultiItemCategory(slot.category) ? (
                  <button
                    type="button"
                    aria-label={`${primaryItem.name} 견적에서 제거`}
                    disabled={isRemovePending}
                    onClick={() => onRemoveItem(primaryItem.partId)}
                    className="pointer-events-auto rounded border border-commerce-line bg-white px-1.5 py-0.5 text-[9px] font-black text-slate-400 opacity-0 transition group-hover:opacity-100 focus-visible:opacity-100 hover:border-commerce-sale hover:text-commerce-sale disabled:cursor-wait"
                  >
                    빼기
                  </button>
                ) : null}
              </div>
            </div>
          </>
        ) : (
          <div className="flex flex-1 items-center justify-start gap-1">
            <span className="text-[11px] font-black text-brand-blue">+ 부품 선택</span>
            {slot.miniSlots ? <MiniSlotRow slot={slot} items={items} /> : null}
          </div>
        )}
      </div>
    </div>
  );
}

function BoardHardwareLabels() {
  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 z-10 hidden text-[11px] font-black text-slate-700 lg:block">
      <div className="absolute left-[28%] top-[54%]">
        <div className="h-2 w-64 rounded-full border border-slate-300 bg-white/70 shadow-sm" />
        <div className="mt-1 text-center leading-4">
          PCIe x16 슬롯
          <div className="text-[10px] font-bold text-slate-500">(메인보드)</div>
        </div>
      </div>
      <div className="absolute left-[31%] top-[74%] leading-4">
        24핀 전원
        <div className="text-[10px] font-bold text-slate-500">(메인보드)</div>
      </div>
    </div>
  );
}

// 장착/교체로 슬롯 구성이 바뀌면 잠깐 flash 상태를 켠다. 애니메이션 자체는 CSS가 담당하고
// prefers-reduced-motion에서는 CSS에서 꺼진다.
function useAttachFlash(items: QuoteDraftItem[]) {
  const signature = items.map((item) => `${item.partId}:${item.quantity}`).join('|');
  const previousSignature = useRef<string | null>(null);
  const [isFlashing, setIsFlashing] = useState(false);

  useEffect(() => {
    const previous = previousSignature.current;
    previousSignature.current = signature;
    if (previous === null || previous === signature || signature === '') {
      return;
    }
    setIsFlashing(true);
    const timer = window.setTimeout(() => setIsFlashing(false), 900);
    return () => window.clearTimeout(timer);
  }, [signature]);

  return isFlashing;
}

type SlotEdgeStatus = 'PASS' | 'WARN' | 'FAIL' | 'PENDING' | 'BASE';

type ResolvedSlotEdge = {
  config: SlotEdgeConfig;
  status: SlotEdgeStatus;
  label: string;
  summary?: string;
};

const EDGE_STROKES: Record<SlotEdgeStatus, { stroke: string; dash?: string }> = {
  PASS: { stroke: '#0d9488' },
  WARN: { stroke: '#d97706' },
  FAIL: { stroke: '#ef4444', dash: '7 5' },
  PENDING: { stroke: '#94a3b8', dash: '4 4' },
  BASE: { stroke: '#0d9488' }
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
  slotPositions,
  selectedCategory
}: {
  items: QuoteDraftItem[];
  graph?: BuildGraphResolveResponse;
  slotPositions: Partial<Record<PartCategory, SlotBoardPosition>>;
  selectedCategory: PartCategory | null;
}) {
  const filledCategories = new Set(items.map((item) => item.category));
  const edges: ResolvedSlotEdge[] = FALLBACK_EDGES.map((config) => {
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
          const { path, start, end } = edgeGeometry(edge.config, slotPositions);
          const style = EDGE_STROKES[edge.status];
          const isHighlighted = selectedCategory === edge.config.from || selectedCategory === edge.config.to;
          return (
            <g key={`${edge.config.from}-${edge.config.to}`}>
              <path
                d={path}
                fill="none"
                stroke={style.stroke}
                strokeWidth={isHighlighted ? 3.5 : 2}
                strokeDasharray={style.dash}
                strokeOpacity={isHighlighted ? 1 : 0.6}
                strokeLinecap="round"
                strokeLinejoin="round"
                vectorEffect="non-scaling-stroke"
              />
              <circle cx={start.x} cy={start.y} r={0.7} fill={style.stroke} fillOpacity={isHighlighted ? 1 : 0.6} />
              <circle cx={end.x} cy={end.y} r={0.7} fill={style.stroke} fillOpacity={isHighlighted ? 1 : 0.6} />
            </g>
          );
        })}
      </svg>
      {edges.map((edge) => {
        const { label } = edgeGeometry(edge.config, slotPositions);
        const isHighlighted = selectedCategory === edge.config.from || selectedCategory === edge.config.to;
        return (
          <span
            key={`label-${edge.config.from}-${edge.config.to}`}
            data-testid={`slot-edge-${edge.config.from}-${edge.config.to}`}
            data-status={edge.status}
            title={edge.summary}
            style={{ left: `${label.x}%`, top: `${label.y}%`, opacity: isHighlighted ? 1 : 0.8 }}
            className={`absolute -translate-x-1/2 -translate-y-1/2 whitespace-nowrap rounded-full border px-1.5 py-0.5 text-[9px] font-black shadow-sm ${EDGE_LABEL_CLASSES[edge.status]}`}
          >
            {edge.label}
          </span>
        );
      })}
    </div>
  );
}

type Box = { x: number; y: number; w: number; h: number };

// 두 슬롯을 잇는 직각(ㄱ자) 경로를 계산한다. 카드 가장자리에서 앵커를 시작해
// 수평/수직으로만 꺾이게 하고, 라벨은 가장 긴 구간 중앙에 둔다.
function edgeGeometry(config: SlotEdgeConfig, slotPositions: Partial<Record<PartCategory, SlotBoardPosition>>) {
  const fromSlot = slotConfigFor(config.from);
  const toSlot = slotConfigFor(config.to);
  const a: Box = fromSlot ? slotLayoutWithPosition(fromSlot, slotPositions[config.from]) : { x: 0, y: 0, w: 0, h: 0 };
  const b: Box = toSlot ? slotLayoutWithPosition(toSlot, slotPositions[config.to]) : { x: 0, y: 0, w: 0, h: 0 };
  const ac = { x: a.x + a.w / 2, y: a.y + a.h / 2 };
  const bc = { x: b.x + b.w / 2, y: b.y + b.h / 2 };

  const dx = bc.x - ac.x;
  const dy = bc.y - ac.y;
  const overlapX = Math.abs(dx) < (a.w + b.w) / 4; // 두 카드가 가로로 거의 정렬됨
  const overlapY = Math.abs(dy) < (a.h + b.h) / 4;

  let start: { x: number; y: number };
  let end: { x: number; y: number };
  let corner: { x: number; y: number };

  if (overlapX && !overlapY) {
    // 세로로 나열 → 위/아래 가장자리를 잇는 직선(수직)
    start = { x: ac.x, y: dy > 0 ? a.y + a.h : a.y };
    end = { x: bc.x, y: dy > 0 ? b.y : b.y + b.h };
    corner = { x: (start.x + end.x) / 2, y: (start.y + end.y) / 2 };
  } else if (overlapY && !overlapX) {
    // 가로로 나열 → 좌/우 가장자리를 잇는 직선(수평)
    start = { x: dx > 0 ? a.x + a.w : a.x, y: ac.y };
    end = { x: dx > 0 ? b.x : b.x + b.w, y: bc.y };
    corner = { x: (start.x + end.x) / 2, y: (start.y + end.y) / 2 };
  } else {
    // 대각 배치 → 수평으로 나갔다가 수직으로 꺾어 도착(L자)
    start = { x: dx > 0 ? a.x + a.w : a.x, y: ac.y };
    end = { x: bc.x, y: dy > 0 ? b.y : b.y + b.h };
    corner = { x: end.x, y: start.y };
  }

  const path = `M ${start.x} ${start.y} L ${corner.x} ${corner.y} L ${end.x} ${end.y}`;
  // 라벨은 시작-코너 구간과 코너-끝 구간 중 더 긴 쪽 중앙에 둔다.
  const seg1 = Math.hypot(corner.x - start.x, corner.y - start.y);
  const seg2 = Math.hypot(end.x - corner.x, end.y - corner.y);
  const label = seg1 >= seg2
    ? { x: (start.x + corner.x) / 2, y: (start.y + corner.y) / 2 }
    : { x: (corner.x + end.x) / 2, y: (corner.y + end.y) / 2 };

  return { path, start, end, label };
}

function slotPositionsFromGraph(graph?: BuildGraphResolveResponse) {
  const positions: Partial<Record<PartCategory, SlotBoardPosition>> = {};
  graph?.nodes.forEach((node) => {
    const category = typeof node.category === 'string' ? node.category : null;
    if (node.type === 'PART' && isSlotCategory(category) && isSlotBoardPercentPosition(category, node.position)) {
      positions[category] = node.position;
    }
  });
  return positions;
}

function partStatusByCategory(graph?: BuildGraphResolveResponse) {
  const statusMap = new Map<string, 'PASS' | 'WARN' | 'FAIL'>();
  graph?.nodes.forEach((node) => {
    if (node.type === 'PART' && node.category) {
      statusMap.set(node.category, node.status);
    }
  });
  return statusMap;
}

function findGraphEdge(graph: BuildGraphResolveResponse | undefined, from: PartCategory, to: PartCategory) {
  if (!graph) {
    return undefined;
  }
  const categoryByNodeId = new Map(graph.nodes.map((node) => [node.id, node.category]));
  return graph.edges.find((edge) => {
    const sourceCategory = categoryByNodeId.get(edge.source);
    const targetCategory = categoryByNodeId.get(edge.target);
    return (sourceCategory === from && targetCategory === to) || (sourceCategory === to && targetCategory === from);
  });
}

function MiniSlotRow({ slot, items }: { slot: SlotConfig; items: QuoteDraftItem[] }) {
  const total = slot.miniSlots ?? 0;
  const fillCount = slot.miniFillBy === 'quantity'
    ? items.reduce((sum, item) => sum + item.quantity, 0)
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
