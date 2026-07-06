import type { PartCategory } from '../../../quote/aiSelection';

export type SlotConfig = {
  category: PartCategory;
  label: string;
  glyph: string;
  /** 시각적 mini slot 수. 실제 메인보드 수용량이 아니라 표현용 고정값이다. */
  miniSlots?: number;
  /** mini slot 채움 기준: RAM은 quantity 합산, STORAGE는 item 개수 */
  miniFillBy?: 'quantity' | 'items';
  /** 슬롯 보드(데스크톱) 기준 % 좌표 */
  layout: { x: number; y: number; w: number; h: number };
};

export type SlotBoardPosition = {
  x: number;
  y: number;
};

export const SLOT_BOARD_BG = '/slot-board/backgrounds/topology-board-bg.svg';

// 목표 이미지처럼 메인보드의 실제 장착 위치가 먼저 읽히도록 잡은 기본 좌표(%).
// 관리자 저장 좌표가 있으면 이 좌표는 fallback으로만 사용된다.
export const SLOT_CONFIGS: SlotConfig[] = [
  { category: 'CPU', label: 'CPU', glyph: '/slot-board/parts/cpu.svg', layout: { x: 9, y: 6, w: 23, h: 18 } },
  { category: 'RAM', label: 'RAM', glyph: '/slot-board/parts/ram.svg', miniSlots: 4, miniFillBy: 'quantity', layout: { x: 43, y: 8, w: 18, h: 18 } },
  { category: 'STORAGE', label: 'SSD', glyph: '/slot-board/parts/ssd.svg', miniSlots: 2, miniFillBy: 'items', layout: { x: 74, y: 25, w: 20, h: 17 } },
  { category: 'COOLER', label: '쿨러', glyph: '/slot-board/parts/cooler.svg', layout: { x: 9, y: 30, w: 23, h: 17 } },
  { category: 'MOTHERBOARD', label: '메인보드', glyph: '/slot-board/parts/motherboard.svg', layout: { x: 31, y: 76, w: 20, h: 15 } },
  { category: 'GPU', label: 'GPU', glyph: '/slot-board/parts/gpu.svg', layout: { x: 61, y: 53, w: 34, h: 18 } },
  { category: 'PSU', label: '파워', glyph: '/slot-board/parts/psu.svg', layout: { x: 58, y: 78, w: 25, h: 16 } },
  { category: 'CASE', label: '케이스', glyph: '/slot-board/parts/case.svg', layout: { x: 8, y: 76, w: 20, h: 16 } }
];

export const SLOT_COUNT = SLOT_CONFIGS.length;

export const SLOT_BOARD_DEFAULT_POSITIONS: Record<PartCategory, SlotBoardPosition> = SLOT_CONFIGS.reduce((positions, slot) => {
  positions[slot.category] = { x: slot.layout.x, y: slot.layout.y };
  return positions;
}, {} as Record<PartCategory, SlotBoardPosition>);

export type SlotEdgeConfig = {
  from: PartCategory;
  to: PartCategory;
  /** graph API 응답이 없을 때 항상 표시하는 기본 topology 라벨 */
  label: string;
};

export const FALLBACK_EDGES: SlotEdgeConfig[] = [
  { from: 'CPU', to: 'MOTHERBOARD', label: '소켓 호환' },
  { from: 'CPU', to: 'COOLER', label: '쿨러 장착' },
  { from: 'MOTHERBOARD', to: 'RAM', label: '메모리 규격' },
  { from: 'GPU', to: 'MOTHERBOARD', label: 'PCIe x16' },
  { from: 'PSU', to: 'MOTHERBOARD', label: '24핀 전원' },
  { from: 'GPU', to: 'PSU', label: '전력 여유' },
  { from: 'GPU', to: 'CASE', label: '장착 길이' },
  { from: 'COOLER', to: 'CASE', label: '높이 여유' }
];

export function slotConfigFor(category: string): SlotConfig | undefined {
  return SLOT_CONFIGS.find((slot) => slot.category === category);
}

export function isSlotCategory(category: string | null): category is PartCategory {
  return Boolean(category && SLOT_CONFIGS.some((slot) => slot.category === category));
}

/** RAM/STORAGE처럼 여러 항목을 담을 수 있는 카테고리 */
export function isMultiItemCategory(category: string) {
  return category === 'RAM' || category === 'STORAGE';
}

export function defaultSlotBoardPositions() {
  return { ...SLOT_BOARD_DEFAULT_POSITIONS };
}

export function isSlotBoardPercentPosition(category: string, position: unknown): position is SlotBoardPosition {
  const slot = slotConfigFor(category);
  if (!slot || !position || typeof position !== 'object') {
    return false;
  }
  const candidate = position as { x?: unknown; y?: unknown };
  const x = Number(candidate.x);
  const y = Number(candidate.y);
  return Number.isFinite(x) && Number.isFinite(y) && x >= 0 && y >= 0 && x <= 100 && y <= 100;
}

export function clampSlotBoardPosition(category: PartCategory, position: SlotBoardPosition): SlotBoardPosition {
  const slot = slotConfigFor(category);
  const maxX = Math.max(0, 100 - (slot?.layout.w ?? 0));
  const maxY = Math.max(0, 100 - (slot?.layout.h ?? 0));
  return {
    x: clamp(Math.round(position.x), 0, maxX),
    y: clamp(Math.round(position.y), 0, maxY)
  };
}

export function mergeSlotBoardPositions(positions: Record<string, SlotBoardPosition> = {}) {
  const next = defaultSlotBoardPositions();
  for (const [category, position] of Object.entries(positions)) {
    if (isSlotCategory(category) && isSlotBoardPercentPosition(category, position)) {
      next[category] = clampSlotBoardPosition(category, position);
    }
  }
  return next;
}

export function slotLayoutWithPosition(slot: SlotConfig, position?: SlotBoardPosition) {
  const clamped = position ? clampSlotBoardPosition(slot.category, position) : SLOT_BOARD_DEFAULT_POSITIONS[slot.category];
  return {
    ...slot.layout,
    x: clamped.x,
    y: clamped.y
  };
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}
