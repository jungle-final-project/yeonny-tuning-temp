import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';

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

/**
 * 보드 위 떠 있는 카드(후보 패널·관계/문제 팝오버)를 헤더로 잡아 옮기는 공용 드래그 훅.
 * - 데스크톱(lg 이상) 전용 — 모바일 바텀시트/인라인 렌더는 고정.
 * - 경계는 화면(뷰포트): 보드 밖으로도 밀어낼 수 있되, 카드의 일부(48px)와 헤더 상단은
 *   항상 화면 안에 남아 완전히 잃어버리지 않는다. 다시 열면 초기 위치로 돌아온다.
 * - 핸들 안의 컨트롤(button·select·input·label·a) 조작은 드래그로 삼키지 않는다.
 * - resetKey가 바뀌면(다른 슬롯으로 다시 열림) 위치와 리사이즈 크기를 초기화한다.
 * - 핸들 더블클릭 = 원위치+원크기(resetDrag).
 */
export function useBoardDrag<T extends HTMLElement>({ resetKey }: { resetKey?: unknown } = {}) {
  const targetRef = useRef<T | null>(null);
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 });
  const [isDragging, setIsDragging] = useState(false);
  const dragSessionRef = useRef<{
    pointerId: number;
    startX: number;
    startY: number;
    baseX: number;
    baseY: number;
    minDx: number;
    maxDx: number;
    minDy: number;
    maxDy: number;
  } | null>(null);

  const resetDrag = () => {
    setDragOffset({ x: 0, y: 0 });
    const target = targetRef.current;
    if (target) {
      // 네이티브 리사이즈가 남긴 인라인 크기도 함께 초기화한다.
      target.style.width = '';
      target.style.height = '';
    }
  };

  useEffect(() => {
    resetDrag();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resetKey]);

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
      baseX: dragOffset.x,
      baseY: dragOffset.y,
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
      const dx = Math.min(Math.max(move.clientX - session.startX, session.minDx), session.maxDx);
      const dy = Math.min(Math.max(move.clientY - session.startY, session.minDy), session.maxDy);
      setDragOffset({ x: session.baseX + dx, y: session.baseY + dy });
    };
    const handleUp = (up: globalThis.PointerEvent) => {
      if (dragSessionRef.current && up.pointerId !== dragSessionRef.current.pointerId) {
        return;
      }
      dragSessionRef.current = null;
      setIsDragging(false);
      window.removeEventListener('pointermove', handleMove);
      window.removeEventListener('pointerup', handleUp);
      window.removeEventListener('pointercancel', handleUp);
    };
    window.addEventListener('pointermove', handleMove);
    window.addEventListener('pointerup', handleUp);
    window.addEventListener('pointercancel', handleUp);
  };

  const dragStyle = dragOffset.x !== 0 || dragOffset.y !== 0
    ? { transform: `translate(${dragOffset.x}px, ${dragOffset.y}px)` }
    : undefined;

  return { targetRef, dragOffset, dragStyle, isDragging, startDrag, resetDrag };
}
