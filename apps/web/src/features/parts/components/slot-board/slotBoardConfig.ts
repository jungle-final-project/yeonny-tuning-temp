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

// 허브 방사형 기본 좌표(%): 메인보드가 중앙 허브, 7부품이 시계 방향 링.
// "모든 부품은 메인보드에 꽂힌다"는 직관 그대로 — 허브 스포크는 교차하지 않고,
// 크로스 관계(쿨러-케이스, GPU-파워 등)가 있는 부품끼리 인접 배치해 곡선이 짧게 지나간다.
// 관리자 저장 좌표가 있으면 이 좌표는 fallback으로만 사용된다.
export const SLOT_CONFIGS: SlotConfig[] = [
  { category: 'CPU', label: 'CPU', glyph: '/slot-board/parts/cpu.svg', layout: { x: 39, y: 2.5, w: 22, h: 17 } },
  { category: 'RAM', label: 'RAM', glyph: '/slot-board/parts/ram.svg', miniSlots: 4, miniFillBy: 'quantity', layout: { x: 73, y: 13.5, w: 21, h: 17 } },
  { category: 'STORAGE', label: 'SSD', glyph: '/slot-board/parts/ssd.svg', miniSlots: 2, miniFillBy: 'items', layout: { x: 78, y: 46.5, w: 21, h: 17 } },
  { category: 'GPU', label: 'GPU', glyph: '/slot-board/parts/gpu.svg', layout: { x: 66, y: 77.5, w: 21, h: 17 } },
  { category: 'PSU', label: '파워', glyph: '/slot-board/parts/psu.svg', layout: { x: 30.5, y: 81.5, w: 21, h: 17 } },
  { category: 'CASE', label: '케이스', glyph: '/slot-board/parts/case.svg', layout: { x: 1.5, y: 67.5, w: 21, h: 17 } },
  { category: 'COOLER', label: '쿨러', glyph: '/slot-board/parts/cooler.svg', layout: { x: 5.5, y: 13.5, w: 21, h: 17 } },
  { category: 'MOTHERBOARD', label: '메인보드', glyph: '/slot-board/parts/motherboard.svg', layout: { x: 37, y: 39, w: 26, h: 22 } }
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
  /**
   * 크로스 관계(허브 미경유) 곡선의 볼록 정도(%). 양수 = 보드 바깥쪽으로 휨,
   * 음수 = 중앙 쪽으로 휨(빈 회랑 통과). 허브 스포크는 직선이라 무시된다.
   */
  bow?: number;
  /** 라벨 위치(0=from 쪽 … 1=to 쪽, 기본 0.5) — 이웃 라벨과 겹칠 때만 조정한다. */
  labelT?: number;
};

export const FALLBACK_EDGES: SlotEdgeConfig[] = [
  { from: 'CPU', to: 'MOTHERBOARD', label: '소켓 호환' },
  { from: 'MOTHERBOARD', to: 'RAM', label: '메모리 규격' },
  { from: 'GPU', to: 'MOTHERBOARD', label: 'PCIe x16' },
  // 라벨을 허브 쪽으로 올려 케이스-GPU 안쪽 곡선 라벨과의 겹침을 피한다.
  { from: 'PSU', to: 'MOTHERBOARD', label: '24핀 전원', labelT: 0.72 },
  { from: 'CPU', to: 'COOLER', label: '쿨러 장착', bow: 7 },
  { from: 'COOLER', to: 'CASE', label: '높이 여유', bow: 6 },
  { from: 'PSU', to: 'CASE', label: '파워 깊이', bow: 6 },
  { from: 'GPU', to: 'PSU', label: '전력 여유', bow: 5 },
  // 케이스-GPU는 하단이 붐벼서 허브와 파워 사이의 빈 회랑으로 안쪽 곡선을 태운다.
  { from: 'GPU', to: 'CASE', label: '장착 길이', bow: -9 }
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
