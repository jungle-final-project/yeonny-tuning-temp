import { useEffect, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent as ReactKeyboardEvent, type PointerEvent as ReactPointerEvent } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { LayoutGrid, Move, RotateCcw, Save } from 'lucide-react';
import { AdminShell, StateMessage } from '../../../components/ui';
import {
  RECOMMENDED_SLOT_ORDER,
  SLOT_BOARD_BG,
  SLOT_BOARD_ISO_CALLOUT_LAYOUTS,
  SLOT_BOARD_ISO_SCENE,
  SLOT_CONFIGS,
  SLOT_ISO_ART,
  clampSlotBoardPosition,
  defaultSlotBoardPositions,
  mergeSlotBoardPositions,
  slotConfigFor,
  slotLayoutWithPosition,
  type SlotBoardPosition,
  type SlotConfig
} from '../../parts/components/slot-board/slotBoardConfig';
import {
  getDefaultBuildGraphLayout,
  resetDefaultBuildGraphLayout,
  saveDefaultBuildGraphLayout,
  type BuildGraphAnchor
} from '../adminApi';
import type { PartCategory } from '../../quote/aiSelection';

type SaveState = 'idle' | 'dirty' | 'saved' | 'reset';

type DragState = {
  category: PartCategory;
  pointerOffsetX: number;
  pointerOffsetY: number;
};

type AnchorPoint = { x: number; y: number };
type AnchorPair = { card: AnchorPoint; part: AnchorPoint };
type AnchorKind = 'card' | 'part';

type AnchorDragState = {
  category: PartCategory;
  kind: AnchorKind;
  pointerOffsetX: number;
  pointerOffsetY: number;
};

function clamp01(value: number): number {
  return Math.round(Math.min(100, Math.max(0, value)));
}

// 3D 커넥터 앵커 기본값 — self-quote IsoCardConnector의 현재 자동 계산과 동일한 시드로 시작한다.
function defaultAnchors(): Record<PartCategory, AnchorPair> {
  const result = {} as Record<PartCategory, AnchorPair>;
  for (const category of RECOMMENDED_SLOT_ORDER) {
    const iso = SLOT_ISO_ART[category];
    const card = SLOT_BOARD_ISO_CALLOUT_LAYOUTS[category];
    result[category] = {
      card: { x: clamp01(card.x + card.w / 2), y: clamp01(card.y + card.h / 2) },
      part: { x: clamp01(iso.x + iso.w / 2), y: clamp01(iso.y + iso.w * 0.35) }
    };
  }
  return result;
}

function mergeAnchors(fetched: Record<string, BuildGraphAnchor> | undefined): Record<PartCategory, AnchorPair> {
  const merged = defaultAnchors();
  if (!fetched) {
    return merged;
  }
  for (const category of RECOMMENDED_SLOT_ORDER) {
    const anchor = fetched[category];
    if (!anchor) {
      continue;
    }
    if (isFiniteAnchorPoint(anchor.card) && isFiniteAnchorPoint(anchor.part)) {
      merged[category] = {
        card: { x: clamp01(anchor.card.x), y: clamp01(anchor.card.y) },
        part: { x: clamp01(anchor.part.x), y: clamp01(anchor.part.y) }
      };
    }
  }
  return merged;
}

function isFiniteAnchorPoint(point: AnchorPoint | undefined): point is AnchorPoint {
  return Boolean(point) && Number.isFinite(point?.x) && Number.isFinite(point?.y);
}

export function AdminBuildGraphLayoutsPage() {
  const boardRef = useRef<HTMLDivElement | null>(null);
  const dragRef = useRef<DragState | null>(null);
  const anchorBoardRef = useRef<HTMLDivElement | null>(null);
  const anchorDragRef = useRef<AnchorDragState | null>(null);
  const [positions, setPositions] = useState<Record<PartCategory, SlotBoardPosition>>(() => defaultSlotBoardPositions());
  const [anchors, setAnchors] = useState<Record<PartCategory, AnchorPair>>(() => defaultAnchors());
  const [saveState, setSaveState] = useState<SaveState>('idle');
  const [draggingCategory, setDraggingCategory] = useState<PartCategory | null>(null);
  const [selectedAnchorCategory, setSelectedAnchorCategory] = useState<PartCategory>('CPU');
  const [draggingAnchor, setDraggingAnchor] = useState<{ category: PartCategory; kind: AnchorKind } | null>(null);

  const layoutQuery = useQuery({
    queryKey: ['admin-build-graph-layout-default'],
    queryFn: getDefaultBuildGraphLayout
  });
  const saveMutation = useMutation({
    mutationFn: () => saveDefaultBuildGraphLayout(positions, anchors),
    onSuccess: (layout) => {
      setPositions(mergeSlotBoardPositions(layout.positions));
      setAnchors(mergeAnchors(layout.anchors));
      setSaveState('saved');
    }
  });
  const resetMutation = useMutation({
    mutationFn: resetDefaultBuildGraphLayout,
    onSuccess: (layout) => {
      setPositions(mergeSlotBoardPositions(layout.positions));
      setAnchors(mergeAnchors(layout.anchors));
      setSaveState('reset');
    }
  });

  useEffect(() => {
    if (layoutQuery.data) {
      setPositions(mergeSlotBoardPositions(layoutQuery.data.positions));
      setAnchors(mergeAnchors(layoutQuery.data.anchors));
      setSaveState('idle');
    }
  }, [layoutQuery.data]);

  useEffect(() => {
    if (!draggingCategory) {
      return;
    }

    const previousUserSelect = document.body.style.userSelect;
    document.body.style.userSelect = 'none';

    const handlePointerMove = (event: PointerEvent) => {
      const board = boardRef.current;
      const drag = dragRef.current;
      if (!board || !drag) {
        return;
      }
      const rect = board.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) {
        return;
      }
      const nextPosition = {
        x: ((event.clientX - rect.left - drag.pointerOffsetX) / rect.width) * 100,
        y: ((event.clientY - rect.top - drag.pointerOffsetY) / rect.height) * 100
      };
      setPositions((current) => ({
        ...current,
        [drag.category]: clampSlotBoardPosition(drag.category, nextPosition)
      }));
      setSaveState('dirty');
    };

    const stopDrag = () => {
      dragRef.current = null;
      setDraggingCategory(null);
    };

    window.addEventListener('pointermove', handlePointerMove);
    window.addEventListener('pointerup', stopDrag);
    window.addEventListener('pointercancel', stopDrag);
    return () => {
      document.body.style.userSelect = previousUserSelect;
      window.removeEventListener('pointermove', handlePointerMove);
      window.removeEventListener('pointerup', stopDrag);
      window.removeEventListener('pointercancel', stopDrag);
    };
  }, [draggingCategory]);

  useEffect(() => {
    if (!draggingAnchor) {
      return;
    }

    const previousUserSelect = document.body.style.userSelect;
    document.body.style.userSelect = 'none';

    const handlePointerMove = (event: PointerEvent) => {
      const board = anchorBoardRef.current;
      const drag = anchorDragRef.current;
      if (!board || !drag) {
        return;
      }
      const rect = board.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) {
        return;
      }
      const nextPoint: AnchorPoint = {
        x: clamp01(((event.clientX - rect.left - drag.pointerOffsetX) / rect.width) * 100),
        y: clamp01(((event.clientY - rect.top - drag.pointerOffsetY) / rect.height) * 100)
      };
      setAnchors((current) => ({
        ...current,
        [drag.category]: { ...current[drag.category], [drag.kind]: nextPoint }
      }));
      setSaveState('dirty');
    };

    const stopDrag = () => {
      anchorDragRef.current = null;
      setDraggingAnchor(null);
    };

    window.addEventListener('pointermove', handlePointerMove);
    window.addEventListener('pointerup', stopDrag);
    window.addEventListener('pointercancel', stopDrag);
    return () => {
      document.body.style.userSelect = previousUserSelect;
      window.removeEventListener('pointermove', handlePointerMove);
      window.removeEventListener('pointerup', stopDrag);
      window.removeEventListener('pointercancel', stopDrag);
    };
  }, [draggingAnchor]);

  const slotLayouts = useMemo(() => SLOT_CONFIGS.map((slot) => ({
    slot,
    layout: slotLayoutWithPosition(slot, positions[slot.category])
  })), [positions]);

  const startDrag = (event: ReactPointerEvent<HTMLButtonElement>, slot: SlotConfig) => {
    if (event.button !== 0) {
      return;
    }
    const board = boardRef.current;
    if (!board) {
      return;
    }
    const rect = board.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) {
      return;
    }
    const position = positions[slot.category] ?? defaultSlotBoardPositions()[slot.category];
    dragRef.current = {
      category: slot.category,
      pointerOffsetX: event.clientX - rect.left - (position.x / 100) * rect.width,
      pointerOffsetY: event.clientY - rect.top - (position.y / 100) * rect.height
    };
    setDraggingCategory(slot.category);
  };

  const startAnchorDrag = (event: ReactPointerEvent<HTMLButtonElement>, category: PartCategory, kind: AnchorKind) => {
    if (event.button !== 0) {
      return;
    }
    const board = anchorBoardRef.current;
    if (!board) {
      return;
    }
    const rect = board.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) {
      return;
    }
    const point = anchors[category]?.[kind] ?? defaultAnchors()[category][kind];
    anchorDragRef.current = {
      category,
      kind,
      pointerOffsetX: event.clientX - rect.left - (point.x / 100) * rect.width,
      pointerOffsetY: event.clientY - rect.top - (point.y / 100) * rect.height
    };
    setDraggingAnchor({ category, kind });
  };

  const handleKeyDown = (event: ReactKeyboardEvent<HTMLButtonElement>, category: PartCategory) => {
    const step = event.shiftKey ? 5 : 1;
    const deltas: Partial<Record<string, SlotBoardPosition>> = {
      ArrowLeft: { x: -step, y: 0 },
      ArrowRight: { x: step, y: 0 },
      ArrowUp: { x: 0, y: -step },
      ArrowDown: { x: 0, y: step }
    };
    const delta = deltas[event.key];
    if (!delta) {
      return;
    }
    event.preventDefault();
    setPositions((current) => {
      const currentPosition = current[category] ?? defaultSlotBoardPositions()[category];
      return {
        ...current,
        [category]: clampSlotBoardPosition(category, {
          x: currentPosition.x + (delta.x ?? 0),
          y: currentPosition.y + (delta.y ?? 0)
        })
      };
    });
    setSaveState('dirty');
  };

  const layoutSourceLabel = layoutQuery.data?.source === 'SAVED' && saveState !== 'reset'
    ? '저장 배치 사용 중'
    : '기본 배치 사용 중';

  return (
    <AdminShell title="슬롯 보드 배치">
      <div className="space-y-5">
        <header className="flex flex-col gap-3 rounded-lg border border-slate-200 bg-white p-5 lg:flex-row lg:items-start lg:justify-between">
          <div>
            <p className="flex items-center gap-2 text-xs font-bold text-brand-blue">
              <LayoutGrid size={15} />
              셀프 견적 레이아웃
            </p>
            <h1 className="mt-1 text-xl font-black text-brand-navy">견적 슬롯 보드 배치</h1>
            <p className="mt-2 max-w-3xl break-keep text-sm leading-6 text-slate-600">
              운영자가 표준 슬롯 카드를 드래그해 저장하면 /self-quote 슬롯 보드에서 같은 category 기준 위치가 적용됩니다.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <span className={`rounded-full px-3 py-1 text-xs font-black ${statusClass(saveState)}`}>
              {saveStateLabel(saveState)}
            </span>
            <button
              type="button"
              onClick={() => resetMutation.mutate()}
              disabled={resetMutation.isPending || saveMutation.isPending}
              className="inline-flex items-center gap-2 rounded border border-slate-200 bg-white px-3 py-2 text-sm font-bold text-brand-navy hover:bg-slate-50 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-400"
            >
              <RotateCcw size={15} />
              기본값으로 초기화
            </button>
            <button
              type="button"
              onClick={() => saveMutation.mutate()}
              disabled={saveMutation.isPending || resetMutation.isPending}
              className="inline-flex items-center gap-2 rounded bg-brand-blue px-4 py-2 text-sm font-bold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-300 disabled:text-slate-500"
            >
              <Save size={15} />
              {saveMutation.isPending ? '저장 중' : '저장하기'}
            </button>
          </div>
        </header>

        {layoutQuery.isError ? (
          <StateMessage type="warn" title="배치 조회 실패" body="저장된 슬롯 보드 배치를 불러오지 못했습니다. 기본 배치로 편집을 계속할 수 있습니다." />
        ) : null}
        {saveMutation.isError ? (
          <StateMessage type="warn" title="배치 저장 실패" body="관리자 권한 또는 좌표 저장 API 응답을 확인해야 합니다." />
        ) : null}
        {resetMutation.isError ? (
          <StateMessage type="warn" title="초기화 실패" body="기본 배치 초기화 요청이 실패했습니다." />
        ) : null}

        <section className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
          <div className="flex items-center justify-between border-b border-slate-200 px-5 py-3">
            <div>
              <h2 className="text-sm font-black text-brand-navy">슬롯 보드 미리보기</h2>
              <p className="mt-1 text-xs text-slate-500">슬롯을 끌어서 위치를 바꾼 뒤 저장하기를 누르세요. 방향키로 1칸, Shift+방향키로 5칸 이동합니다.</p>
            </div>
            <div className="text-xs font-bold text-slate-500">{layoutSourceLabel}</div>
          </div>
          <div className="bg-[linear-gradient(180deg,#f8fafc_0%,#ffffff_100%)] p-4">
            <div
              ref={boardRef}
              data-testid="admin-slot-layout-board"
              className="relative aspect-[16/10] min-h-[520px] overflow-hidden rounded-lg border border-slate-200 bg-slate-900/[0.03] bg-cover bg-center"
              style={{ backgroundImage: `url(${SLOT_BOARD_BG})` }}
            >
              {slotLayouts.map(({ slot, layout }) => (
                <SlotLayoutCard
                  key={slot.category}
                  slot={slot}
                  layout={layout}
                  isDragging={draggingCategory === slot.category}
                  onPointerDown={(event) => startDrag(event, slot)}
                  onKeyDown={(event) => handleKeyDown(event, slot.category)}
                />
              ))}
            </div>
          </div>
        </section>

        <section className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
          <div className="border-b border-slate-200 px-5 py-3">
            <h2 className="text-sm font-black text-brand-navy">3D 커넥터 앵커 배치</h2>
            <p className="mt-1 text-xs text-slate-500">
              카드 앵커(파랑)와 부품 앵커(주황)를 끌어 self-quote 3D 관계도 연결선을 조정합니다. 카테고리를 선택한 뒤 보드 위 점을 드래그하세요.
            </p>
          </div>
          <div className="flex flex-wrap gap-1.5 border-b border-slate-200 px-5 py-3">
            {RECOMMENDED_SLOT_ORDER.map((category) => {
              const slot = slotConfigFor(category);
              return (
                <button
                  key={category}
                  type="button"
                  data-testid={`admin-anchor-category-${category}`}
                  onClick={() => setSelectedAnchorCategory(category)}
                  className={`rounded-full border px-3 py-1.5 text-xs font-bold transition ${
                    selectedAnchorCategory === category
                      ? 'border-brand-blue bg-blue-50 text-brand-blue'
                      : 'border-slate-200 bg-white text-slate-600 hover:border-brand-blue/60'
                  }`}
                >
                  {slot?.label ?? category}
                </button>
              );
            })}
          </div>
          <div className="bg-[linear-gradient(180deg,#f8fafc_0%,#ffffff_100%)] p-4">
            <div
              ref={anchorBoardRef}
              data-testid="admin-anchor-board"
              className="relative aspect-[16/8.4] overflow-hidden rounded-lg border border-slate-200 bg-[#f6fbff] bg-contain bg-center bg-no-repeat"
              style={{ backgroundImage: `url(${SLOT_BOARD_ISO_SCENE})` }}
            >
              <AnchorHandle
                category={selectedAnchorCategory}
                kind="card"
                point={anchors[selectedAnchorCategory]?.card ?? defaultAnchors()[selectedAnchorCategory].card}
                isDragging={draggingAnchor?.category === selectedAnchorCategory && draggingAnchor?.kind === 'card'}
                onPointerDown={(event) => startAnchorDrag(event, selectedAnchorCategory, 'card')}
              />
              <AnchorHandle
                category={selectedAnchorCategory}
                kind="part"
                point={anchors[selectedAnchorCategory]?.part ?? defaultAnchors()[selectedAnchorCategory].part}
                isDragging={draggingAnchor?.category === selectedAnchorCategory && draggingAnchor?.kind === 'part'}
                onPointerDown={(event) => startAnchorDrag(event, selectedAnchorCategory, 'part')}
              />
            </div>
          </div>
          <div className="border-t border-slate-200 p-5">
            <h3 className="text-xs font-black text-brand-navy">앵커 좌표</h3>
            <div className="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
              {RECOMMENDED_SLOT_ORDER.map((category) => {
                const pair = anchors[category] ?? defaultAnchors()[category];
                const slot = slotConfigFor(category);
                return (
                  <div
                    key={category}
                    data-testid={`admin-anchor-readout-${category}`}
                    className="rounded border border-slate-100 bg-slate-50 px-3 py-2 text-xs"
                  >
                    <div className="font-bold text-slate-700">{slot?.label ?? category}</div>
                    <div className="mt-1 font-mono text-slate-500">카드 x {pair.card.x} · y {pair.card.y}</div>
                    <div className="font-mono text-slate-500">부품 x {pair.part.x} · y {pair.part.y}</div>
                  </div>
                );
              })}
            </div>
          </div>
        </section>

        <section className="rounded-lg border border-slate-200 bg-white p-5">
          <h2 className="text-sm font-black text-brand-navy">저장 좌표</h2>
          <div className="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
            {SLOT_CONFIGS.map((slot) => {
              const position = positions[slot.category] ?? defaultSlotBoardPositions()[slot.category];
              return (
                <div key={slot.category} className="flex items-center justify-between rounded border border-slate-100 bg-slate-50 px-3 py-2 text-xs">
                  <span className="font-bold text-slate-700">{slot.label}</span>
                  <span className="font-mono text-slate-500">x {position.x} · y {position.y}</span>
                </div>
              );
            })}
          </div>
        </section>
      </div>
    </AdminShell>
  );
}

function SlotLayoutCard({
  slot,
  layout,
  isDragging,
  onPointerDown,
  onKeyDown
}: {
  slot: SlotConfig;
  layout: SlotConfig['layout'];
  isDragging: boolean;
  onPointerDown: (event: ReactPointerEvent<HTMLButtonElement>) => void;
  onKeyDown: (event: ReactKeyboardEvent<HTMLButtonElement>) => void;
}) {
  const style: CSSProperties = {
    left: `${layout.x}%`,
    top: `${layout.y}%`,
    width: `${layout.w}%`,
    height: `${layout.h}%`
  };

  return (
    <button
      type="button"
      data-testid={`admin-slot-layout-card-${slot.category}`}
      aria-label={`${slot.label} 슬롯 배치 이동`}
      title={`${slot.label} 슬롯 배치 이동`}
      onPointerDown={onPointerDown}
      onKeyDown={onKeyDown}
      style={style}
      className={`absolute flex touch-none cursor-grab flex-col justify-between rounded-lg border bg-white/95 p-3 text-left shadow-sm transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue ${
        isDragging ? 'z-20 scale-[1.02] cursor-grabbing border-brand-blue ring-4 ring-blue-100' : 'z-10 border-commerce-line hover:border-brand-blue'
      }`}
    >
      <span className="flex items-center justify-between gap-2">
        <span className="flex items-center gap-2 text-[11px] font-black text-slate-600">
          <img src={slot.glyph} alt="" aria-hidden="true" className="h-6 w-auto max-w-16" />
          {slot.label}
        </span>
        <Move size={14} className="text-slate-400" aria-hidden="true" />
      </span>
      <span className="text-xs font-black leading-4 text-commerce-ink">
        {slot.category === 'STORAGE' ? 'SSD / 저장장치' : `${slot.label} 슬롯`}
      </span>
    </button>
  );
}

function AnchorHandle({
  category,
  kind,
  point,
  isDragging,
  onPointerDown
}: {
  category: PartCategory;
  kind: AnchorKind;
  point: AnchorPoint;
  isDragging: boolean;
  onPointerDown: (event: ReactPointerEvent<HTMLButtonElement>) => void;
}) {
  const style: CSSProperties = {
    left: `${point.x}%`,
    top: `${point.y}%`
  };
  const label = kind === 'card' ? '카드 앵커' : '부품 앵커';
  const colorClass = kind === 'card' ? 'border-brand-blue bg-brand-blue' : 'border-amber-500 bg-amber-500';

  return (
    <button
      type="button"
      data-testid={`admin-anchor-handle-${category}-${kind}`}
      aria-label={`${category} ${label} 이동`}
      title={`${category} ${label}`}
      onPointerDown={onPointerDown}
      style={style}
      className={`absolute z-20 flex h-5 w-5 -translate-x-1/2 -translate-y-1/2 touch-none cursor-grab items-center justify-center rounded-full border-2 text-white shadow-md transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue ${colorClass} ${
        isDragging ? 'scale-125 cursor-grabbing' : ''
      }`}
    >
      <span className="sr-only">{label}</span>
    </button>
  );
}

function saveStateLabel(state: SaveState) {
  if (state === 'dirty') return '저장되지 않은 변경';
  if (state === 'saved') return '저장 완료';
  if (state === 'reset') return '기본 배치로 초기화됨';
  return '변경 없음';
}

function statusClass(state: SaveState) {
  if (state === 'dirty') return 'bg-amber-50 text-amber-700';
  if (state === 'saved' || state === 'reset') return 'bg-emerald-50 text-emerald-700';
  return 'bg-slate-100 text-slate-600';
}
