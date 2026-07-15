import { Fragment, useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type FocusEvent, type RefObject } from 'react';
import type { PartCategory } from '../../../quote/aiSelection';
import type { QuoteDraftItem } from '../../types';
import {
  FUSED_BADGE_ANCHORS,
  FUSED_BOARD_SIZE,
  FUSED_PART_AREAS,
  FUSED_PART_LAYERS,
  FUSED_PLATE_BG,
  FUSED_PRELOAD_URLS
} from './fusedPlateConfig';
import { itemStickCount } from './slotBoardItemCounts';
import { slotOrderNumber } from './slotBoardConfig';
import './FusedPlateArt.css';

type FusedPlateArtProps = {
  items: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  aiFocusCategories: PartCategory[];
  statusByCategory: Map<string, 'PASS' | 'WARN' | 'FAIL'>;
  flashingCategories: Set<PartCategory>;
  onSlotSelect: (category: PartCategory) => void;
  onRemoveItem: (partId: string) => void;
  onUpdateQuantity: (partId: string, quantity: number) => void;
  isRemovePending: boolean;
  isQuantityPending: boolean;
};

export function FusedPlateArt({
  items,
  selectedCategory,
  aiFocusCategories,
  statusByCategory,
  flashingCategories,
  onSlotSelect,
  onRemoveItem,
  onUpdateQuantity,
  isRemovePending,
  isQuantityPending
}: FusedPlateArtProps) {
  const itemsByCategory = new Map<string, QuoteDraftItem[]>();
  items.forEach((item) => {
    const categoryItems = itemsByCategory.get(item.category) ?? [];
    categoryItems.push(item);
    itemsByCategory.set(item.category, categoryItems);
  });

  const filledCategories = new Set(items.map((item) => item.category));
  const ramItems = itemsByCategory.get('RAM') ?? [];
  const ramSlotCount = Math.min(4, ramItems.reduce((sum, item) => sum + itemStickCount(item), 0));
  const mountingRamSlots = useMountingRamSlots(ramSlotCount);
  const ramIncreaseTarget = ramSlotCount < 4 ? findRamIncreaseTarget(ramItems, ramSlotCount) : null;
  const ramDecreaseAction = ramSlotCount > 0 ? findRamDecreaseAction(ramItems, ramSlotCount) : null;
  const isActionPending = isRemovePending || isQuantityPending;
  const [hoveredCategory, setHoveredCategory] = useState<PartCategory | null>(null);
  const stageContainerRef = useRef<HTMLDivElement | null>(null);
  const stageSize = useContainedPlateSize(stageContainerRef);
  const aiFocusSet = new Set(aiFocusCategories);
  const hasAiFocus = aiFocusSet.size > 0;
  const hasHoveredLayer = hoveredCategory
    ? hoveredCategory === 'RAM'
      ? ramSlotCount > 0 || selectedCategory === 'RAM'
      : filledCategories.has(hoveredCategory) || selectedCategory === hoveredCategory
    : false;
  const spotlightCategory = hasHoveredLayer ? hoveredCategory : null;
  const spotlightCategories = hasAiFocus
    ? aiFocusSet
    : new Set<PartCategory>();

  useEffect(() => {
    FUSED_PRELOAD_URLS.forEach((src) => {
      const image = new Image();
      image.src = src;
    });
  }, []);

  const clearHoverIfLeavingArea = (category: PartCategory) => {
    setHoveredCategory((current) => current === category ? null : current);
  };

  const clearHoverIfFocusLeavesArea = (category: PartCategory, event: FocusEvent<HTMLDivElement>) => {
    const nextTarget = event.relatedTarget;
    if (nextTarget instanceof Node && event.currentTarget.contains(nextTarget)) {
      return;
    }
    clearHoverIfLeavingArea(category);
  };

  const removeCategoryItems = (categoryItems: QuoteDraftItem[]) => {
    if (isActionPending || categoryItems.length === 0) {
      return;
    }
    categoryItems.forEach((item) => onRemoveItem(item.partId));
  };

  const decreaseRam = () => {
    if (isActionPending || !ramDecreaseAction) {
      return;
    }
    if (ramDecreaseAction.type === 'quantity') {
      onUpdateQuantity(ramDecreaseAction.item.partId, ramDecreaseAction.quantity);
      return;
    }
    onRemoveItem(ramDecreaseAction.item.partId);
  };

  const increaseRam = () => {
    if (isActionPending || !ramIncreaseTarget) {
      return;
    }
    onUpdateQuantity(ramIncreaseTarget.partId, ramIncreaseTarget.quantity + 1);
  };

  return (
    <div ref={stageContainerRef} data-testid="slot-board-fused-plate" className="absolute inset-0 hidden h-full w-full items-center justify-center overflow-hidden bg-[#f6f7f9] lg:flex">
      <div
        className="relative shrink-0"
        style={stageSize
          ? { width: stageSize.width, height: stageSize.height }
          : { width: '100%', aspectRatio: `${FUSED_BOARD_SIZE.width} / ${FUSED_BOARD_SIZE.height}` }}
      >
        <img
          src={FUSED_PLATE_BG}
          alt=""
          aria-hidden="true"
          data-dimmed={spotlightCategories.size > 0 ? 'true' : 'false'}
          className="fused-plate-bg pointer-events-none absolute inset-0 h-full w-full select-none object-contain"
        />
        {FUSED_PART_LAYERS.map((layer) => {
          const visible = layer.category === 'RAM'
            ? (layer.slotIndex ?? 0) < ramSlotCount
            : filledCategories.has(layer.category);
          const focused = selectedCategory === layer.category;
          const hovered = !hasAiFocus && spotlightCategory === layer.category;
          const spotlighted = spotlightCategories.has(layer.category);
          const aiSpotlighted = aiFocusSet.has(layer.category);
          const dimmed = spotlightCategories.size > 0 && !spotlighted;
          const mounting = visible && (
            layer.category === 'RAM'
              ? layer.slotIndex !== undefined && mountingRamSlots.has(layer.slotIndex)
              : flashingCategories.has(layer.category)
          );
          const status = statusByCategory.get(layer.category) ?? 'PASS';
          const testId = `slot-fused-layer-${layer.category}${layer.slotIndex !== undefined ? `-${layer.slotIndex + 1}` : ''}`;
          return (
            <Fragment key={layer.src}>
              <img
                aria-hidden="true"
                data-testid={`${testId}-problem-blur`}
                data-visible={visible ? 'true' : 'false'}
                data-spotlight={spotlighted ? 'true' : 'false'}
                data-ai-spotlight={aiSpotlighted ? 'true' : 'false'}
                data-dimmed={dimmed ? 'true' : 'false'}
                data-status={status}
                src={layer.src}
                alt=""
                className="fused-part-problem-blur fused-part-problem-glow pointer-events-none absolute inset-0 h-full w-full select-none object-contain"
                style={{ zIndex: 4 }}
              />
              <img
                data-testid={testId}
                data-visible={visible ? 'true' : 'false'}
                data-hovered={hovered ? 'true' : 'false'}
                data-spotlight={spotlighted ? 'true' : 'false'}
                data-ai-spotlight={aiSpotlighted ? 'true' : 'false'}
                data-dimmed={dimmed ? 'true' : 'false'}
                data-mounting={mounting ? 'true' : 'false'}
                data-status={status}
                src={layer.src}
                alt=""
                aria-hidden="true"
                className={`fused-part-layer pointer-events-none absolute inset-0 h-full w-full select-none object-contain ${
                  visible ? 'opacity-100' : 'opacity-0'
                } ${focused || aiSpotlighted ? 'z-20' : 'z-10'}`}
              />
            </Fragment>
          );
        })}
        {FUSED_PART_AREAS.map((area) => {
          const categoryItems = itemsByCategory.get(area.category) ?? [];
          const filled = area.category === 'RAM' ? ramSlotCount > 0 : categoryItems.length > 0;
          const hovered = !hasAiFocus && hoveredCategory === area.category;
          const selected = selectedCategory === area.category;
          const aiSpotlighted = aiFocusSet.has(area.category);
          const aiDimmed = hasAiFocus && !aiSpotlighted;
          const status = statusByCategory.get(area.category) ?? 'PASS';
          return (
            <div
              key={area.category}
              data-testid={`slot-fused-area-wrap-${area.category}`}
              data-filled={filled ? 'true' : 'false'}
              data-hovered={hovered ? 'true' : 'false'}
              data-selected={selected ? 'true' : 'false'}
              data-ai-spotlight={aiSpotlighted ? 'true' : 'false'}
              data-ai-dimmed={aiDimmed ? 'true' : 'false'}
              data-mounted={filled ? 'true' : 'false'}
              data-status={status}
              onPointerEnter={() => setHoveredCategory(area.category)}
              onPointerLeave={() => clearHoverIfLeavingArea(area.category)}
              onFocus={() => setHoveredCategory(area.category)}
              onBlur={(event) => clearHoverIfFocusLeavesArea(area.category, event)}
              className="fused-part-hitbox absolute z-30 rounded"
              style={fusedAreaStyle(area.box, FUSED_BADGE_ANCHORS[area.category])}
            >
              <button
                type="button"
                data-testid={`slot-fused-area-${area.category}`}
                aria-label={`${area.label} select`}
                aria-pressed={selectedCategory === area.category}
                onClick={() => onSlotSelect(area.category)}
                className="fused-part-select-button absolute inset-0 cursor-pointer rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue"
              />
              {selected ? <span className="fused-part-selected-badge">선택됨</span> : null}
              {aiSpotlighted && !filled ? (
                <span
                  data-testid={`slot-fused-ai-unmounted-${area.category}`}
                  className="pointer-events-none absolute bottom-1 right-1 z-40 rounded border border-blue-200 bg-blue-50/95 px-1.5 py-0.5 text-[9px] font-black text-brand-blue shadow-sm"
                >
                  미장착
                </span>
              ) : null}
              {filled && area.category === 'RAM' ? (
                <>
                  {hovered ? (
                    <span data-testid="slot-fused-label-RAM" className="fused-part-hover-label">
                      {area.label}
                    </span>
                  ) : null}
                  <span data-testid="slot-fused-badge-RAM" className="fused-ram-number-badge">
                    {slotOrderNumber(area.category)}
                  </span>
                  {hovered ? (
                    <>
                      <div data-testid="slot-fused-ram-controls" className="fused-ram-quantity-controls" aria-label="RAM quantity controls">
                        <button
                          type="button"
                          data-testid="slot-fused-ram-decrease"
                          aria-label="Decrease RAM quantity"
                          disabled={isActionPending || !ramDecreaseAction}
                          onClick={(event) => {
                            event.preventDefault();
                            event.stopPropagation();
                            decreaseRam();
                          }}
                        >
                          -
                        </button>
                        <span data-testid="slot-fused-ram-count">{ramSlotCount}</span>
                        <button
                          type="button"
                          data-testid="slot-fused-ram-increase"
                          aria-label="Increase RAM quantity"
                          disabled={isActionPending || !ramIncreaseTarget}
                          onClick={(event) => {
                            event.preventDefault();
                            event.stopPropagation();
                            increaseRam();
                          }}
                        >
                          +
                        </button>
                      </div>
                      <button
                        type="button"
                        data-testid="slot-fused-remove-RAM"
                        aria-label="Remove RAM"
                        disabled={isActionPending}
                        onClick={(event) => {
                          event.preventDefault();
                          event.stopPropagation();
                          removeCategoryItems(categoryItems);
                        }}
                        className="fused-ram-remove-button"
                      >
                        X
                      </button>
                    </>
                  ) : null}
                </>
              ) : null}
              {filled && area.category !== 'RAM' ? (
                <>
                  {hovered ? (
                    <span data-testid={`slot-fused-label-${area.category}`} className="fused-part-hover-label">
                      {area.label}
                    </span>
                  ) : null}
                  <button
                    type="button"
                    data-testid={`slot-fused-remove-${area.category}`}
                    aria-label={`Remove ${area.category}`}
                    disabled={isActionPending}
                    onClick={(event) => {
                      event.preventDefault();
                      event.stopPropagation();
                      if (!hovered) {
                        return;
                      }
                      removeCategoryItems(categoryItems);
                    }}
                    className="fused-part-action-button"
                  >
                    <span data-testid={`slot-fused-badge-${area.category}`} className="fused-part-action-number">
                      {slotOrderNumber(area.category)}
                    </span>
                    <span className="fused-part-action-x" aria-hidden="true">X</span>
                  </button>
                </>
              ) : null}
            </div>
          );
        })}
      </div>
    </div>
  );
}

type ContainedPlateSize = { width: number; height: number };

// 부모 영역 안에 전체 판을 맞추되 원본 비율은 유지한다. 이미지·번호·클릭 영역이 같은 무대를 공유해 좌표가 어긋나지 않는다.
function useContainedPlateSize(containerRef: RefObject<HTMLDivElement>): ContainedPlateSize | null {
  const [size, setSize] = useState<ContainedPlateSize | null>(null);

  useLayoutEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const update = () => {
      const containerWidth = container.clientWidth;
      const containerHeight = container.clientHeight;
      if (containerWidth <= 0 || containerHeight <= 0) return;

      const ratio = FUSED_BOARD_SIZE.width / FUSED_BOARD_SIZE.height;
      const width = Math.min(containerWidth, containerHeight * ratio);
      const height = width / ratio;
      setSize((current) => (
        current
          && Math.abs(current.width - width) < 0.5
          && Math.abs(current.height - height) < 0.5
          ? current
          : { width, height }
      ));
    };

    update();
    const observer = new ResizeObserver(update);
    observer.observe(container);
    return () => observer.disconnect();
  }, [containerRef]);

  return size;
}

function useMountingRamSlots(ramSlotCount: number) {
  const previousRamSlotCountRef = useRef<number | null>(null);
  const [mountingRamSlots, setMountingRamSlots] = useState<Set<number>>(new Set());

  useEffect(() => {
    const previousRamSlotCount = previousRamSlotCountRef.current;
    previousRamSlotCountRef.current = ramSlotCount;
    if (previousRamSlotCount === null) {
      return;
    }
    if (ramSlotCount <= previousRamSlotCount) {
      setMountingRamSlots(new Set());
      return;
    }

    const nextMountingRamSlots = new Set<number>();
    for (let slotIndex = previousRamSlotCount; slotIndex < ramSlotCount; slotIndex += 1) {
      nextMountingRamSlots.add(slotIndex);
    }
    setMountingRamSlots(nextMountingRamSlots);
    const timer = window.setTimeout(() => setMountingRamSlots(new Set()), 900);
    return () => window.clearTimeout(timer);
  }, [ramSlotCount]);

  return mountingRamSlots;
}

type FusedAreaStyle = CSSProperties & {
  '--fused-badge-left': string;
  '--fused-badge-top': string;
};

function fusedAreaStyle(
  box: { x: number; y: number; w: number; h: number },
  badgeAnchor: { x: number; y: number }
): FusedAreaStyle {
  return {
    left: `${(box.x / FUSED_BOARD_SIZE.width) * 100}%`,
    top: `${(box.y / FUSED_BOARD_SIZE.height) * 100}%`,
    width: `${(box.w / FUSED_BOARD_SIZE.width) * 100}%`,
    height: `${(box.h / FUSED_BOARD_SIZE.height) * 100}%`,
    '--fused-badge-left': `${((badgeAnchor.x - box.x) / box.w) * 100}%`,
    '--fused-badge-top': `${((badgeAnchor.y - box.y) / box.h) * 100}%`
  };
}

type RamDecreaseAction =
  | { type: 'quantity'; item: QuoteDraftItem; quantity: number }
  | { type: 'remove'; item: QuoteDraftItem };

function findRamIncreaseTarget(ramItems: QuoteDraftItem[], ramSlotCount: number): QuoteDraftItem | null {
  let target: QuoteDraftItem | null = null;
  let targetModuleCount = Number.POSITIVE_INFINITY;
  for (const item of ramItems) {
    if (item.quantity >= 9) {
      continue;
    }
    const moduleCount = ramModuleCount(item);
    if (ramSlotCount + moduleCount > 4 || moduleCount >= targetModuleCount) {
      continue;
    }
    target = item;
    targetModuleCount = moduleCount;
  }
  return target;
}

function findRamDecreaseAction(ramItems: QuoteDraftItem[], ramSlotCount: number): RamDecreaseAction | null {
  let quantityTarget: QuoteDraftItem | null = null;
  let quantityTargetModuleCount = Number.POSITIVE_INFINITY;
  for (const item of ramItems) {
    if (item.quantity <= 1) {
      continue;
    }
    const moduleCount = ramModuleCount(item);
    if (ramSlotCount - moduleCount < 1 || moduleCount >= quantityTargetModuleCount) {
      continue;
    }
    quantityTarget = item;
    quantityTargetModuleCount = moduleCount;
  }
  if (quantityTarget) {
    return { type: 'quantity', item: quantityTarget, quantity: quantityTarget.quantity - 1 };
  }

  for (let index = ramItems.length - 1; index >= 0; index -= 1) {
    const item = ramItems[index];
    if (ramSlotCount - ramModuleCount(item) >= 0) {
      return { type: 'remove', item };
    }
  }

  return null;
}

function ramModuleCount(item: QuoteDraftItem): number {
  const quantity = Math.max(1, item.quantity);
  return Math.max(1, Math.round(itemStickCount(item) / quantity));
}
