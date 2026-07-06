import type { PartCategory } from '../../../quote/aiSelection';

export type SlotConfig = {
  category: PartCategory;
  label: string;
  glyph: string;
  /** 시각적 mini slot 수. 실제 메인보드 수용량이 아니라 표현용 고정값이다. */
  miniSlots?: number;
  /** mini slot 채움 기준: RAM은 quantity 합산, STORAGE는 item 개수 */
  miniFillBy?: 'quantity' | 'items';
  /** 실장 방식: board = 평면도 위 실장 지점, dock = 보드 밖 도킹 베이 */
  mount: 'board' | 'dock';
  /** 슬롯 보드(데스크톱) 기준 % 좌표 — 평면도 아트(viewBox 160×100)와 같은 상수에서 유도 */
  layout: { x: number; y: number; w: number; h: number };
};

export type SlotBoardPosition = {
  x: number;
  y: number;
};

// (관리자 배치 페이지의 캔버스 배경 전용 — 실장도 보드는 인라인 아트를 쓴다)
export const SLOT_BOARD_BG = '/slot-board/backgrounds/topology-board-bg.svg';

// 실장도(placement) 좌표계: 평면도 아트가 viewBox 0 0 160 100으로 그려지고
// 컨테이너는 lg:aspect-[16/10]이라 아트 1unit = 화면에서 가로세로 같은 길이다.
// 아래 % 좌표는 아트 좌표를 (x/1.6, y) 변환한 값 — 아트의 소켓/슬롯 위치와 반드시 함께 움직여야 한다.
export const SLOT_BOARD_ART_VIEWBOX = '0 0 160 100';

// 실장도 기본 좌표(%): 보드 위 부품(CPU 소켓·DIMM·PCIe·M.2)은 평면도의 실장 지점에,
// 보드에 꽂히지 않는 부품(파워·쿨러·케이스)은 우측 도킹 베이에 배치한다.
// "무엇이 어디에 들어가는가"를 그림 자체가 설명하는 것이 목적(멘토 Don't-make-me-think).
export const SLOT_CONFIGS: SlotConfig[] = [
  { category: 'CPU', label: 'CPU', glyph: '/slot-board/parts/cpu.svg', mount: 'board', layout: { x: 18.75, y: 16, w: 17.5, h: 28 } },
  { category: 'RAM', label: 'RAM', glyph: '/slot-board/parts/ram.svg', miniSlots: 4, miniFillBy: 'quantity', mount: 'board', layout: { x: 40, y: 8, w: 16.25, h: 42 } },
  { category: 'GPU', label: 'GPU', glyph: '/slot-board/parts/gpu.svg', mount: 'board', layout: { x: 6.25, y: 54, w: 42.5, h: 16 } },
  { category: 'STORAGE', label: 'SSD', glyph: '/slot-board/parts/ssd.svg', miniSlots: 2, miniFillBy: 'items', mount: 'board', layout: { x: 51.25, y: 72, w: 15, h: 12 } },
  { category: 'MOTHERBOARD', label: '메인보드', glyph: '/slot-board/parts/motherboard.svg', mount: 'board', layout: { x: 3.75, y: 84, w: 22.5, h: 11 } },
  // 도킹 순서: 케이스는 관계 상대(쿨러·파워·GPU)가 셋이라 가운데 — 세 선이 모두 짧아진다.
  { category: 'COOLER', label: '쿨러', glyph: '/slot-board/parts/cooler.svg', mount: 'dock', layout: { x: 70, y: 4, w: 27.5, h: 28 } },
  { category: 'CASE', label: '케이스', glyph: '/slot-board/parts/case.svg', mount: 'dock', layout: { x: 70, y: 36, w: 27.5, h: 28 } },
  { category: 'PSU', label: '파워', glyph: '/slot-board/parts/psu.svg', mount: 'dock', layout: { x: 70, y: 68, w: 27.5, h: 28 } }
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
   * 곡선의 볼록 정도(%). 양수 = 보드 바깥쪽으로 휨, 음수 = 중앙 쪽으로 휨.
   * implied 관계·직선에서는 무시된다.
   */
  bow?: number;
  /** 라벨 위치(0=from 쪽 … 1=to 쪽, 기본 0.5) — 이웃 라벨과 겹칠 때만 조정한다. */
  labelT?: number;
  /**
   * 실장 자체가 관계를 표현하는 경우(CPU가 소켓에, RAM이 DIMM에 꽂혀 있음) 선을 그리지 않고
   * 상태 점/문제 라벨만 해당 실장 지점 옆에 표시한다.
   */
  implied?: boolean;
};

export const FALLBACK_EDGES: SlotEdgeConfig[] = [
  // 보드 위 실장 관계 — 꽂혀 있는 그림 자체가 관계라 선은 생략하고 상태만 표시한다.
  { from: 'CPU', to: 'MOTHERBOARD', label: '소켓 호환', implied: true },
  { from: 'MOTHERBOARD', to: 'RAM', label: '메모리 규격', implied: true },
  { from: 'GPU', to: 'MOTHERBOARD', label: 'PCIe x16', implied: true },
  // 도킹 부품 ↔ 보드/부품 관계 — 물리적 의미가 있는 연결선으로 그린다.
  { from: 'PSU', to: 'MOTHERBOARD', label: '24핀 전원', labelT: 0.35 },
  // DIMM 위 상단 가장자리 회랑으로 아치 — 라벨은 쿨러 쪽 거터에.
  { from: 'COOLER', to: 'CPU', label: '쿨러 장착', bow: 18, labelT: 0.18 },
  { from: 'COOLER', to: 'CASE', label: '높이 여유', bow: 4 },
  { from: 'PSU', to: 'CASE', label: '파워 깊이', bow: 4 },
  // M.2(SSD) 실장 지점을 피해서 칩셋 위 회랑으로 우회한다.
  { from: 'GPU', to: 'PSU', label: '전력 여유', bow: -10, labelT: 0.6 },
  { from: 'GPU', to: 'CASE', label: '장착 길이', labelT: 0.6 }
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
