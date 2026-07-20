import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent
} from 'react';

/** lg(1024px) 이상 여부 — 떠 있는 카드의 포탈/드래그/리사이즈는 데스크톱 전용이다. */
export function useIsDesktop() {
  const [isDesktop, setIsDesktop] = useState(() =>
    typeof window !== 'undefined' && window.matchMedia('(min-width: 1024px)').matches
  );
  useEffect(() => {
    const media = window.matchMedia('(min-width: 1024px)');
    const handle = (event: MediaQueryListEvent) => setIsDesktop(event.matches);
    media.addEventListener('change', handle);
    return () => media.removeEventListener('change', handle);
  }, []);
  return isDesktop;
}

// 카드가 화면 밖으로 나가더라도 이만큼은 남겨 항상 다시 잡을 수 있게 한다.
const MIN_VISIBLE_PX = 48;

/** 사용자가 옮겨 놓은 자리(화면 절대 좌표). */
type PlacedPoint = { left: number; top: number };
/** 사용자가 조절해 둔 크기. 옮긴 자리와 함께 한 벌로 기억한다. */
type PlacedSize = { width: number; height: number };
type PlacedRect = PlacedPoint & { size?: PlacedSize };

// 옮겨 놓은 자리는 카드가 사라져도(부품 전환·닫기) 이 모듈에 남는다 — "가져다 놓고 쓰는" 도구라
// 한 번 놓으면 그 자리를 지킨다. 핸들 더블클릭이 유일한 수동 해제다.
const placedByKey = new Map<string, PlacedRect>();

// remember:'local'이면 새로고침·다음 방문까지 남긴다. 부품 목록 패널은 사용자가 자기 화면에 맞춰
// 한 번 배치해 두고 계속 쓰는 도구라, 카테고리를 바꿔도 다시 맞추게 하지 않는다(키를 부품별로 쪼개지 않는 이유).
const STORAGE_PREFIX = 'buildgraph.boardPlacement.';

function readPlaced(persistKey: string, remember: 'session' | 'local'): PlacedRect | null {
  const cached = placedByKey.get(persistKey);
  if (cached || remember === 'session') {
    return cached ?? null;
  }
  try {
    const raw = localStorage.getItem(`${STORAGE_PREFIX}${persistKey}`);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<PlacedRect>;
    if (typeof parsed?.left !== 'number' || typeof parsed?.top !== 'number') return null;
    const size = parsed.size;
    const rect: PlacedRect = {
      left: parsed.left,
      top: parsed.top,
      size: typeof size?.width === 'number' && typeof size?.height === 'number'
        ? { width: size.width, height: size.height }
        : undefined
    };
    placedByKey.set(persistKey, rect);
    return rect;
  } catch {
    // 저장소를 못 읽으면(프라이빗 모드·손상) 기억만 포기한다 — 기본 자리로 뜨면 그만이다.
    return null;
  }
}

function writePlaced(persistKey: string, rect: PlacedRect, remember: 'session' | 'local') {
  placedByKey.set(persistKey, rect);
  if (remember === 'session') return;
  try {
    localStorage.setItem(`${STORAGE_PREFIX}${persistKey}`, JSON.stringify(rect));
  } catch {
    // 저장 실패는 이번 세션 기억(Map)까지만 유지한다.
  }
}

function clearPlaced(persistKey: string, remember: 'session' | 'local') {
  placedByKey.delete(persistKey);
  if (remember === 'session') return;
  try {
    localStorage.removeItem(`${STORAGE_PREFIX}${persistKey}`);
  } catch {
    // 지우지 못해도 이번 화면은 이미 기본 자리로 돌아갔다.
  }
}

/** 화면이 줄어 기억해둔 자리가 밖으로 나갔을 때, 다시 잡을 수 있는 위치로 끌어들인다. */
function clampIntoViewport(point: PlacedPoint, width: number): PlacedPoint {
  if (typeof window === 'undefined') {
    return point;
  }
  const minLeft = MIN_VISIBLE_PX - width;
  const maxLeft = window.innerWidth - MIN_VISIBLE_PX;
  const maxTop = Math.max(window.innerHeight - MIN_VISIBLE_PX, 0);
  return {
    left: Math.min(Math.max(point.left, minLeft), maxLeft),
    // 헤더(잡는 곳)가 화면 위로 사라지면 다시 옮길 수 없다 — 위쪽은 0 아래로 못 간다.
    top: Math.min(Math.max(point.top, 0), maxTop)
  };
}

/**
 * 보드 위 떠 있는 카드(후보 패널·관계/문제 팝오버)를 헤더로 잡아 옮기는 공용 드래그 훅.
 * - 데스크톱(lg 이상) 전용 — 모바일 바텀시트/인라인 렌더는 고정이라 위치 스타일을 주지 않는다.
 * - anchor = 시스템이 정한 기본 자리(팝오버는 부품 옆, 패널은 보드 좌상단). 아직 안 옮겼으면 여기 뜬다.
 * - 한 번 옮기면 그 자리를 persistKey에 절대 좌표로 기억해, 부품을 바꾸거나 닫았다 열어도 그대로 있다.
 * - 경계는 화면(뷰포트): 보드 밖으로도 밀어낼 수 있되, 카드의 일부(48px)와 헤더 상단은 항상 남는다.
 * - 핸들 안의 컨트롤(button·select·input·label·a) 조작은 드래그로 삼키지 않는다.
 * - 핸들 더블클릭 = 기억한 자리를 버리고 기본 자리·기본 크기로(resetDrag).
 * - remember:'local' + rememberSize면 자리와 크기를 새로고침 너머까지 기억한다.
 */
export function useBoardDrag<T extends HTMLElement>({
  persistKey,
  anchor,
  remember = 'session',
  rememberSize = false
}: {
  persistKey: string;
  anchor: PlacedPoint;
  remember?: 'session' | 'local';
  rememberSize?: boolean;
}) {
  const isDesktop = useIsDesktop();
  const targetRef = useRef<T | null>(null);
  // 저장소는 마운트 때만 읽는다 — 검색어 입력처럼 잦은 재렌더마다 파싱하지 않도록.
  const [placed, setPlaced] = useState<PlacedPoint | null>(() => {
    const rect = readPlaced(persistKey, remember);
    return rect ? { left: rect.left, top: rect.top } : null;
  });
  // 크기도 마운트 시점에 한 번만 집는다 — 리사이즈 중에 이 값이 바뀌면 React가 폭·높이를 되돌려 써
  // 사용자가 끌고 있는 모서리와 싸운다. 되돌리기(resetDrag)만 이 값을 비운다.
  const [rememberedSize, setRememberedSize] = useState<PlacedSize | null>(
    () => readPlaced(persistKey, remember)?.size ?? null
  );
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 });
  const [isDragging, setIsDragging] = useState(false);
  const dragSessionRef = useRef<{
    pointerId: number;
    startX: number;
    startY: number;
    minDx: number;
    maxDx: number;
    minDy: number;
    maxDy: number;
  } | null>(null);
  // 드래그 중 최신 오프셋 — 종료 시 절대 좌표로 확정할 때 읽는다(상태 갱신 함수 안에서 부수효과를 내지 않도록).
  const offsetRef = useRef({ x: 0, y: 0 });

  const base = placed ?? anchor;
  // 이벤트 핸들러가 최신 기준점을 읽도록 — 드래그 중 재렌더로 base가 바뀌어도 세션은 흔들리지 않는다.
  const baseRef = useRef(base);
  baseRef.current = base;

  // 사용자가 모서리를 끌어 바꾼 크기를 기억한다. 네이티브 [resize:both]는 이벤트를 내지 않아
  // ResizeObserver로만 알 수 있다. 첫 관찰은 '열면서 적용한 크기'라 사용자의 뜻이 아니므로 건너뛴다.
  const sizeSeenRef = useRef(false);
  // 디바운스 타이머와 마지막 측정치는 ref에 둔다 — 되돌리기가 대기 중인 저장을 취소해야 하고,
  // 언마운트 때는 반대로 대기 중인 저장을 흘려보내야 한다.
  const sizeWriteTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const pendingSizeRef = useRef<PlacedSize | null>(null);

  const resetDrag = () => {
    // 되돌리기가 만드는 크기 변화(기억한 크기 → 기본 크기)도 ResizeObserver에 배달된다.
    // 다시 무장하지 않으면 그 변화를 '사용자 리사이즈'로 오해해 200ms 뒤 방금 지운 자리를
    // 되살려 쓴다 — 유일한 수동 해제 수단이 새로고침을 못 넘긴다.
    clearTimeout(sizeWriteTimerRef.current);
    sizeWriteTimerRef.current = undefined;
    pendingSizeRef.current = null;
    sizeSeenRef.current = false;
    clearPlaced(persistKey, remember);
    setPlaced(null);
    setRememberedSize(null);
    setDragOffset({ x: 0, y: 0 });
    offsetRef.current = { x: 0, y: 0 };
    const target = targetRef.current;
    if (target) {
      // 네이티브 리사이즈가 남긴 인라인 크기도 함께 초기화한다.
      target.style.width = '';
      target.style.height = '';
    }
  };

  useEffect(() => {
    const target = targetRef.current;
    if (!rememberSize || !isDesktop || !target || typeof ResizeObserver === 'undefined') {
      return;
    }
    sizeSeenRef.current = false;
    const observer = new ResizeObserver((entries) => {
      const box = entries[0]?.contentRect;
      if (!box || box.width === 0) return;
      if (!sizeSeenRef.current) {
        sizeSeenRef.current = true;
        return;
      }
      // 끄는 동안 매 픽셀 저장하지 않는다 — 손을 멈춘 뒤 한 번만 쓴다.
      const rect = target.getBoundingClientRect();
      pendingSizeRef.current = { width: rect.width, height: rect.height };
      clearTimeout(sizeWriteTimerRef.current);
      sizeWriteTimerRef.current = setTimeout(() => {
        const size = pendingSizeRef.current;
        if (!size) return;
        pendingSizeRef.current = null;
        writePlaced(persistKey, { ...baseRef.current, size }, remember);
      }, 200);
    });
    observer.observe(target);
    return () => {
      clearTimeout(sizeWriteTimerRef.current);
      sizeWriteTimerRef.current = undefined;
      // 크기를 바꾸자마자 닫으면 대기 중이던 저장이 취소돼 마지막 조작만 사라진다 — 흘려보낸다.
      const size = pendingSizeRef.current;
      pendingSizeRef.current = null;
      if (size) {
        writePlaced(persistKey, { ...baseRef.current, size }, remember);
      }
      observer.disconnect();
    };
  }, [isDesktop, persistKey, remember, rememberSize, targetRef]);

  // 창이 작아지면 기억해둔 자리가 화면 밖일 수 있다 — 복원 직후와 리사이즈 때 다시 잡을 수 있게 끌어들인다.
  useEffect(() => {
    if (!placed || !isDesktop) {
      return;
    }
    const pull = () => {
      const target = targetRef.current;
      const current = baseRef.current;
      const rect = target?.getBoundingClientRect();
      const next = clampIntoViewport(current, rect?.width ?? 0);
      if (next.left !== current.left || next.top !== current.top) {
        writePlaced(persistKey, { ...next, size: placedByKey.get(persistKey)?.size }, remember);
        setPlaced(next);
      }
    };
    pull();
    window.addEventListener('resize', pull);
    return () => window.removeEventListener('resize', pull);
  }, [placed, persistKey, isDesktop]);

  const startDrag = (event: ReactPointerEvent<HTMLElement>) => {
    if ((event.target as HTMLElement).closest('button, select, input, label, a')) {
      return;
    }
    if (typeof window === 'undefined' || !window.matchMedia('(min-width: 1024px)').matches) {
      return;
    }
    const target = targetRef.current;
    if (!target) {
      return;
    }
    const rect = target.getBoundingClientRect();
    const viewportWidth = window.innerWidth;
    const viewportHeight = window.innerHeight;
    dragSessionRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      // 화면 경계 클램프: 좌우는 48px 가시 유지, 위로는 헤더가 화면 위로 사라지지 않게.
      minDx: MIN_VISIBLE_PX - rect.right,
      maxDx: viewportWidth - MIN_VISIBLE_PX - rect.left,
      minDy: -rect.top,
      maxDy: viewportHeight - MIN_VISIBLE_PX - rect.top
    };
    setIsDragging(true);
    event.preventDefault();
    const handleMove = (move: globalThis.PointerEvent) => {
      const session = dragSessionRef.current;
      if (!session || move.pointerId !== session.pointerId) {
        return;
      }
      const next = {
        x: Math.min(Math.max(move.clientX - session.startX, session.minDx), session.maxDx),
        y: Math.min(Math.max(move.clientY - session.startY, session.minDy), session.maxDy)
      };
      offsetRef.current = next;
      setDragOffset(next);
    };
    const handleUp = (up: globalThis.PointerEvent) => {
      if (dragSessionRef.current && up.pointerId !== dragSessionRef.current.pointerId) {
        return;
      }
      dragSessionRef.current = null;
      setIsDragging(false);
      // 옮긴 만큼을 절대 좌표로 확정하고 오프셋은 0으로 — 같은 렌더에 반영돼 화면은 그대로다.
      const offset = offsetRef.current;
      if (offset.x !== 0 || offset.y !== 0) {
        const next = { left: baseRef.current.left + offset.x, top: baseRef.current.top + offset.y };
        writePlaced(persistKey, { ...next, size: placedByKey.get(persistKey)?.size }, remember);
        setPlaced(next);
        offsetRef.current = { x: 0, y: 0 };
        setDragOffset({ x: 0, y: 0 });
      }
      window.removeEventListener('pointermove', handleMove);
      window.removeEventListener('pointerup', handleUp);
      window.removeEventListener('pointercancel', handleUp);
    };
    window.addEventListener('pointermove', handleMove);
    window.addEventListener('pointerup', handleUp);
    window.addEventListener('pointercancel', handleUp);
  };

  // 모바일(바텀시트·인라인)은 좌표를 주지 않는다 — 레이아웃이 위치를 소유한다.
  const dragStyle: CSSProperties | undefined = isDesktop
    ? {
        left: base.left,
        top: base.top,
        ...(dragOffset.x !== 0 || dragOffset.y !== 0
          ? { transform: `translate(${dragOffset.x}px, ${dragOffset.y}px)` }
          : {})
      }
    : undefined;

  return {
    targetRef,
    dragStyle,
    isDragging,
    startDrag,
    resetDrag,
    isPlaced: placed !== null,
    rememberedSize
  };
}
