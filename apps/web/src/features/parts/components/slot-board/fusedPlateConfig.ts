import type { PartCategory } from '../../../quote/aiSelection';

export const FUSED_PLATE_BG = '/slot-board/backgrounds/dependency-plate-before.png';
export const FUSED_BOARD_SIZE = { width: 1672, height: 941 } as const;

export const FUSED_LAYER_ASSETS = {
  CPU: ['/slot-board/parts/cpu-fused-overlay.png'],
  RAM: [
    '/slot-board/parts/ram-slot-1-fused-overlay.png',
    '/slot-board/parts/ram-slot-2-fused-overlay.png',
    '/slot-board/parts/ram-slot-3-fused-overlay.png',
    '/slot-board/parts/ram-slot-4-fused-overlay.png'
  ],
  GPU: ['/slot-board/parts/gpu-fused-overlay.png'],
  STORAGE: ['/slot-board/parts/ssd-fused-overlay.png'],
  MOTHERBOARD: ['/slot-board/parts/motherboard-fused-overlay.png'],
  COOLER: ['/slot-board/parts/cooler-fused-overlay.png'],
  CASE: ['/slot-board/parts/case-fused-overlay.png'],
  PSU: ['/slot-board/parts/psu-fused-overlay.png']
} satisfies Record<PartCategory, string[]>;

export const FUSED_PRELOAD_URLS = [
  FUSED_PLATE_BG,
  ...Object.values(FUSED_LAYER_ASSETS).flat()
];

export type FusedPartLayer = {
  category: PartCategory;
  src: string;
  slotIndex?: number;
};

export const FUSED_PART_LAYERS: FusedPartLayer[] = [
  { category: 'MOTHERBOARD', src: FUSED_LAYER_ASSETS.MOTHERBOARD[0] },
  { category: 'CASE', src: FUSED_LAYER_ASSETS.CASE[0] },
  { category: 'PSU', src: FUSED_LAYER_ASSETS.PSU[0] },
  { category: 'STORAGE', src: FUSED_LAYER_ASSETS.STORAGE[0] },
  ...FUSED_LAYER_ASSETS.RAM.map((src, slotIndex) => ({ category: 'RAM' as const, src, slotIndex })),
  { category: 'CPU', src: FUSED_LAYER_ASSETS.CPU[0] },
  { category: 'COOLER', src: FUSED_LAYER_ASSETS.COOLER[0] },
  { category: 'GPU', src: FUSED_LAYER_ASSETS.GPU[0] }
];

export type FusedPartArea = {
  category: PartCategory;
  label: string;
  box: { x: number; y: number; w: number; h: number };
};

export const FUSED_BADGE_ANCHORS: Record<PartCategory, { x: number; y: number }> = {
  CPU: { x: 1485, y: 570 },
  MOTHERBOARD: { x: 1043, y: 159 },
  RAM: { x: 477, y: 245 },
  GPU: { x: 1031, y: 532 },
  STORAGE: { x: 479, y: 171 },
  PSU: { x: 409, y: 533 },
  CASE: { x: 1508, y: 105 },
  COOLER: { x: 1372, y: 524 }
};

export const FUSED_PART_AREAS: FusedPartArea[] = [
  { category: 'STORAGE', label: 'SSD', box: { x: 190, y: 150, w: 310, h: 95 } },
  { category: 'RAM', label: 'RAM', box: { x: 160, y: 230, w: 345, h: 295 } },
  { category: 'MOTHERBOARD', label: '메인보드', box: { x: 535, y: 150, w: 520, h: 355 } },
  { category: 'CASE', label: '케이스', box: { x: 1110, y: 155, w: 380, h: 370 } },
  { category: 'PSU', label: '파워', box: { x: 145, y: 520, w: 300, h: 255 } },
  { category: 'GPU', label: 'GPU', box: { x: 480, y: 515, w: 610, h: 255 } },
  { category: 'COOLER', label: '쿨러', box: { x: 1090, y: 515, w: 282, h: 260 } },
  { category: 'CPU', label: 'CPU', box: { x: 1340, y: 550, w: 170, h: 160 } }
];
