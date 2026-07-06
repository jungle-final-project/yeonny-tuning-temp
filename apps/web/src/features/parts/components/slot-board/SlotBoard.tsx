import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import type { BuildGraphResolveResponse, PartCategory } from '../../../quote/aiSelection';
import { partImageUrl, partShortSpec } from '../../partDisplay';
import type { QuoteDraftItem } from '../../types';
import {
  FALLBACK_EDGES,
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
  nextCategory?: PartCategory | null;
  onSlotSelect: (category: PartCategory) => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
  graph?: BuildGraphResolveResponse;
};

export function SlotBoard({ items, selectedCategory, nextCategory, onSlotSelect, onRemoveItem, isRemovePending, graph }: SlotBoardProps) {
  const statusByCategory = partStatusByCategory(graph);
  const slotPositions = useMemo(() => slotPositionsFromGraph(graph), [graph]);
  // 카테고리별 장착 플래시를 보드 수준에서 계산해 카드(꽂힘 모션)와 관계선(draw-in·포트 점등)이 함께 반응한다.
  const flashingCategories = useAttachFlashByCategory(items);
  return (
    <div className="panel overflow-hidden">
      {/* 보드 헤더: 제목 + 호환 상태 범례(초록/노랑/빨강/회색) */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-commerce-line px-4 py-2.5">
        <span className="text-xs font-black text-slate-700">구성 관계도 — 부품 간 호환 상태</span>
        <div className="flex items-center gap-3 text-[10px] font-bold text-slate-500">
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
      {/* 보드 본체 — 메인보드 허브 방사형: 중앙 허브에서 스포크가 뻗고 크로스 관계는 외곽 곡선 */}
      <div
        data-testid="slot-board"
        data-visual-mode="motherboard"
        className="relative flex flex-col gap-2 bg-slate-50/60 p-3 lg:block lg:aspect-[16/10] lg:overflow-hidden lg:bg-[#f8fbff] lg:p-4"
      >
        <SlotBoardEdges
          items={items}
          graph={graph}
          slotPositions={slotPositions}
          selectedCategory={selectedCategory}
          flashingCategories={flashingCategories}
        />
        {SLOT_CONFIGS.map((slot) => (
          <BoardSlot
            key={slot.category}
            slot={slot}
            layout={slotLayoutWithPosition(slot, slotPositions[slot.category])}
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
    </div>
  );
}

function BoardSlot({
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
  // 메인보드는 방사형의 중앙 허브 — 모든 스포크가 모이는 기준점이라 시각적으로 구분한다.
  const isHub = slot.category === 'MOTHERBOARD';
  // "메인보드에 꽂힌다"는 느낌: 장착 순간 카드가 보드 바깥에서 허브 방향으로 밀려 들어온다.
  const outwardX = layout.x + layout.w / 2 - 50;
  const outwardY = layout.y + layout.h / 2 - 50;
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
      ? 'border-2 border-red-500 ring-2 ring-red-50'
      : slotStatus === 'WARN'
        ? 'border-2 border-amber-400 ring-2 ring-amber-50'
        : filled
          ? isHub
            ? 'border-2 border-slate-300 shadow-md hover:border-slate-400'
            : 'border border-emerald-200 hover:border-emerald-400 shadow-sm'
          : isNext
            ? 'border-2 border-brand-blue bg-blue-50/40 hover:border-blue-600'
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
      data-next={isNext ? 'true' : 'false'}
      title={filled ? visibleName : undefined}
      className={`group relative z-20 rounded-lg bg-white/95 p-2 text-left transition backdrop-blur-[1px] lg:absolute lg:left-[var(--sx)] lg:top-[var(--sy)] lg:h-[var(--sh)] lg:w-[var(--sw)] ${borderClass} ${
        isFlashing ? 'slot-attach-flash slot-plug-in' : ''
      } ${isNext && !isSelected ? 'slot-empty-pulse' : ''}`}
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
            <span className="rounded border border-emerald-200 bg-emerald-50 px-1 py-0.5 text-[9px] font-black text-emerald-700">호환 가능</span>
          ) : isNext ? (
            <span className="rounded border border-blue-200 bg-blue-50 px-1 py-0.5 text-[9px] font-black text-brand-blue">다음 선택</span>
          ) : null}
        </div>
        {/* 카드 본체 — 상품명 전문 대신 이미지 중심 + 짧은 요약. 이름은 체크리스트와 hover 툴팁이 담당한다. */}
        {filled ? (
          <>
            <div className="flex min-h-[22px] flex-1 items-center justify-center overflow-hidden">
              <img
                data-testid="slot-part-image"
                src={partImageUrl(primaryItem)}
                alt=""
                aria-hidden="true"
                className="h-full max-h-full max-w-full rounded bg-white object-contain"
              />
            </div>
            {visibleSpec ? (
              <div className="line-clamp-1 text-center text-[10px] font-bold leading-4 text-slate-500">
                {visibleSpec}
              </div>
            ) : null}
            {slot.miniSlots ? (
              <div className="flex items-end justify-start">
                <MiniSlotRow slot={slot} items={items} />
              </div>
            ) : null}
            {!isMultiItemCategory(slot.category) ? (
              <button
                type="button"
                aria-label={`${primaryItem.name} 견적에서 제거`}
                disabled={isRemovePending}
                onClick={() => onRemoveItem(primaryItem.partId)}
                className="pointer-events-auto absolute bottom-1 right-1 rounded border border-commerce-line bg-white px-1.5 py-0.5 text-[9px] font-black text-slate-400 opacity-0 transition group-hover:opacity-100 focus-visible:opacity-100 hover:border-commerce-sale hover:text-commerce-sale disabled:cursor-wait"
              >
                빼기
              </button>
            ) : null}
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
  slotPositions,
  selectedCategory,
  flashingCategories
}: {
  items: QuoteDraftItem[];
  graph?: BuildGraphResolveResponse;
  slotPositions: Partial<Record<PartCategory, SlotBoardPosition>>;
  selectedCategory: PartCategory | null;
  flashingCategories: Set<PartCategory>;
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

  // 추상 기판: 허브 카드보다 살짝 큰 PCB 판을 카드 뒤에 깔고, 스포크는 이 기판 가장자리의
  // 포트 패드에 꽂힌다. 컨테이너 비율(16/10)에서 시각적으로 같은 두께가 되도록 x/y 패딩을 달리 준다.
  const hubSlot = slotConfigFor('MOTHERBOARD');
  const hubBox = hubSlot ? slotLayoutWithPosition(hubSlot, slotPositions.MOTHERBOARD) : null;
  const substrate = hubBox
    ? { x: hubBox.x - HUB_SUBSTRATE_PAD_X, y: hubBox.y - HUB_SUBSTRATE_PAD_Y, w: hubBox.w + HUB_SUBSTRATE_PAD_X * 2, h: hubBox.h + HUB_SUBSTRATE_PAD_Y * 2 }
    : null;

  return (
    <div data-testid="slot-board-edges" aria-hidden="true" className="pointer-events-none absolute inset-0 z-10 hidden lg:block">
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="h-full w-full">
        {substrate ? (
          <g data-testid="slot-board-hub-substrate">
            <rect
              x={substrate.x}
              y={substrate.y}
              width={substrate.w}
              height={substrate.h}
              rx={1.6}
              fill="#eef2f7"
              stroke="#cbd5e1"
              strokeWidth={1.5}
              vectorEffect="non-scaling-stroke"
            />
            {/* 기판 모서리 나사홀 — 추상 표현 최소치 */}
            {[
              { x: substrate.x + 1.4, y: substrate.y + 2 },
              { x: substrate.x + substrate.w - 1.4, y: substrate.y + 2 },
              { x: substrate.x + 1.4, y: substrate.y + substrate.h - 2 },
              { x: substrate.x + substrate.w - 1.4, y: substrate.y + substrate.h - 2 }
            ].map((hole, index) => (
              <circle key={index} cx={hole.x} cy={hole.y} r={0.5} fill="#ffffff" stroke="#cbd5e1" vectorEffect="non-scaling-stroke" />
            ))}
          </g>
        ) : null}
        {edges.map((edge) => {
          const { path, start, end } = edgeGeometry(edge.config, slotPositions);
          const style = EDGE_STROKES[edge.status];
          const isHighlighted = selectedCategory === edge.config.from || selectedCategory === edge.config.to;
          const isSpoke = edge.config.from === 'MOTHERBOARD' || edge.config.to === 'MOTHERBOARD';
          // 장착 순간 관련 관계선이 부품→포트 방향으로 그려지고(draw-in) 포트가 점등된다.
          const isDrawing = flashingCategories.has(edge.config.from) || flashingCategories.has(edge.config.to);
          // 스포크의 허브 쪽 끝은 "꽂히는 포트" 패드로 그린다 — 메인보드에 장착된다는 시각 언어.
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
        const { label } = edgeGeometry(edge.config, slotPositions);
        const isHighlighted = selectedCategory === edge.config.from || selectedCategory === edge.config.to;
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

type Box = { x: number; y: number; w: number; h: number };
type Point = { x: number; y: number };

const BOARD_CENTER: Point = { x: 50, y: 50 };
// 추상 기판이 허브 카드보다 넓게 깔리는 패딩(%). 16/10 컨테이너에서 시각적으로 균일하도록 y를 크게 잡는다.
const HUB_SUBSTRATE_PAD_X = 2.4;
const HUB_SUBSTRATE_PAD_Y = 3.8;

// 스포크 앵커 계산용 — 허브(메인보드)는 기판 크기로 팽창시켜 포트 패드가 기판 가장자리에 앉게 한다.
function inflateHubBox(box: Box): Box {
  return {
    x: box.x - HUB_SUBSTRATE_PAD_X,
    y: box.y - HUB_SUBSTRATE_PAD_Y,
    w: box.w + HUB_SUBSTRATE_PAD_X * 2,
    h: box.h + HUB_SUBSTRATE_PAD_Y * 2
  };
}

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

// 허브 방사형 지오메트리: 허브(메인보드) 스포크는 중심을 향한 직선, 크로스 관계는
// 설정된 곡률(bow)만큼 보드 바깥(+)/안쪽(-)으로 휘는 곡선. 라벨은 실제 선의 중앙에 둔다.
function edgeGeometry(config: SlotEdgeConfig, slotPositions: Partial<Record<PartCategory, SlotBoardPosition>>) {
  const fromSlot = slotConfigFor(config.from);
  const toSlot = slotConfigFor(config.to);
  let a: Box = fromSlot ? slotLayoutWithPosition(fromSlot, slotPositions[config.from]) : { x: 0, y: 0, w: 0, h: 0 };
  let b: Box = toSlot ? slotLayoutWithPosition(toSlot, slotPositions[config.to]) : { x: 0, y: 0, w: 0, h: 0 };
  if (config.from === 'MOTHERBOARD') {
    a = inflateHubBox(a);
  }
  if (config.to === 'MOTHERBOARD') {
    b = inflateHubBox(b);
  }
  const ac = boxCenter(a);
  const bc = boxCenter(b);
  const isSpoke = config.from === 'MOTHERBOARD' || config.to === 'MOTHERBOARD';

  const labelT = config.labelT ?? 0.5;
  if (isSpoke || !config.bow) {
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
  const outward = { x: mid.x - BOARD_CENTER.x, y: mid.y - BOARD_CENTER.y };
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
