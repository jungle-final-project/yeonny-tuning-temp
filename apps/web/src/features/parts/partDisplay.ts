import type { PartRow, QuoteDraftItem } from './types';

type DisplayPart = PartRow | QuoteDraftItem;

const specFields: Record<string, Array<[string, string, string?]>> = {
  CPU: [
    ['소켓', 'socket'],
    ['코어', 'cores', '코어'],
    ['스레드', 'threads', '스레드'],
    ['TDP', 'tdpW', 'W'],
    ['전력', 'wattage', 'W'],
    ['메모리', 'memoryType'],
    ['내장그래픽', 'integratedGraphics']
  ],
  MOTHERBOARD: [
    ['소켓', 'socket'],
    ['칩셋', 'chipset'],
    ['규격', 'formFactor'],
    ['메모리', 'memoryType'],
    ['메모리 슬롯', 'memorySlots', '개'],
    ['최대 메모리', 'maxMemoryGb', 'GB'],
    ['Wi-Fi', 'wifi']
  ],
  RAM: [
    ['타입', 'memoryType'],
    ['용량', 'capacityGb', 'GB'],
    ['클럭', 'speedMhz', 'MHz'],
    ['CL', 'cl'],
    ['구성', 'kit'],
    ['모듈 수', 'moduleCount', '개']
  ],
  GPU: [
    ['GPU', 'chipset'],
    ['VRAM', 'vramGb', 'GB'],
    ['VRAM 타입', 'memoryType'],
    ['인터페이스', 'interface'],
    ['사용전력', 'wattage', 'W'],
    ['권장 파워', 'requiredSystemPowerW', 'W'],
    ['길이', 'lengthMm', 'mm'],
    ['두께', 'thicknessMm', 'mm'],
    ['슬롯', 'slotWidth'],
    ['전원 포트', 'powerConnector']
  ],
  STORAGE: [
    ['용량', 'capacityGb', 'GB'],
    ['인터페이스', 'interface'],
    ['폼팩터', 'formFactor'],
    ['읽기', 'readMbS', 'MB/s'],
    ['쓰기', 'writeMbS', 'MB/s']
  ],
  PSU: [
    ['정격 출력', 'capacityW', 'W'],
    ['인증', 'efficiency'],
    ['규격', 'formFactor'],
    ['ATX 버전', 'atxVersion'],
    ['PCIe 5.x', 'pcie5Support'],
    ['12V-2x6', 'connector12V2x6']
  ],
  CASE: [
    ['규격', 'formFactor'],
    ['메인보드 지원', 'supportedMotherboards'],
    ['GPU 최대 길이', 'maxGpuLengthMm', 'mm'],
    ['CPU 쿨러 높이', 'maxCpuCoolerHeightMm', 'mm'],
    ['라디에이터', 'radiatorSupport'],
    ['크기', 'dimensionsMm']
  ],
  COOLER: [
    ['타입', 'coolerType'],
    ['라디에이터', 'radiatorSizeMm', 'mm'],
    ['높이', 'heightMm', 'mm'],
    ['해소 TDP', 'tdpW', 'W'],
    ['소켓 지원', 'socketSupport']
  ]
};

export function partImageUrl(part: DisplayPart) {
  const imageUrl = part.externalOffer?.imageUrl ?? part.attributes?.imageUrl;
  if (typeof imageUrl === 'string' && imageUrl.trim()) {
    return imageUrl;
  }
  return partImagePlaceholder(part.category);
}

export function partImagePlaceholder(category?: string) {
  const label = category === 'STORAGE' ? 'SSD' : category ?? 'PART';
  const accent = categoryAccent(category ?? '');
  const svg = `
    <svg xmlns="http://www.w3.org/2000/svg" width="112" height="112" viewBox="0 0 112 112">
      <rect width="112" height="112" rx="14" fill="#f8fafc"/>
      <rect x="12" y="20" width="88" height="56" rx="10" fill="${accent}" opacity="0.92"/>
      <rect x="20" y="28" width="72" height="40" rx="6" fill="#ffffff" opacity="0.16"/>
      <text x="56" y="54" text-anchor="middle" font-family="Arial, sans-serif" font-size="18" font-weight="700" fill="#ffffff">${label}</text>
      <rect x="24" y="84" width="64" height="6" rx="3" fill="#cbd5e1"/>
    </svg>
  `;
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
}

// 외부 오퍼 이미지 URL이 만료(404 등)되면 깨진 아이콘 대신 카테고리 플레이스홀더로 교체한다.
// dataset 플래그로 onError 재발화(플레이스홀더 자체 실패) 무한 루프를 막는다.
export function handlePartImageError(event: { currentTarget: HTMLImageElement }, category?: string) {
  const image = event.currentTarget;
  if (image.dataset.fallbackApplied === 'true') {
    return;
  }
  image.dataset.fallbackApplied = 'true';
  image.src = partImagePlaceholder(category);
}

export function partShortSpec(part: DisplayPart) {
  const shortSpec = part.attributes?.shortSpec;
  return typeof shortSpec === 'string' ? shortSpec : part.category;
}

export function specRows(part: DisplayPart) {
  const fields = specFields[part.category] ?? [];
  return fields
    .map(([label, key, unit]) => ({ label, value: formatSpecValue(part.attributes?.[key], unit) }))
    .filter((row) => row.value !== null) as Array<{ label: string; value: string }>;
}

export function fullSpecLine(part: DisplayPart) {
  const rows = specRows(part);
  if (rows.length === 0) {
    return partShortSpec(part);
  }
  return rows.map((row) => `${row.label}: ${row.value}`).join(' / ');
}

export function categoryAccent(category: string) {
  switch (category) {
    case 'CPU':
      return '#2563eb';
    case 'MOTHERBOARD':
      return '#475569';
    case 'RAM':
      return '#16a34a';
    case 'GPU':
      return '#7c3aed';
    case 'STORAGE':
      return '#0891b2';
    case 'PSU':
      return '#ca8a04';
    case 'CASE':
      return '#dc2626';
    case 'COOLER':
      return '#0f766e';
    default:
      return '#334155';
  }
}

function formatSpecValue(value: unknown, unit?: string): string | null {
  if (value === undefined || value === null || value === '') {
    return null;
  }
  if (typeof value === 'boolean') {
    return value ? '지원' : '미지원';
  }
  if (typeof value === 'number') {
    return `${value.toLocaleString()}${unit ? unit : ''}`;
  }
  if (Array.isArray(value)) {
    const items = value.map(String).filter(Boolean);
    return items.length === 0 ? null : items.join(', ');
  }
  if (typeof value === 'object') {
    const record = value as Record<string, unknown>;
    const depth = numberText(record.depth);
    const width = numberText(record.width);
    const height = numberText(record.height);
    if (depth && width && height) {
      return `${depth} x ${width} x ${height}mm`;
    }
    return Object.entries(record)
      .map(([key, item]) => `${key}:${String(item)}`)
      .join(', ');
  }
  const text = String(value).trim();
  return text ? `${text}${unit && /^\d+$/.test(text) ? unit : ''}` : null;
}

function numberText(value: unknown) {
  if (typeof value === 'number') {
    return value.toLocaleString();
  }
  if (typeof value === 'string' && value.trim()) {
    return value.trim();
  }
  return null;
}
