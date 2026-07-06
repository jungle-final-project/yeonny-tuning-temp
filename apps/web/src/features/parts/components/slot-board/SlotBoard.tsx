import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import type { BuildGraphResolveResponse, PartCategory } from '../../../quote/aiSelection';
import { partShortSpec } from '../../partDisplay';
import type { QuoteDraftItem } from '../../types';
import {
  FALLBACK_EDGES,
  SLOT_BOARD_ART_VIEWBOX,
  SLOT_CONFIGS,
  isMultiItemCategory,
  slotConfigFor,
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
      {/* 보드 본체 — 실장도: 추상 메인보드 평면도의 실장 지점(소켓/DIMM/PCIe/M.2)에 부품이 꽂히고,
          보드에 안 꽂히는 부품(파워/쿨러/케이스)은 우측 도킹 베이에 놓인다 */}
      <div
        data-testid="slot-board"
        data-visual-mode="motherboard"
        className="relative flex flex-col gap-2 bg-slate-50/60 p-3 lg:block lg:aspect-[16/10] lg:overflow-hidden lg:bg-[#fbfdff] lg:p-0"
      >
        <BoardPlanArt />
        <SlotBoardEdges
          items={items}
          graph={graph}
          selectedCategory={selectedCategory}
          flashingCategories={flashingCategories}
        />
        {SLOT_CONFIGS.map((slot) => (
          <BoardSlot
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
    </div>
  );
}

// 추상 메인보드 평면도 (통일 에셋 스타일: 기판 #f4f7fb / 부품 #e2e8f0 / 선 #cbd5e1).
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
      {/* 기판 + 나사홀 */}
      <rect x="3" y="3" width="104" height="94" rx="3" fill="#f4f7fb" stroke="#d3dce6" strokeWidth="0.7" />
      {[[7, 7], [55, 7], [103, 7], [7, 93], [55, 93], [103, 93]].map(([x, y], index) => (
        <circle key={index} cx={x} cy={y} r="1" fill="#ffffff" stroke="#cbd5e1" strokeWidth="0.4" />
      ))}
      {/* 좌측 IO 포트 블록 */}
      {[12, 22, 32].map((y) => (
        <rect key={y} x="5.5" y={y} width="6.5" height="7" rx="1" fill="#e8edf4" stroke="#cbd5e1" strokeWidth="0.5" />
      ))}
      {/* CPU 소켓 (핫스팟: 30..58 × 16..44) */}
      <rect x="30" y="16" width="28" height="28" rx="1.5" fill="#eef2f7" stroke="#cbd5e1" strokeWidth="0.8" />
      <rect x="35" y="21" width="18" height="18" rx="1" fill="#f8fafc" stroke="#cbd5e1" strokeWidth="0.6" />
      {[[31.5, 17.5], [56.5, 17.5], [31.5, 42.5], [56.5, 42.5]].map(([x, y], index) => (
        <circle key={index} cx={x} cy={y} r="0.8" fill="#cbd5e1" />
      ))}
      {/* DIMM 4슬롯 (핫스팟: 64..90 × 8..50) */}
      {[65.5, 72, 78.5, 85].map((x) => (
        <g key={x}>
          <rect x={x} y="10" width="4.4" height="38" rx="0.8" fill="#f8fafc" stroke="#cbd5e1" strokeWidth="0.7" />
          <line x1={x + 2.2} y1="12.5" x2={x + 2.2} y2="45.5" stroke="#e2e8f0" strokeWidth="1.4" />
          <rect x={x + 0.6} y="8.6" width="3.2" height="1.6" rx="0.4" fill="#e2e8f0" />
          <rect x={x + 0.6} y="47.8" width="3.2" height="1.6" rx="0.4" fill="#e2e8f0" />
        </g>
      ))}
      {/* PCIe x16 (핫스팟: 10..78 × 54..70) + 보조 x4 */}
      <rect x="11" y="58" width="65" height="4.5" rx="0.8" fill="#f8fafc" stroke="#cbd5e1" strokeWidth="0.7" />
      <line x1="24" y1="58.6" x2="24" y2="61.9" stroke="#cbd5e1" strokeWidth="0.6" />
      <rect x="11" y="66.5" width="30" height="3.4" rx="0.8" fill="#f8fafc" stroke="#cbd5e1" strokeWidth="0.6" />
      {/* M.2 (핫스팟: 82..106 × 72..84) */}
      <rect x="83" y="75.5" width="21" height="5" rx="0.8" fill="#f8fafc" stroke="#cbd5e1" strokeWidth="0.7" />
      <circle cx="102" cy="78" r="1" fill="#ffffff" stroke="#cbd5e1" strokeWidth="0.5" />
      {/* 24핀 전원 커넥터 */}
      <rect x="99" y="15" width="6" height="18" rx="1" fill="#eef2f7" stroke="#cbd5e1" strokeWidth="0.7" />
      <line x1="102" y1="16.5" x2="102" y2="31.5" stroke="#cbd5e1" strokeWidth="0.5" />
      {/* 칩셋 방열판 */}
      <rect x="62" y="74" width="14" height="12" rx="1.2" fill="#e8edf4" stroke="#cbd5e1" strokeWidth="0.7" />
      <rect x="64.5" y="76.5" width="9" height="7" rx="0.8" fill="none" stroke="#cbd5e1" strokeWidth="0.5" />
      {/* 메인보드 명판 (핫스팟: 6..42 × 84..95) */}
      <rect x="6" y="84.5" width="36" height="10.5" rx="1.2" fill="#f8fafc" stroke="#cbd5e1" strokeWidth="0.7" strokeDasharray="1.6 1.2" />
    </svg>
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
  // 보드 위 실장 지점은 비어 있을 때 카드 대신 평면도의 소켓/슬롯 그림이 빈 상태를 말한다(데스크톱).
  const isBoardMount = slot.mount === 'board';
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
          ? 'border border-emerald-200 hover:border-emerald-400 shadow-sm'
          : isNext
            ? `border-2 border-brand-blue bg-blue-50/40 hover:border-blue-600${isBoardMount ? ' lg:bg-blue-50/25' : ''}`
            : isBoardMount
              // 데스크톱 실장 지점: 아트의 빈 소켓/슬롯이 보이도록 투명 — hover에만 윤곽을 드러낸다.
              ? 'border border-dashed border-slate-300 bg-white/75 hover:border-brand-blue lg:border-transparent lg:bg-transparent lg:hover:border-brand-blue/60 lg:hover:bg-blue-50/20'
              : 'border border-dashed border-slate-300 bg-white/75 hover:border-brand-blue';
  const surfaceClass = !filled && isBoardMount ? 'bg-white/95 lg:bg-transparent lg:backdrop-blur-0' : 'bg-white/95';
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
      } ${isNext && !isSelected ? 'slot-empty-pulse' : ''}`}
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
          <span className={`flex items-center gap-1 text-[10px] font-black text-slate-600 ${filled ? 'rounded bg-white/85 px-1 py-0.5' : ''}`}>
            {!filled ? <img src={slot.glyph} alt="" aria-hidden="true" className="h-4 w-4 shrink-0 opacity-35" /> : null}
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
  flashingCategories
}: {
  items: QuoteDraftItem[];
  graph?: BuildGraphResolveResponse;
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

  return (
    <div data-testid="slot-board-edges" aria-hidden="true" className="pointer-events-none absolute inset-0 z-10 hidden lg:block">
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="h-full w-full">
        {edges.map((edge) => {
          const { path, start, end } = edgeGeometry(edge.config);
          if (!path) {
            // 실장 관계(implied)는 꽂혀 있는 그림이 관계 자체다 — 상태 표시는 라벨 레이어가 담당한다.
            return null;
          }
          const style = EDGE_STROKES[edge.status];
          const isHighlighted = selectedCategory === edge.config.from || selectedCategory === edge.config.to;
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
        const { label } = edgeGeometry(edge.config);
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
// 물리적 의미가 있는 고정 접점 — 파워 24핀 커넥터(평면도 아트의 커넥터 위치와 동일 상수 계보).
const EDGE_POINT_OVERRIDES: Record<string, Point> = {
  'PSU-MOTHERBOARD': { x: 65.6, y: 24 }
};

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
function edgeGeometry(config: SlotEdgeConfig) {
  const fromSlot = slotConfigFor(config.from);
  const toSlot = slotConfigFor(config.to);
  const a: Box = fromSlot ? fromSlot.layout : { x: 0, y: 0, w: 0, h: 0 };
  const b: Box = toSlot ? toSlot.layout : { x: 0, y: 0, w: 0, h: 0 };
  const ac = boxCenter(a);
  const bc = boxCenter(b);

  if (config.implied) {
    // 실장 관계: 부품 쪽 실장 지점의 안쪽(보드 중앙 방향) 가장자리에 상태 점/문제 라벨을 붙인다.
    const partBox = config.from === 'MOTHERBOARD' ? b : a;
    const anchor = boxAnchorToward(partBox, BOARD_CENTER);
    const center = boxCenter(partBox);
    const direction = { x: anchor.x - center.x, y: anchor.y - center.y };
    const length = Math.hypot(direction.x, direction.y) || 1;
    const point = { x: anchor.x + (direction.x / length) * 1.8, y: anchor.y + (direction.y / length) * 1.8 };
    return { path: '', start: point, end: point, label: point };
  }

  const override = EDGE_POINT_OVERRIDES[`${config.from}-${config.to}`] ?? EDGE_POINT_OVERRIDES[`${config.to}-${config.from}`];
  const labelT = config.labelT ?? 0.5;
  if (override) {
    // 고정 접점(24핀 등)으로 향하는 직선 — 접점이 곧 포트 패드 위치다.
    const start = boxAnchorToward(a, override);
    const end = override;
    return {
      path: `M ${start.x} ${start.y} L ${end.x} ${end.y}`,
      start,
      end,
      label: { x: start.x + (end.x - start.x) * labelT, y: start.y + (end.y - start.y) * labelT }
    };
  }
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
