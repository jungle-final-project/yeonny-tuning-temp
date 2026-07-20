import { useRef, useState, type ReactNode } from 'react';
import { HelpCircle } from 'lucide-react';

// 기능 설명 말풍선 — hover/focus 시 짙은 회색 말풍선으로 기능을 설명한다.
// children이 있으면 그 요소가 트리거가 되고, 없으면 ? 아이콘 버튼을 트리거로 그린다.
// overlay는 조상 overflow-hidden(셀프견적 한 화면 캔버스의 main 등)에 말풍선이 잘릴 때
// position: fixed로 띄우는 탈출구다 — hover 시점에 앵커 좌표를 계산한다.
export function HelpTip({
  label,
  text,
  placement = 'bottom',
  align = 'left',
  overlay = false,
  children
}: {
  label?: string;
  text: string;
  placement?: 'top' | 'bottom';
  align?: 'left' | 'right';
  overlay?: boolean;
  children?: ReactNode;
}) {
  const wrapRef = useRef<HTMLSpanElement>(null);
  const bubbleRef = useRef<HTMLSpanElement>(null);
  const [overlayPos, setOverlayPos] = useState<{ left: number; top: number } | null>(null);

  const syncOverlayPos = () => {
    if (!overlay || !wrapRef.current || !bubbleRef.current) return;
    const anchor = wrapRef.current.getBoundingClientRect();
    const bubble = bubbleRef.current.getBoundingClientRect();
    const left = align === 'right' ? anchor.right - bubble.width : anchor.left;
    const top = placement === 'top' ? anchor.top - 8 - bubble.height : anchor.bottom + 8;
    setOverlayPos({ left: Math.max(8, left), top: Math.max(8, top) });
  };

  const placementClass = placement === 'top' ? 'bottom-full mb-2' : 'top-full mt-2';
  const alignClass = align === 'right' ? 'right-0' : 'left-0';

  return (
    <span
      ref={wrapRef}
      className="group relative inline-flex shrink-0"
      onMouseEnter={syncOverlayPos}
      onFocus={syncOverlayPos}
    >
      {children ?? (
        <button
          type="button"
          aria-label={label}
          className="grid h-5 w-5 place-items-center rounded-full text-slate-400 transition hover:text-slate-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-200"
        >
          <HelpCircle size={13} aria-hidden="true" />
        </button>
      )}
      <span
        ref={bubbleRef}
        role="tooltip"
        className={`pointer-events-none z-50 w-60 rounded-xl bg-slate-600/95 px-3.5 py-2.5 text-left text-[11px] font-semibold leading-relaxed text-white shadow-lg opacity-0 transition group-hover:opacity-100 group-focus-within:opacity-100 ${
          overlay ? 'fixed' : `absolute ${placementClass} ${alignClass}`
        }`}
        style={overlay ? (overlayPos ?? { left: -9999, top: -9999 }) : undefined}
      >
        {text}
      </span>
    </span>
  );
}
